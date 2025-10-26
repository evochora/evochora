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
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.resources.database.H2Database;
import org.evochora.datapipeline.resources.storage.FileSystemStorageResource;
import org.evochora.datapipeline.resources.topics.H2TopicResource;
import org.evochora.datapipeline.services.indexers.EnvironmentIndexer;
import org.evochora.node.spi.ServiceRegistry;
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
    private H2TopicResource testBatchTopic;
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
                .addCells(CellState.newBuilder().setFlatIndex(0).setOwnerId(100).setMoleculeType(1).setMoleculeValue(50).build())  // (0,0)
                .addCells(CellState.newBuilder().setFlatIndex(5).setOwnerId(101).setMoleculeType(2).setMoleculeValue(60).build())  // (5,0)
                .addCells(CellState.newBuilder().setFlatIndex(15).setOwnerId(102).setMoleculeType(1).setMoleculeValue(70).build()) // (5,1)
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

        // Create and register EnvironmentController
        Config controllerConfig = ConfigFactory.empty();
        EnvironmentController controller = new EnvironmentController(registry, controllerConfig);
        controller.registerRoutes(app, "/visualizer/api");

        // Query data via HTTP API using RestAssured
        given()
            .port(port)
            .basePath("/visualizer/api")
        .when()
            .get("/{tick}/environment?runId={runId}&region=0,10,0,10", 1, runId)
        .then()
            .statusCode(200)
            .body("tick", equalTo(1))
            .body("runId", equalTo(runId))
            .body("cells", hasSize(3))  // Should have 3 cells
            .body("cells[0].coordinates", equalTo(Arrays.asList(0, 0)))  // Cell at flatIndex=0 → (0,0)
            .body("cells[0].ownerId", equalTo(100))
            .body("cells[1].coordinates", equalTo(Arrays.asList(0, 5)))  // Cell at flatIndex=5 → (0,5)
            .body("cells[2].coordinates", equalTo(Arrays.asList(1, 5))); // Cell at flatIndex=15 → (1,5) in row-major
    }

    private EnvironmentIndexer<?> createEnvironmentIndexer(String name, Config config) {
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
        IResource wrappedTopic = testBatchTopic.getWrappedResource(topicContext);

        Map<String, List<IResource>> resources = Map.of(
            "database", List.of(wrappedDbEnv),
            "storage", List.of(wrappedStorage),
            "topic", List.of(wrappedTopic),
            "metadata", List.of(wrappedDbMeta)
        );

        return new EnvironmentIndexer<>(name, config, resources);
    }

    private SimulationMetadata createMetadata(String runId, int[] worldShape, boolean isToroidal) {
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
            .setSamplingInterval(10)
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

        // Send batch notification to topic (CRITICAL: Set simulation run BEFORE sending!)
        ResourceContext topicWriteContext = new ResourceContext("test", "topic-port", "topic-write", "batch-topic", Map.of());
        var topicWriterResource = testBatchTopic.getWrappedResource(topicWriteContext);
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
}
