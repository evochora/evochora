package org.evochora.node.processes.http.api.visualizer;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.evochora.datapipeline.CellStateTestHelper;
import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.contracts.CellHttpResponse;
import org.evochora.datapipeline.api.contracts.EnvironmentHttpResponse;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.resources.database.H2Database;
import org.evochora.datapipeline.resources.storage.FileSystemStorageResource;
import org.evochora.datapipeline.resources.topics.H2TopicResource;
import org.evochora.datapipeline.services.indexers.EnvironmentIndexer;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.node.spi.ServiceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import io.javalin.Javalin;
import io.restassured.response.Response;

/**
 * Integration test for EnvironmentIndexer and EnvironmentController.
 * <p>
 * Tests end-to-end flow:
 * <ul>
 *   <li>Write test data to storage</li>
 *   <li>EnvironmentIndexer indexes data into H2 database</li>
 *   <li>EnvironmentController reads data via HTTP API</li>
 * </ul>
 * <p>
 * Uses real H2Database, real FileSystemStorageResource, real H2TopicResource, and real EnvironmentController.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class EnvironmentControllerIntegrationTest {

    private H2Database testDatabase;
    private FileSystemStorageResource testStorage;
    private H2TopicResource<BatchInfo> testBatchTopic;
    private EnvironmentIndexer<?> indexer;
    private Javalin app;
    private Path tempStorageDir;
    private String dbUrl;

    @BeforeEach
    void setUp() throws IOException {
        // Setup temporary storage for file operations
        tempStorageDir = Files.createTempDirectory("evochora-test-env-controller-");
        Config storageConfig = ConfigFactory.parseString(
            "rootDirectory = \"" + tempStorageDir.toAbsolutePath().toString().replace("\\", "/") + "\""
        );
        testStorage = new FileSystemStorageResource("test-storage", storageConfig);

        // Setup in-memory H2 database with unique name for parallel testing
        dbUrl = "jdbc:h2:mem:test-env-controller-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        Config dbConfig = ConfigFactory.parseString(
            "jdbcUrl = \"" + dbUrl + "\"\n" +
            "username = \"sa\"\n" +
            "password = \"\"\n" +
            "maxPoolSize = 10\n" +
            "h2EnvironmentStrategy {\n" +
            "  className = \"org.evochora.datapipeline.resources.database.h2.RowPerChunkStrategy\"\n" +
            "  options { compression { enabled = true, codec = \"zstd\", level = 3 } }\n" +
            "}\n"
        );
        testDatabase = new H2Database("test-db", dbConfig);

        // Setup H2 topic for batch processing
        String topicJdbcUrl = "jdbc:h2:mem:test-batch-topic-" + UUID.randomUUID();
        Config topicConfig = ConfigFactory.parseString(
            "jdbcUrl = \"" + topicJdbcUrl + "\"\n" +
            "username = \"sa\"\n" +
            "password = \"\"\n" +
            "claimTimeout = 300"
        );
        testBatchTopic = new H2TopicResource<>("batch-topic", topicConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Stop HTTP server
        if (app != null) {
            app.stop();
        }

        // Stop indexer if still running
        if (indexer != null && indexer.getCurrentState() != IService.State.STOPPED && indexer.getCurrentState() != IService.State.ERROR) {
            indexer.stop();
            await().atMost(5, TimeUnit.SECONDS)
                .until(() -> indexer.getCurrentState() == IService.State.STOPPED || indexer.getCurrentState() == IService.State.ERROR);
        }

        // Close resources
        if (testBatchTopic != null) {
            testBatchTopic.close();
        }

        if (testDatabase != null) {
            testDatabase.close();
        }

        // Cleanup temp directory
        if (tempStorageDir != null && Files.exists(tempStorageDir)) {
            Files.walk(tempStorageDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
        }
    }

    @Test
    void testEnvironmentIndexerToControllerFlow() throws Exception {
        // Given: Test run with metadata
        String runId = "test-run-" + UUID.randomUUID();
        SimulationMetadata metadata = createMetadata(runId, new int[]{10, 10}, false);

        // Index metadata first (required by EnvironmentIndexer)
        indexMetadata(runId, metadata);

        // Setup test data: 2D 10x10 grid, put cells at specific positions
        List<TickData> batch1 = List.of(
            TickData.newBuilder()
                .setTickNumber(1L)
                .setSimulationRunId(runId)
                .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                    CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build(),  // (0,0)
                    CellStateTestHelper.createCellStateBuilder(5, 101, 2, 60, 0).build(),  // (5,0)
                    CellStateTestHelper.createCellStateBuilder(15, 102, 1, 70, 0).build()  // (5,1)
                )))
                .build()
        );

        // Write batches to storage and send notifications
        writeBatchAndNotify(runId, batch1);

        // When: Start indexer
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 2000
            insertBatchSize = 100
            flushTimeoutMs = 1000
            """.formatted(runId));

        indexer = createEnvironmentIndexer("test-indexer", config);
        indexer.start();

        // Wait for indexer to process both batches
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("ticks_processed").longValue() >= 1);

        // Then: Verify metrics
        assertThat(indexer.getMetrics().get("ticks_processed").longValue()).isEqualTo(1);
        assertThat(indexer.getMetrics().get("batches_processed").longValue()).isEqualTo(1);

        // Now: Start HTTP server with EnvironmentController
        app = Javalin.create().start(0);
        int port = app.port();

        // Register database provider in controller registry
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IDatabaseReaderProvider.class, testDatabase);

        // Create and register EnvironmentController with HTTP cache enabled for testing
        Config controllerConfig = ConfigFactory.parseString("""
            http-cache {
              environment {
                enabled = true
                maxAge = 31536000
                useETag = true
              }
            }
            """);
        EnvironmentController controller = new EnvironmentController(registry, controllerConfig);
        controller.registerRoutes(app, "/visualizer/api/environment");

        // Query data via HTTP API using RestAssured (now returns Protobuf binary)
        Response resp = given()
            .port(port)
            .basePath("/visualizer/api/environment")
            .queryParam("region", "0,10,0,10")
            .queryParam("runId", runId)
            .accept("application/x-protobuf")
            .get("/1");
        
        // Verify HTTP response status and headers
        resp.then()
            .statusCode(200)
            .contentType("application/x-protobuf")
            .header("Cache-Control", containsString("must-revalidate"));  // HTTP cache header check
        
        // Parse Protobuf response
        byte[] responseBytes = resp.asByteArray();
        EnvironmentHttpResponse envResponse = EnvironmentHttpResponse.parseFrom(responseBytes);
        
        // Verify cell count
        assertThat(envResponse.getCellsCount()).isEqualTo(3);
        
        // Verify detailed cell data
        List<CellHttpResponse> cells = envResponse.getCellsList();
        
        // Cell at flatIndex=0 → (0,0)
        assertThat(cells.get(0).getCoordinatesList()).containsExactly(0, 0);
        assertThat(cells.get(0).getOwnerId()).isEqualTo(100);
        
        // Cell at flatIndex=5 → (0,5)
        assertThat(cells.get(1).getCoordinatesList()).containsExactly(0, 5);
        
        // Cell at flatIndex=15 → (1,5) in row-major
        assertThat(cells.get(2).getCoordinatesList()).containsExactly(1, 5);
    }

    @Test
    void httpEndpoint_cacheHeadersSet() throws Exception {
        // Given: Set up test data
        String runId = "test-run-" + UUID.randomUUID();
        SimulationMetadata metadata = createMetadata(runId, new int[]{10, 10}, false);
        
        indexMetadata(runId, metadata);
        
        List<TickData> batch1 = List.of(
            TickData.newBuilder()
                .setTickNumber(1L)
                .setSimulationRunId(runId)
                .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                    CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build()
                )))
                .build()
        );
        
        writeBatchAndNotify(runId, batch1);
        
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 2000
            insertBatchSize = 100
            flushTimeoutMs = 1000
            """.formatted(runId));
        
        indexer = createEnvironmentIndexer("test-indexer", config);
        indexer.start();
        
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("ticks_processed").longValue() >= 1);
        
        // Setup HTTP server
        app = Javalin.create().start(0);
        int port = app.port();
        
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IDatabaseReaderProvider.class, testDatabase);
        
        // Configure HTTP cache for testing (enabled with ETag)
        Config controllerConfig = ConfigFactory.parseString("""
            http-cache {
              environment {
                enabled = true
                maxAge = 31536000
                useETag = true
              }
            }
            """);
        EnvironmentController controller = new EnvironmentController(registry, controllerConfig);
        controller.registerRoutes(app, "/visualizer/api/environment");
        
        // When: Make HTTP request
        Response resp = given()
            .port(port)
            .basePath("/visualizer/api/environment")
            .queryParam("region", "0,10,0,10")
            .queryParam("runId", runId)
            .get("/1");
        
        // Then: Verify cache headers are set correctly
        resp.then()
            .statusCode(200)
            .header("Cache-Control", equalTo("public, max-age=31536000, must-revalidate"))
            .header("ETag", notNullValue());
        
        // ETag should contain runId (not _tick suffix, as per new implementation)
        assertThat(resp.header("ETag")).contains(runId);
    }

    @Test
    void httpEndpoint_runIdFallback_usesLatest() throws Exception {
        // Given: Set up test data with two runs (older and newer)
        // IMPORTANT: "findLatestRunId" sorts by SCHEMA_NAME alphabetically DESC.
        // Schema names are created as "SIM_" + sanitized(runId).uppercase().
        // To ensure the newer run comes first in DESC order, we need it to be lexicographically LAST.
        // Prefix "A" for older (comes BEFORE), "B" for newer (comes AFTER).
        String baseUuid = UUID.randomUUID().toString();
        String oldRunId = "A-older-" + baseUuid;
        String newRunId = "B-newer-" + baseUuid;
        
        // Setup separate topic for older run
        String oldTopicJdbcUrl = "jdbc:h2:mem:test-batch-topic-old-" + UUID.randomUUID();
        Config oldTopicConfig = ConfigFactory.parseString(
            "jdbcUrl = \"" + oldTopicJdbcUrl + "\"\n" +
            "username = \"sa\"\n" +
            "password = \"\"\n" +
            "claimTimeout = 300"
        );
        H2TopicResource<BatchInfo> oldBatchTopic = new H2TopicResource<>("batch-topic-old", oldTopicConfig);
        
        // Setup older run
        SimulationMetadata oldMetadata = createMetadata(oldRunId, new int[]{10, 10}, false);
        indexMetadata(oldRunId, oldMetadata);
        
        List<TickData> oldBatch = List.of(
            TickData.newBuilder()
                .setTickNumber(1L)
                .setSimulationRunId(oldRunId)
                .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                    CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build()
                )))
                .build()
        );
        
        writeBatchAndNotifyWithTopic(oldRunId, oldBatch, oldBatchTopic);
        
        Config oldConfig = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 2000
            insertBatchSize = 100
            flushTimeoutMs = 1000
            """.formatted(oldRunId));
        
        EnvironmentIndexer<?> oldIndexer = createEnvironmentIndexerWithTopic("old-indexer", oldConfig, oldBatchTopic);
        oldIndexer.start();
        
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> oldIndexer.getMetrics().get("ticks_processed").longValue() >= 1);
        oldIndexer.stop();
        oldBatchTopic.close();
        
        // Setup newer run (reuses testBatchTopic from setUp)
        // Note: We don't need a delay for different timestamps because schema names
        // are sorted alphabetically and our run IDs ensure the newer one sorts last
        SimulationMetadata newMetadata = createMetadata(newRunId, new int[]{10, 10}, false);
        indexMetadata(newRunId, newMetadata);
        
        List<TickData> newBatch = List.of(
            TickData.newBuilder()
                .setTickNumber(1L)
                .setSimulationRunId(newRunId)
                .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                    CellStateTestHelper.createCellStateBuilder(5, 200, 2, 75, 0).build()
                )))
                .build()
        );
        
        writeBatchAndNotify(newRunId, newBatch);
        
        Config newConfig = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 2000
            insertBatchSize = 100
            flushTimeoutMs = 1000
            """.formatted(newRunId));
        
        indexer = createEnvironmentIndexer("new-indexer", newConfig);
        indexer.start();
        
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("ticks_processed").longValue() >= 1);
        
        // Setup HTTP server
        app = Javalin.create().start(0);
        int port = app.port();
        
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IDatabaseReaderProvider.class, testDatabase);
        
        Config controllerConfig = ConfigFactory.empty();
        EnvironmentController controller = new EnvironmentController(registry, controllerConfig);
        controller.registerRoutes(app, "/visualizer/api/environment");
        
        // When: Make HTTP request WITHOUT runId parameter (should fallback to latest)
        Response resp = given()
            .port(port)
            .basePath("/visualizer/api/environment")
            .queryParam("region", "0,10,0,10")
            .accept("application/x-protobuf")
            // Note: NO runId parameter!
            .get("/1");
        
        // Then: Should return data from the NEWER run (latest)
        resp.then()
            .statusCode(200)
            .contentType("application/x-protobuf");
        
        // Parse Protobuf and verify
        byte[] responseBytes = resp.asByteArray();
        EnvironmentHttpResponse envResponse = EnvironmentHttpResponse.parseFrom(responseBytes);
        assertThat(envResponse.getCellsCount()).isEqualTo(1);
        assertThat(envResponse.getCells(0).getOwnerId()).isEqualTo(200); // Newer run has ownerId=200
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, messagePattern = ".*Region must have even number.*")
    void httpEndpoint_errorHandling_invalidRegion() throws Exception {
        // Given: Set up test data
        String runId = "test-run-" + UUID.randomUUID();
        SimulationMetadata metadata = createMetadata(runId, new int[]{10, 10}, false);
        
        indexMetadata(runId, metadata);
        
        List<TickData> batch1 = List.of(
            TickData.newBuilder()
                .setTickNumber(1L)
                .setSimulationRunId(runId)
                .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                    CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build()
                )))
                .build()
        );
        
        writeBatchAndNotify(runId, batch1);
        
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 2000
            insertBatchSize = 100
            flushTimeoutMs = 1000
            """.formatted(runId));
        
        indexer = createEnvironmentIndexer("test-indexer", config);
        indexer.start();
        
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("ticks_processed").longValue() >= 1);
        
        // Setup HTTP server
        app = Javalin.create().start(0);
        int port = app.port();
        
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IDatabaseReaderProvider.class, testDatabase);
        
        Config controllerConfig = ConfigFactory.empty();
        EnvironmentController controller = new EnvironmentController(registry, controllerConfig);
        controller.registerRoutes(app, "/visualizer/api/environment");
        
        // When: Make HTTP request with invalid region (odd number of values)
        Response resp = given()
            .port(port)
            .basePath("/visualizer/api/environment")
            .queryParam("region", "0,10,0")  // Only 3 values (invalid - must be even)
            .queryParam("runId", runId)
            .get("/1");
        
        // Then: Should return 400 with error message
        resp.then()
            .statusCode(400);
        
        // Check error message in response body (the actual error text from the spec)
        String body = resp.body().asString();
        assertThat(body).containsIgnoringCase("Region");
    }

    @Test
    void concurrentRequests_noConnectionLeaks() throws Exception {
        // Given: Set up test data with multiple ticks
        String runId = "test-run-" + UUID.randomUUID();
        SimulationMetadata metadata = createMetadata(runId, new int[]{10, 10}, false);
        
        indexMetadata(runId, metadata);
        
        // Create 10 ticks of data
        List<TickData> batches = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            batches.add(TickData.newBuilder()
                .setTickNumber(i + 1)
                .setSimulationRunId(runId)
                .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                    CellStateTestHelper.createCellStateBuilder(0, 100 + i, 1, 50 + i, 0).build()
                )))
                .build());
        }
        
        for (TickData batch : batches) {
            writeBatchAndNotify(runId, List.of(batch));
        }
        
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 2000
            insertBatchSize = 100
            flushTimeoutMs = 1000
            """.formatted(runId));
        
        indexer = createEnvironmentIndexer("test-indexer", config);
        indexer.start();
        
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("ticks_processed").longValue() >= 10);
        
        // Setup HTTP server
        app = Javalin.create().start(0);
        int port = app.port();
        
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IDatabaseReaderProvider.class, testDatabase);
        
        Config controllerConfig = ConfigFactory.empty();
        EnvironmentController controller = new EnvironmentController(registry, controllerConfig);
        controller.registerRoutes(app, "/visualizer/api/environment");
        
        // Get baseline connection count before HTTP requests
        Map<String, Number> baselineMetrics = testDatabase.getMetrics();
        int baselineActive = baselineMetrics.get("h2_pool_active_connections").intValue();
        
        // When: Make 20 concurrent requests
        List<CompletableFuture<Response>> futures = IntStream.range(1, 21)
            .mapToObj(i -> CompletableFuture.supplyAsync(() -> 
                given()
                    .port(port)
                    .basePath("/visualizer/api/environment")
                    .queryParam("region", "0,10,0,10")
                    .queryParam("runId", runId)
                    .get("/" + Math.min(i, 10))))  // Request ticks 1-10, repeat
            .toList();
        
        List<Response> responses = futures.stream()
            .map(CompletableFuture::join)
            .toList();
        
        // Then: All requests succeeded (200)
        assertTrue(responses.stream().allMatch(r -> r.statusCode() == 200),
            "All concurrent requests should return 200");
        
        // Verify no connection leaks by checking that connections don't increase
        // The try-with-resources in EnvironmentController should close connections properly
        // Use Awaitility to poll metrics until connections stabilize
        Map<String, Number> afterMetrics = await()
            .atMost(2, TimeUnit.SECONDS)
            .pollInterval(50, TimeUnit.MILLISECONDS)
            .until(() -> {
                Map<String, Number> metrics = testDatabase.getMetrics();
                // Metrics should be stable - wait a bit for cleanup
                return metrics;
            }, metrics -> {
                // Verify that connections are properly managed
                int afterTotal = metrics.get("h2_pool_total_connections").intValue();
                // The pool max size is 10, so we should not exceed this
                return afterTotal <= 10;
            });
        
        int afterActive = afterMetrics.get("h2_pool_active_connections").intValue();
        int afterTotal = afterMetrics.get("h2_pool_total_connections").intValue();
        
        // Verify that connections are properly managed
        // The pool max size is 10, so we should not exceed this
        assertTrue(afterTotal <= 10,
            "Total connections should not exceed pool max size (10). Was: " + afterTotal);
        
        // Verify that active connections did not increase (indicating no leaks)
        // We allow a small tolerance (+1) for timing/cleanup delays
        assertTrue(afterActive <= baselineActive + 1,
            "Active connections should not increase after requests. Baseline: " + baselineActive + ", After: " + afterActive);
    }

    private EnvironmentIndexer<?> createEnvironmentIndexer(String name, Config config) {
        return createEnvironmentIndexerWithTopic(name, config, testBatchTopic);
    }

    private EnvironmentIndexer<?> createEnvironmentIndexerWithTopic(String name, Config config, H2TopicResource<BatchInfo> topic) {
        // Wrap database for metadata reading
        ResourceContext dbMetaContext = new ResourceContext(
            name, "metadata", "db-meta-read", "test-db", Map.of());
        IResource wrappedDbMeta = testDatabase.getWrappedResource(dbMetaContext);

        // Wrap database for environment writing
        ResourceContext dbEnvContext = new ResourceContext(
            name, "database", "db-env-write", "test-db", Map.of());
        IResource wrappedDbEnv = testDatabase.getWrappedResource(dbEnvContext);

        // Wrap storage for batch reading
        ResourceContext storageContext = new ResourceContext(
            name, "storage", "storage-read", "test-storage", Map.of());
        IResource wrappedStorage = testStorage.getWrappedResource(storageContext);

        // Wrap topic for batch notifications
        ResourceContext topicContext = new ResourceContext(
            name, "topic", "topic-read", "batch-topic",
            Map.of("consumerGroup", "test-consumer-" + UUID.randomUUID()));
        IResource wrappedTopic = topic.getWrappedResource(topicContext);

        Map<String, List<IResource>> resources = Map.of(
            "database", List.of(wrappedDbEnv),
            "storage", List.of(wrappedStorage),
            "topic", List.of(wrappedTopic),
            "metadata", List.of(wrappedDbMeta)
        );

        return new EnvironmentIndexer<>(name, config, resources);
    }

    private SimulationMetadata createMetadata(String runId, int[] worldShape, boolean isToroidal) {
        // Build resolvedConfigJson using TestMetadataHelper
        TestMetadataHelper.Builder builder = TestMetadataHelper.builder()
            .toroidal(isToroidal)
            .samplingInterval(10);

        if (worldShape.length == 2) {
            builder.shape(worldShape[0], worldShape[1]);
        } else {
            // For non-2D, use width/height approximation
            builder.width(worldShape[0]).height(worldShape.length > 1 ? worldShape[1] : 1);
        }

        return SimulationMetadata.newBuilder()
            .setSimulationRunId(runId)
            .setResolvedConfigJson(builder.build())
            .setStartTimeMs(Instant.now().toEpochMilli())
            .setInitialSeed(42L)
            .build();
    }

    private void indexMetadata(String runId, SimulationMetadata metadata) throws Exception {
        // Write metadata to database
        ResourceContext metaWriteContext = new ResourceContext("test", "metadata-port", "db-meta-write", "test-db", Map.of());
        var metaWriter = testDatabase.getWrappedResource(metaWriteContext);
        var schemaAwareWriter = (org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareMetadataWriter) metaWriter;
        schemaAwareWriter.setSimulationRun(runId);
        schemaAwareWriter.insertMetadata(metadata);
        ((AutoCloseable) schemaAwareWriter).close();
    }

    private void writeBatchAndNotify(String runId, List<TickData> ticks) throws Exception {
        writeBatchAndNotifyWithTopic(runId, ticks, testBatchTopic);
    }

    private void writeBatchAndNotifyWithTopic(String runId, List<TickData> ticks, H2TopicResource<BatchInfo> topic) throws Exception {
        // Convert each tick to its own chunk (each chunk has tickCount=1)
        long firstTick = ticks.get(0).getTickNumber();
        long lastTick = ticks.get(ticks.size() - 1).getTickNumber();
        
        List<TickDataChunk> chunks = new ArrayList<>();
        for (TickData tick : ticks) {
            TickDataChunk chunk = TickDataChunk.newBuilder()
                .setSimulationRunId(runId)
                .setFirstTick(tick.getTickNumber())
                .setLastTick(tick.getTickNumber())
                .setTickCount(1)  // Each chunk contains exactly 1 tick
                .setSnapshot(tick)
                .build();
            chunks.add(chunk);
        }
        
        // Write chunk batch to storage
        ResourceContext storageWriteContext = new ResourceContext("test", "storage-port", "storage-write", "test-storage", Map.of("simulationRunId", runId));
        var storageWriter = testStorage.getWrappedResource(storageWriteContext);
        var batchWriter = (org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite) storageWriter;

        StoragePath batchPath = batchWriter.writeChunkBatch(chunks, firstTick, lastTick);

        // Send batch notification to topic (CRITICAL: Set simulation run BEFORE sending!)
        ResourceContext topicWriteContext = new ResourceContext("test", "topic-port", "topic-write", "batch-topic", Map.of());
        var topicWriterResource = topic.getWrappedResource(topicWriteContext);
        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> topicWriter = (ITopicWriter<BatchInfo>) topicWriterResource;
        
        // CRITICAL: setSimulationRun() creates schema and tables in H2TopicResource!
        topicWriter.setSimulationRun(runId);

        BatchInfo batchInfo = BatchInfo.newBuilder()
            .setSimulationRunId(runId)
            .setStoragePath(batchPath.asString())
            .setTickStart(firstTick)
            .setTickEnd(lastTick)
            .setWrittenAtMs(Instant.now().toEpochMilli())
            .build();
        topicWriter.send(batchInfo);
        topicWriter.close();
    }

    @Test
    void getTicks_returnsTickRange() throws Exception {
        // Given: Test run with multiple ticks
        String runId = "test-run-" + UUID.randomUUID();
        SimulationMetadata metadata = createMetadata(runId, new int[]{10, 10}, false);

        indexMetadata(runId, metadata);

        // Write ticks 10, 20, 30
        List<TickData> batches = List.of(
            TickData.newBuilder()
                .setTickNumber(10L)
                .setSimulationRunId(runId)
                .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                    CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build()
                )))
                .build(),
            TickData.newBuilder()
                .setTickNumber(20L)
                .setSimulationRunId(runId)
                .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                    CellStateTestHelper.createCellStateBuilder(0, 101, 1, 60, 0).build()
                )))
                .build(),
            TickData.newBuilder()
                .setTickNumber(30L)
                .setSimulationRunId(runId)
                .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                    CellStateTestHelper.createCellStateBuilder(0, 102, 1, 70, 0).build()
                )))
                .build()
        );

        for (TickData batch : batches) {
            writeBatchAndNotify(runId, List.of(batch));
        }

        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 2000
            insertBatchSize = 100
            flushTimeoutMs = 1000
            """.formatted(runId));

        indexer = createEnvironmentIndexer("test-indexer", config);
        indexer.start();

        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("ticks_processed").longValue() >= 3);

        // Setup HTTP server
        app = Javalin.create().start(0);
        int port = app.port();

        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IDatabaseReaderProvider.class, testDatabase);

        Config controllerConfig = ConfigFactory.empty();
        EnvironmentController controller = new EnvironmentController(registry, controllerConfig);
        controller.registerRoutes(app, "/visualizer/api/environment");

        // When: Request tick range
        io.restassured.response.Response resp = given()
            .port(port)
            .basePath("/visualizer/api/environment")
            .queryParam("runId", runId)
            .get("/ticks");

        // Then: Verify tick range response
        resp.then()
            .statusCode(200)
            .body("minTick", equalTo(10))
            .body("maxTick", equalTo(30));
    }

    @Test
    void getTicks_returns404WhenNoTicks() throws Exception {
        // Given: Test run with metadata but no ticks
        String runId = "test-run-" + UUID.randomUUID();
        SimulationMetadata metadata = createMetadata(runId, new int[]{10, 10}, false);

        indexMetadata(runId, metadata);

        // Setup HTTP server
        app = Javalin.create().start(0);
        int port = app.port();

        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IDatabaseReaderProvider.class, testDatabase);

        Config controllerConfig = ConfigFactory.empty();
        EnvironmentController controller = new EnvironmentController(registry, controllerConfig);
        controller.registerRoutes(app, "/visualizer/api/environment");

        // When: Request tick range (no ticks indexed)
        io.restassured.response.Response resp = given()
            .port(port)
            .basePath("/visualizer/api/environment")
            .queryParam("runId", runId)
            .get("/ticks");

        // Then: Should return 404
        resp.then()
            .statusCode(404);
    }
}
