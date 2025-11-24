package org.evochora.node.processes.http.api.visualizer;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.javalin.Javalin;
import org.evochora.datapipeline.api.contracts.*;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.resources.database.H2Database;
import org.evochora.datapipeline.resources.storage.FileSystemStorageResource;
import org.evochora.datapipeline.resources.topics.H2TopicResource;
import org.evochora.datapipeline.services.indexers.EnvironmentIndexer;
import org.evochora.node.spi.ServiceRegistry;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.evochora.test.utils.FileUtils.findBatchFiles;
import static org.evochora.test.utils.FileUtils.countBatchFiles;

/**
 * Integration test for SimulationController.
 * <p>
 * Tests end-to-end flow:
 * <ul>
 *   <li>Write test data to storage</li>
 *   <li>EnvironmentIndexer indexes data into H2 database</li>
 *   <li>SimulationController reads metadata and tick range via HTTP API</li>
 * </ul>
 * <p>
 * Uses real H2Database, real FileSystemStorageResource, real H2TopicResource, and real SimulationController.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class SimulationControllerIntegrationTest {

    private H2Database testDatabase;
    private FileSystemStorageResource testStorage;
    private H2TopicResource testBatchTopic;
    private EnvironmentIndexer<?> indexer;
    private Javalin app;
    private Path tempStorageDir;
    private String dbUrl;

    @BeforeEach
    void setUp() throws IOException {
        // Setup temporary storage for file operations
        tempStorageDir = Files.createTempDirectory("evochora-test-sim-controller-");
        Config storageConfig = ConfigFactory.parseString(
            "rootDirectory = \"" + tempStorageDir.toAbsolutePath().toString().replace("\\", "/") + "\""
        );
        testStorage = new FileSystemStorageResource("test-storage", storageConfig);

        // Setup in-memory H2 database with unique name for parallel testing
        dbUrl = "jdbc:h2:mem:test-sim-controller-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        Config dbConfig = ConfigFactory.parseString(
            "jdbcUrl = \"" + dbUrl + "\"\n" +
            "username = \"sa\"\n" +
            "password = \"\"\n" +
            "maxPoolSize = 10\n" +
            "h2EnvironmentStrategy {\n" +
            "  className = \"org.evochora.datapipeline.resources.database.h2.SingleBlobStrategy\"\n" +
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
        testBatchTopic = new H2TopicResource("batch-topic", topicConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Stop HTTP server
        if (app != null) {
            app.stop();
        }

        // Stop indexer if still running
        if (indexer != null && indexer.getCurrentState() != org.evochora.datapipeline.api.services.IService.State.STOPPED && 
            indexer.getCurrentState() != org.evochora.datapipeline.api.services.IService.State.ERROR) {
            indexer.stop();
            await().atMost(5, TimeUnit.SECONDS)
                .until(() -> indexer.getCurrentState() == org.evochora.datapipeline.api.services.IService.State.STOPPED || 
                             indexer.getCurrentState() == org.evochora.datapipeline.api.services.IService.State.ERROR);
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
    void getMetadata_returnsSimulationMetadata() throws Exception {
        // Given: Test run with metadata
        String runId = "test-run-" + UUID.randomUUID();
        SimulationMetadata metadata = createMetadata(runId, new int[]{10, 10}, false, 5);

        // Index metadata first
        indexMetadata(runId, metadata);

        // Setup test data: Write some ticks
        List<TickData> batch1 = List.of(
            TickData.newBuilder()
                .setTickNumber(5L)
                .setSimulationRunId(runId)
                .addCells(CellState.newBuilder().setFlatIndex(0).setOwnerId(100).setMoleculeType(1).setMoleculeValue(50).build())
                .build()
        );

        writeBatchAndNotify(runId, batch1);

        // Start indexer
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

        // Configure cache for testing (enabled with ETag)
        Config controllerConfig = ConfigFactory.parseString("""
            cache {
              metadata {
                enabled = true
                maxAge = 31536000
                useETag = true
              }
            }
            """);
        SimulationController controller = new SimulationController(registry, controllerConfig);
        controller.registerRoutes(app, "/visualizer/api/simulation");

        // When: Request metadata
        io.restassured.response.Response resp = given()
            .port(port)
            .basePath("/visualizer/api/simulation")
            .queryParam("runId", runId)
            .get("/metadata");

        // Then: Verify metadata response
        resp.then()
            .statusCode(200)
            .body("simulationRunId", equalTo(runId))
            .body("environment.shape", hasSize(2))
            .body("environment.shape[0]", equalTo(10))  // int32 from Protobuf
            .body("environment.shape[1]", equalTo(10))  // int32 from Protobuf
            .body("samplingInterval", equalTo(5))
            .header("Cache-Control", equalTo("public, max-age=31536000, must-revalidate"))
            .header("ETag", notNullValue());

        // ETag should contain runId (not _metadata suffix, as per new implementation)
        assertThat(resp.header("ETag")).contains(runId);
    }

    @Test
    void getTicks_returnsTickRange() throws Exception {
        // Given: Test run with multiple ticks
        String runId = "test-run-" + UUID.randomUUID();
        SimulationMetadata metadata = createMetadata(runId, new int[]{10, 10}, false, 10);

        indexMetadata(runId, metadata);

        // Write ticks 10, 20, 30
        List<TickData> batches = List.of(
            TickData.newBuilder()
                .setTickNumber(10L)
                .setSimulationRunId(runId)
                .addCells(CellState.newBuilder().setFlatIndex(0).setOwnerId(100).setMoleculeType(1).setMoleculeValue(50).build())
                .build(),
            TickData.newBuilder()
                .setTickNumber(20L)
                .setSimulationRunId(runId)
                .addCells(CellState.newBuilder().setFlatIndex(0).setOwnerId(101).setMoleculeType(1).setMoleculeValue(60).build())
                .build(),
            TickData.newBuilder()
                .setTickNumber(30L)
                .setSimulationRunId(runId)
                .addCells(CellState.newBuilder().setFlatIndex(0).setOwnerId(102).setMoleculeType(1).setMoleculeValue(70).build())
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
        SimulationController controller = new SimulationController(registry, controllerConfig);
        controller.registerRoutes(app, "/visualizer/api/simulation");

        // When: Request tick range
        io.restassured.response.Response resp = given()
            .port(port)
            .basePath("/visualizer/api/simulation")
            .queryParam("runId", runId)
            .get("/ticks");

        // Then: Verify tick range response
        resp.then()
            .statusCode(200)
            .body("minTick", equalTo(10))
            .body("maxTick", equalTo(30));
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, messagePattern = ".*No ticks available.*")
    void getTicks_returns404WhenNoTicks() throws Exception {
        // Given: Test run with metadata but no ticks
        String runId = "test-run-" + UUID.randomUUID();
        SimulationMetadata metadata = createMetadata(runId, new int[]{10, 10}, false, 10);

        indexMetadata(runId, metadata);

        // Setup HTTP server
        app = Javalin.create().start(0);
        int port = app.port();

        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IDatabaseReaderProvider.class, testDatabase);

        Config controllerConfig = ConfigFactory.empty();
        SimulationController controller = new SimulationController(registry, controllerConfig);
        controller.registerRoutes(app, "/visualizer/api/simulation");

        // When: Request tick range (no ticks indexed)
        io.restassured.response.Response resp = given()
            .port(port)
            .basePath("/visualizer/api/simulation")
            .queryParam("runId", runId)
            .get("/ticks");

        // Then: Should return 404
        resp.then()
            .statusCode(404);
    }

    @Test
    void getMetadata_runIdFallback_usesLatest() throws Exception {
        // Given: Two runs (older and newer)
        String baseUuid = UUID.randomUUID().toString();
        String oldRunId = "A-older-" + baseUuid;
        String newRunId = "B-newer-" + baseUuid;

        // Setup older run
        SimulationMetadata oldMetadata = createMetadata(oldRunId, new int[]{10, 10}, false, 5);
        indexMetadata(oldRunId, oldMetadata);

        // Setup newer run
        SimulationMetadata newMetadata = createMetadata(newRunId, new int[]{20, 20}, false, 10);
        indexMetadata(newRunId, newMetadata);

        // Setup HTTP server
        app = Javalin.create().start(0);
        int port = app.port();

        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IDatabaseReaderProvider.class, testDatabase);

        Config controllerConfig = ConfigFactory.empty();
        SimulationController controller = new SimulationController(registry, controllerConfig);
        controller.registerRoutes(app, "/visualizer/api/simulation");

        // When: Request metadata WITHOUT runId parameter (should fallback to latest)
        io.restassured.response.Response resp = given()
            .port(port)
            .basePath("/visualizer/api/simulation")
            // Note: NO runId parameter!
            .get("/metadata");

        // Then: Should return metadata from the NEWER run (latest)
        resp.then()
            .statusCode(200)
            .body("simulationRunId", equalTo(newRunId))
            .body("environment.shape[0]", equalTo(20))  // int32 from Protobuf, newer run has 20x20
            .body("samplingInterval", equalTo(10));  // Newer run has samplingInterval=10
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, messagePattern = ".*No run ID available.*")
    void getMetadata_returns404OnNoRuns() {
        // Given: Empty database (no runs)
        // Setup HTTP server
        app = Javalin.create().start(0);
        int port = app.port();

        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IDatabaseReaderProvider.class, testDatabase);

        Config controllerConfig = ConfigFactory.empty();
        SimulationController controller = new SimulationController(registry, controllerConfig);
        controller.registerRoutes(app, "/visualizer/api/simulation");

        // When: Request metadata without runId parameter
        io.restassured.response.Response resp = given()
            .port(port)
            .basePath("/visualizer/api/simulation")
            // Note: NO runId parameter - should try to find latest run
            .get("/metadata");

        // Then: Should return 404
        resp.then()
            .statusCode(404);
    }

    private EnvironmentIndexer<?> createEnvironmentIndexer(String name, Config config) {
        return createEnvironmentIndexerWithTopic(name, config, testBatchTopic);
    }

    private EnvironmentIndexer<?> createEnvironmentIndexerWithTopic(String name, Config config, H2TopicResource topic) {
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

    private SimulationMetadata createMetadata(String runId, int[] worldShape, boolean isToroidal, int samplingInterval) {
        EnvironmentConfig.Builder envBuilder = EnvironmentConfig.newBuilder()
            .setDimensions(worldShape.length);

        for (int size : worldShape) {
            envBuilder.addShape(size);
            envBuilder.addToroidal(isToroidal);
        }

        return SimulationMetadata.newBuilder()
            .setSimulationRunId(runId)
            .setEnvironment(envBuilder.build())
            .setStartTimeMs(Instant.now().toEpochMilli())
            .setInitialSeed(42L)
            .setSamplingInterval(samplingInterval)
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
        // Write batch to storage
        ResourceContext storageWriteContext = new ResourceContext("test", "storage-port", "storage-write", "test-storage", Map.of("simulationRunId", runId));
        var storageWriter = testStorage.getWrappedResource(storageWriteContext);
        var batchWriter = (org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite) storageWriter;

        long firstTick = ticks.get(0).getTickNumber();
        long lastTick = ticks.get(ticks.size() - 1).getTickNumber();
        StoragePath batchPath = batchWriter.writeBatch(ticks, firstTick, lastTick);

        // Send batch notification to topic
        ResourceContext topicWriteContext = new ResourceContext("test", "topic-port", "topic-write", "batch-topic", Map.of());
        var topicWriterResource = testBatchTopic.getWrappedResource(topicWriteContext);
        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> topicWriter = (ITopicWriter<BatchInfo>) topicWriterResource;
        
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

    private long countFilesInDirectory(Path directory) {
        return countBatchFiles(directory);
    }
}

