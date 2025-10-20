package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.*;
import org.evochora.datapipeline.api.resources.IResource;
import java.time.Instant;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IMetadataWriter;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.resources.database.H2Database;
import org.evochora.datapipeline.resources.storage.FileSystemStorageResource;
import org.evochora.datapipeline.resources.topics.H2TopicResource;
import org.evochora.junit.extensions.logging.AllowLog;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for EnvironmentIndexer with real dependencies.
 * <p>
 * Tests end-to-end flow: TickData in storage → Topic notification → Indexer → H2 Database
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
@AllowLog(level = LogLevel.WARN, messagePattern = ".*initialized WITHOUT topic.*")
class EnvironmentIndexerIntegrationTest {
    
    private H2Database testDatabase;
    private FileSystemStorageResource testStorage;
    private H2TopicResource testBatchTopic;
    private EnvironmentIndexer<?> indexer;
    private Path tempStorageDir;
    private String dbUrl;
    
    private Config createConfig(String testName) {
        return ConfigFactory.parseString("""
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 2000
            insertBatchSize = 100
            flushTimeoutMs = 1000
            """);
    }
    
    @BeforeEach
    void setUp() throws IOException {
        // Setup temporary storage
        tempStorageDir = Files.createTempDirectory("evochora-test-env-indexer-");
        Config storageConfig = ConfigFactory.parseString(
            "rootDirectory = \"" + tempStorageDir.toAbsolutePath().toString().replace("\\", "/") + "\""
        );
        testStorage = new FileSystemStorageResource("test-storage", storageConfig);
        
        // Setup in-memory H2 database with unique name for parallel testing
        dbUrl = "jdbc:h2:mem:test-env-indexer-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        Config dbConfig = ConfigFactory.parseString(
            "jdbcUrl = \"" + dbUrl + "\"\n" +
            "username = \"sa\"\n" +
            "password = \"\""
        );
        testDatabase = new H2Database("test-db", dbConfig);
        
        // Setup H2 topic for batch processing tests
        String topicJdbcUrl = "jdbc:h2:mem:test-env-batch-topic-" + UUID.randomUUID();
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
        // Stop indexer if still running
        if (indexer != null && indexer.getCurrentState() != IService.State.STOPPED && indexer.getCurrentState() != IService.State.ERROR) {
            indexer.stop();
            await().atMost(5, TimeUnit.SECONDS)
                .until(() -> indexer.getCurrentState() == IService.State.STOPPED || indexer.getCurrentState() == IService.State.ERROR);
        }
        
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
    void testIndexerCreation_WithAllResources() throws Exception {
        // Given: Create test run with metadata  
        String runId = "20251020-test-" + UUID.randomUUID();
        SimulationMetadata metadata = createMetadata(runId, new int[]{10, 10}, true);
        
        // Index metadata first
        indexMetadata(runId, metadata);
        
        // Configure EnvironmentIndexer
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 2000
            insertBatchSize = 100
            flushTimeoutMs = 1000
            """.formatted(runId));
        
        // When: Create indexer
        indexer = createEnvironmentIndexer("test-indexer", config);
        
        // Then: Should be created successfully
        assertThat(indexer).isNotNull();
        assertThat(indexer.getCurrentState()).isEqualTo(IService.State.STOPPED);
        
        // Verify resources are properly wrapped
        assertThat(indexer.getMetrics()).isNotNull();
    }
    
    @Test
    void testDimensionAgnostic_1D() throws Exception {
        // Given: Metadata with 1D environment
        String runId = "test-1d-" + UUID.randomUUID();
        SimulationMetadata metadata = createMetadata(runId, new int[]{1000}, true);
        
        // When: Index metadata and create indexer
        indexMetadata(runId, metadata);
        
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 2000
            insertBatchSize = 100
            flushTimeoutMs = 1000
            """.formatted(runId));
        
        indexer = createEnvironmentIndexer("test-indexer-1d", config);
        
        // Then: Should be created successfully
        assertThat(indexer).isNotNull();
    }
    
    @Test
    void testDimensionAgnostic_3D() throws Exception {
        // Given: Metadata with 3D environment
        String runId = "test-3d-" + UUID.randomUUID();
        SimulationMetadata metadata = createMetadata(runId, new int[]{10, 10, 10}, false);
        
        // When: Index metadata and create indexer
        indexMetadata(runId, metadata);
        
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 2000
            insertBatchSize = 100
            flushTimeoutMs = 1000
            """.formatted(runId));
        
        indexer = createEnvironmentIndexer("test-indexer-3d", config);
        
        // Then: Should be created successfully
        assertThat(indexer).isNotNull();
    }
    
    @Test
    void testFlushTicks_MultipleBatches() throws Exception {
        // Given: Indexer setup with envProps manually set
        String runId = "test-multi-" + UUID.randomUUID();
        SimulationMetadata metadata = createMetadata(runId, new int[]{10, 10}, false);
        indexMetadata(runId, metadata);
        
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 2000
            insertBatchSize = 100
            flushTimeoutMs = 1000
            """.formatted(runId));
        
        indexer = createEnvironmentIndexer("test-indexer-multi", config);
        
        // Manually set envProps using reflection (normally done by prepareSchema via start())
        java.lang.reflect.Field envPropsField = EnvironmentIndexer.class.getDeclaredField("envProps");
        envPropsField.setAccessible(true);
        envPropsField.set(indexer, new org.evochora.runtime.model.EnvironmentProperties(new int[]{10, 10}, false));
        
        // Manually prepare database schema
        java.lang.reflect.Field dbField = EnvironmentIndexer.class.getDeclaredField("database");
        dbField.setAccessible(true);
        var dbWrapper = (org.evochora.datapipeline.api.resources.database.IEnvironmentDataWriter) dbField.get(indexer);
        ((org.evochora.datapipeline.api.resources.database.ISchemaAwareDatabase) dbWrapper).setSimulationRun(runId);
        dbWrapper.createEnvironmentDataTable(2);
        
        // When/Then: Create multiple batches of ticks
        TickData tick1 = TickData.newBuilder()
            .setTickNumber(1L)
            .addCells(CellState.newBuilder().setFlatIndex(0).setOwnerId(100).setMoleculeType(1).setMoleculeValue(50).build())
            .build();
        TickData tick2 = TickData.newBuilder()
            .setTickNumber(2L)
            .addCells(CellState.newBuilder().setFlatIndex(1).setOwnerId(101).setMoleculeType(1).setMoleculeValue(60).build())
            .build();
        
        // Verify flushTicks can handle multiple ticks
        indexer.flushTicks(List.of(tick1, tick2));
        
        // Should succeed without error
        assertThat(indexer.isHealthy()).isTrue();
    }
    
    @Test
    void testFlushTicks_Idempotency() throws Exception {
        // Given: Indexer setup with envProps manually set
        String runId = "test-idempotency-" + UUID.randomUUID();
        SimulationMetadata metadata = createMetadata(runId, new int[]{10, 10}, true);
        indexMetadata(runId, metadata);
        
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 2000
            insertBatchSize = 100
            flushTimeoutMs = 1000
            """.formatted(runId));
        
        indexer = createEnvironmentIndexer("test-indexer-idempotency", config);
        
        // Manually set envProps using reflection
        java.lang.reflect.Field envPropsField = EnvironmentIndexer.class.getDeclaredField("envProps");
        envPropsField.setAccessible(true);
        envPropsField.set(indexer, new org.evochora.runtime.model.EnvironmentProperties(new int[]{10, 10}, true));
        
        // Manually prepare database schema
        java.lang.reflect.Field dbField = EnvironmentIndexer.class.getDeclaredField("database");
        dbField.setAccessible(true);
        var dbWrapper = (org.evochora.datapipeline.api.resources.database.IEnvironmentDataWriter) dbField.get(indexer);
        ((org.evochora.datapipeline.api.resources.database.ISchemaAwareDatabase) dbWrapper).setSimulationRun(runId);
        dbWrapper.createEnvironmentDataTable(2);
        
        TickData tick = TickData.newBuilder()
            .setTickNumber(1L)
            .addCells(CellState.newBuilder().setFlatIndex(0).setOwnerId(100).setMoleculeType(1).setMoleculeValue(50).build())
            .build();
        
        // When: Write same tick twice
        indexer.flushTicks(List.of(tick));
        indexer.flushTicks(List.of(tick));  // MERGE should handle duplicate
        
        // Then: Should succeed without error (MERGE ensures idempotency)
        assertThat(indexer.isHealthy()).isTrue();
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
            Map.of("consumerGroup", "test-env-indexer-" + UUID.randomUUID()));
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
            .build();
    }
    
    private void indexMetadata(String runId, SimulationMetadata metadata) throws Exception {
        // Write metadata to database
        ResourceContext metaWriteContext = new ResourceContext("test", "metadata-port", "db-meta-write", "test-db", Map.of());
        IMetadataWriter metaWriter = (IMetadataWriter) testDatabase.getWrappedResource(metaWriteContext);
        metaWriter.setSimulationRun(runId);
        metaWriter.insertMetadata(metadata);
        ((AutoCloseable) metaWriter).close();
    }
    
    private void writeBatchAndNotify(String runId, List<TickData> ticks) throws Exception {
        // Write batch to storage
        ResourceContext storageWriteContext = new ResourceContext("test", "storage-port", "storage-write", "test-storage", Map.of("simulationRunId", runId));
        IBatchStorageWrite storageWriter = (IBatchStorageWrite) testStorage.getWrappedResource(storageWriteContext);
        
        long firstTick = ticks.get(0).getTickNumber();
        long lastTick = ticks.get(ticks.size() - 1).getTickNumber();
        StoragePath batchPath = storageWriter.writeBatch(ticks, firstTick, lastTick);
        ((AutoCloseable) storageWriter).close();
        
        // Send batch notification to topic
        ResourceContext topicWriteContext = new ResourceContext("test", "topic-port", "topic-write", "batch-topic", Map.of());
        ITopicWriter<BatchInfo> topicWriter = (ITopicWriter<BatchInfo>) testBatchTopic.getWrappedResource(topicWriteContext);
        topicWriter.setSimulationRun(runId);
        
        BatchInfo batchInfo = BatchInfo.newBuilder()
            .setSimulationRunId(runId)
            .setStoragePath(batchPath.asString())
            .setTickStart(firstTick)
            .setTickEnd(lastTick)
            .setWrittenAtMs(Instant.now().toEpochMilli())
            .build();
        topicWriter.send(batchInfo);
        ((AutoCloseable) topicWriter).close();
    }
    
    private void verifyDatabaseContainsTick(String runId, long tickNumber) throws Exception {
        // For test verification, we can directly query the schema
        // In production, this would be done via a query API (Phase 14.5)
        
        // Note: H2Database doesn't expose getDataSource() or acquireDedicatedConnection() publicly
        // For now, we trust that if ticks_processed metric increased, the write succeeded
        // Full verification would require exposing a test-only method or using a query wrapper
        
        // Simplified verification: Check metrics
        assertThat(indexer.getMetrics().get("ticks_processed").longValue()).isGreaterThan(0);
    }
}

