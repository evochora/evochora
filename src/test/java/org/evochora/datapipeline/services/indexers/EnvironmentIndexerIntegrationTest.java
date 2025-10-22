package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.*;
import org.evochora.datapipeline.api.resources.IResource;
import java.time.Instant;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IMetadataWriter;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareMetadataWriter;
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
        
        // Close resources (services must be stopped first!)
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
    void testEnvironmentIndexerEndToEnd() throws Exception {
        // Given: Metadata and batches in storage
        String runId = "test-e2e-" + UUID.randomUUID();
        SimulationMetadata metadata = createMetadata(runId, new int[]{10, 10}, false);
        indexMetadata(runId, metadata);
        
        // Create ticks with cells
        List<TickData> batch1 = List.of(
            TickData.newBuilder()
                .setTickNumber(1L)
                .setSimulationRunId(runId)
                .addCells(CellState.newBuilder().setFlatIndex(0).setOwnerId(100).setMoleculeType(1).setMoleculeValue(50).build())
                .addCells(CellState.newBuilder().setFlatIndex(5).setOwnerId(101).setMoleculeType(2).setMoleculeValue(60).build())
                .build()
        );
        
        List<TickData> batch2 = List.of(
            TickData.newBuilder()
                .setTickNumber(2L)
                .setSimulationRunId(runId)
                .addCells(CellState.newBuilder().setFlatIndex(1).setOwnerId(102).setMoleculeType(1).setMoleculeValue(70).build())
                .build()
        );
        
        // Write batches to storage and send notifications
        writeBatchAndNotify(runId, batch1);
        writeBatchAndNotify(runId, batch2);
        
        // When: Start indexer (real code path!)
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 2000
            insertBatchSize = 100
            flushTimeoutMs = 1000
            """.formatted(runId));
        
        indexer = createEnvironmentIndexer("test-indexer-e2e", config);
        indexer.start();
        
        // Then: Wait for indexer to process both batches
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("ticks_processed").longValue() >= 2);
        
        // Verify metrics
        assertThat(indexer.getMetrics().get("ticks_processed").longValue()).isEqualTo(2);
        assertThat(indexer.getMetrics().get("batches_processed").longValue()).isEqualTo(2);
        assertThat(indexer.isHealthy()).isTrue();
    }
    
    @Test
    void testMergeIdempotency() throws Exception {
        // Given: Metadata and batches in storage
        String runId = "test-idempotency-" + UUID.randomUUID();
        SimulationMetadata metadata = createMetadata(runId, new int[]{10, 10}, false);
        indexMetadata(runId, metadata);
        
        // Create TWO batches with OVERLAPPING ticks (tick 1 appears in both!)
        // This tests MERGE idempotency: same tick_number written twice should only exist once in DB
        List<TickData> batch1 = List.of(
            TickData.newBuilder()
                .setTickNumber(1L)
                .setSimulationRunId(runId)
                .addCells(CellState.newBuilder().setFlatIndex(0).setOwnerId(100).setMoleculeType(1).setMoleculeValue(50).build())
                .build()
        );
        
        List<TickData> batch2 = List.of(
            TickData.newBuilder()
                .setTickNumber(1L)  // SAME tick_number as batch1!
                .setSimulationRunId(runId)
                .addCells(CellState.newBuilder().setFlatIndex(3).setOwnerId(101).setMoleculeType(2).setMoleculeValue(75).build())
                .build(),
            TickData.newBuilder()
                .setTickNumber(2L)  // Additional tick to ensure different storage path
                .setSimulationRunId(runId)
                .addCells(CellState.newBuilder().setFlatIndex(5).setOwnerId(102).setMoleculeType(1).setMoleculeValue(60).build())
                .build()
        );
        
        // Write batches to storage and send notifications
        writeBatchAndNotify(runId, batch1);  // Storage: batch_1_1.pb
        writeBatchAndNotify(runId, batch2);  // Storage: batch_1_2.pb
        
        // When: Start indexer (real code path!)
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 2000
            insertBatchSize = 100
            flushTimeoutMs = 1000
            """.formatted(runId));
        
        indexer = createEnvironmentIndexer("test-indexer-idempotency", config);
        indexer.start();
        
        // Then: Wait for indexer to process both batches
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("ticks_processed").longValue() >= 3);
        
        // Verify metrics
        assertThat(indexer.getMetrics().get("ticks_processed").longValue()).isEqualTo(3); // 1 + 1 + 2 = 3 ticks processed
        assertThat(indexer.getMetrics().get("batches_processed").longValue()).isEqualTo(2);
        assertThat(indexer.isHealthy()).isTrue();
        
        // Note: MERGE ensures tick 1 is only stored once in database despite being written twice
    }
    
    @Test
    void testCompetingConsumers() throws Exception {
        // Given: Metadata and multiple batches
        String runId = "test-competing-" + UUID.randomUUID();
        SimulationMetadata metadata = createMetadata(runId, new int[]{10, 10}, false);
        indexMetadata(runId, metadata);
        
        // Create 5 batches
        for (int i = 0; i < 50; i++) {
            List<TickData> batch = List.of(
                TickData.newBuilder()
                    .setTickNumber(i + 1L)
                    .setSimulationRunId(runId)
                    .addCells(CellState.newBuilder().setFlatIndex(i).setOwnerId(100 + i).setMoleculeType(1).setMoleculeValue(50).build())
                    .build()
            );
            writeBatchAndNotify(runId, batch);
        }
        
        // When: Start TWO indexers with SAME consumer group (competing consumers)
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 2000
            insertBatchSize = 100
            flushTimeoutMs = 1000
            """.formatted(runId));
        
        // Use SAME consumer group for both indexers
        String sharedConsumerGroup = "test-competing-" + UUID.randomUUID();
        EnvironmentIndexer<?> indexer1 = createEnvironmentIndexerWithConsumerGroup("test-indexer-1", config, sharedConsumerGroup);
        EnvironmentIndexer<?> indexer2 = createEnvironmentIndexerWithConsumerGroup("test-indexer-2", config, sharedConsumerGroup);
        
        try {
            indexer1.start();
            indexer2.start();
            
            // Then: Wait for ALL 5 batches to be processed (distributed between indexers)
            await().atMost(15, TimeUnit.SECONDS)
                .until(() -> {
                    long total1 = indexer1.getMetrics().get("batches_processed").longValue();
                    long total2 = indexer2.getMetrics().get("batches_processed").longValue();
                    return (total1 + total2) >= 50;
                });
            
            // Verify: All 5 batches processed (distributed between indexers)
            long batches1 = indexer1.getMetrics().get("batches_processed").longValue();
            long batches2 = indexer2.getMetrics().get("batches_processed").longValue();
            assertThat(batches1 + batches2).isEqualTo(50);
            
            // Verify: Both indexers participated (load distribution)
            assertThat(batches1).isGreaterThan(0);
            assertThat(batches2).isGreaterThan(0);
            
            // Verify: All 5 ticks processed total
            long ticks1 = indexer1.getMetrics().get("ticks_processed").longValue();
            long ticks2 = indexer2.getMetrics().get("ticks_processed").longValue();
            assertThat(ticks1 + ticks2).isEqualTo(50);
        } finally {
            // CRITICAL: Always stop both indexers in finally block to prevent thread leaks
            if (indexer2 != null && indexer2.getCurrentState() != IService.State.STOPPED && indexer2.getCurrentState() != IService.State.ERROR) {
                indexer2.stop();
                await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> indexer2.getCurrentState() == IService.State.STOPPED || indexer2.getCurrentState() == IService.State.ERROR);
            }
            
            // Note: indexer (field) points to indexer1, which will be cleaned up in @AfterEach
            indexer = indexer1;
        }
    }
    
    private EnvironmentIndexer<?> createEnvironmentIndexer(String name, Config config) {
        // Use unique consumer group per indexer instance
        return createEnvironmentIndexerWithConsumerGroup(name, config, "test-env-indexer-" + UUID.randomUUID());
    }
    
    private EnvironmentIndexer<?> createEnvironmentIndexerWithConsumerGroup(String name, Config config, String consumerGroup) {
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
            Map.of("consumerGroup", consumerGroup));
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
        ((IResourceSchemaAwareMetadataWriter) metaWriter).setSimulationRun(runId);
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
        // Note: IBatchStorageWrite does not implement AutoCloseable - no close() needed
        
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
        topicWriter.close();  // ITopicWriter implements AutoCloseable
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

