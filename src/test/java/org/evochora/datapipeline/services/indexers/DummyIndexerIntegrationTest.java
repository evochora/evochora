package org.evochora.datapipeline.services.indexers;

import static org.awaitility.Awaitility.await;
import static org.evochora.junit.extensions.logging.LogLevel.ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareMetadataWriter;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.topics.IResourceTopicReader;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.resources.database.H2Database;
import org.evochora.datapipeline.resources.storage.FileSystemStorageResource;
import org.evochora.datapipeline.resources.topics.H2TopicResource;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.ExpectLogs;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Integration tests for DummyIndexer Phase 2.5.1 (Metadata Reading).
 * <p>
 * Tests the complete metadata reading flow with real database and storage.
 */
@Tag("integration")
@ExtendWith(MockitoExtension.class)
@ExtendWith(LogWatchExtension.class)
@AllowLog(level = LogLevel.WARN, messagePattern = ".*initialized WITHOUT topic.*")
class DummyIndexerIntegrationTest {
    
    @Mock
    private IResourceTopicReader<BatchInfo, Object> mockTopic;
    
    private H2Database testDatabase;
    private FileSystemStorageResource testStorage;
    private H2TopicResource<BatchInfo> testBatchTopic;  // Real topic for batch processing tests
    private DummyIndexer<?> indexer;  // Track for cleanup
    private Path tempStorageDir;
    private String dbUrl;
    private String dbUsername;
    private String dbPassword;
    
    @BeforeEach
    void setup() throws IOException {
        // Setup temporary storage
        tempStorageDir = Files.createTempDirectory("evochora-test-dummy-v1-");
        Config storageConfig = ConfigFactory.parseString(
            "rootDirectory = \"" + tempStorageDir.toAbsolutePath().toString().replace("\\", "/") + "\""
        );
        testStorage = new FileSystemStorageResource("test-storage", storageConfig);
        
        // Setup in-memory H2 database with unique name for parallel testing
        dbUrl = "jdbc:h2:mem:test-dummy-v1-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        dbUsername = "test-user";
        dbPassword = "test-password";
        Config dbConfig = ConfigFactory.parseString(
            "jdbcUrl = \"" + dbUrl + "\"\n" +
            "username = \"" + dbUsername + "\"\n" +
            "password = \"" + dbPassword + "\""
        );
        testDatabase = new H2Database("test-db", dbConfig);
        
        // Setup H2 topic for batch processing tests
        String topicJdbcUrl = "jdbc:h2:mem:test-batch-topic-" + UUID.randomUUID();
        Config topicConfig = ConfigFactory.parseString(
            "jdbcUrl = \"" + topicJdbcUrl + "\"\n" +
            "username = \"sa\"\n" +
            "password = \"\"\n" +
            "claimTimeout = 300"
        );
        testBatchTopic = new H2TopicResource<>("batch-topic", topicConfig);
        
        // Configure mock topic to return null (no batch messages) - keeps indexer running
        // Use lenient() because not all tests reach the topic polling stage
        try {
            lenient().when(mockTopic.poll(anyLong(), any(TimeUnit.class))).thenReturn(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
    
    @AfterEach
    void cleanup() throws Exception {
        // Stop indexer if still running (DummyIndexer runs continuously)
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
    void testMetadataReading_Success() throws Exception {
        // Given: Create test run with metadata
        String runId = "20251011-120000-" + UUID.randomUUID();
        SimulationMetadata metadata = createTestMetadata(runId, 10);
        
        // First index metadata using MetadataIndexer
        indexMetadata(runId, metadata);
        
        // Configure DummyIndexer in post-mortem mode
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            """.formatted(runId));
        
        indexer = createDummyIndexer("test-indexer", config);
        
        // When: Start indexer
        indexer.start();
        
        // Then: Should transition to RUNNING (continuous batch processing mode)
        await().atMost(5, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);
        
        // Verify indexer is running (no batches to process, waiting for topic messages)
        assertEquals(IService.State.RUNNING, indexer.getCurrentState(),
            "Indexer should be RUNNING and waiting for batch notifications");
        
        // Verify no batches processed yet (no messages in topic)
        Map<String, Number> metrics = indexer.getMetrics();
        assertEquals(0, metrics.get("batches_processed").intValue(),
            "No batches should be processed yet");
    }
    
    @Test
    void testMetadataReading_PollingBehavior() throws Exception {
        // Given: Create run ID with metadata in storage but NOT in database yet
        // This simulates MetadataPersistenceService having written the file but
        // MetadataIndexer not having indexed it to the DB yet
        String runId = "test-run-" + UUID.randomUUID();
        SimulationMetadata metadata = createTestMetadata(runId, 10);

        // Write metadata file to storage (so run validation passes)
        String storageKey = runId + "/raw/metadata.pb";
        testStorage.writeMessage(storageKey, metadata);

        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            """.formatted(runId));

        indexer = createDummyIndexer("test-indexer", config);

        // When: Start indexer (metadata in storage but not in DB)
        indexer.start();

        // Wait until indexer is running (polling for metadata in DB)
        await().atMost(2, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);

        // Now index metadata to database
        indexMetadata(runId, metadata);

        // Then: Should continue RUNNING now that metadata is available in DB
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);

        assertEquals(IService.State.RUNNING, indexer.getCurrentState());
        assertEquals(0, indexer.getMetrics().get("batches_processed").intValue());
    }
    
    @Test
    @ExpectLogs({
        @ExpectLog(level = ERROR,
                   loggerPattern = ".*DummyIndexer.*",
                   messagePattern = "Failed to discover run:.*"),
        @ExpectLog(level = ERROR,
                   loggerPattern = ".*DummyIndexer.*",
                   messagePattern = ".*DummyIndexer.* stopped with ERROR due to RuntimeException")
    })
    void testMetadataReading_Timeout() throws Exception {
        // Given: Create run ID with no data in storage (simulates wrong runId)
        String runId = "test-run-" + UUID.randomUUID();

        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 1000
            """.formatted(runId));

        indexer = createDummyIndexer("test-indexer", config);

        // When: Start indexer (run doesn't exist in storage)
        indexer.start();

        // Then: Should fail fast and enter ERROR state (no 30s timeout)
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.ERROR);

        assertEquals(IService.State.ERROR, indexer.getCurrentState(),
            "Service should enter ERROR state when configured runId not found in storage");

        // Verify no batches processed
        assertEquals(0, indexer.getMetrics().get("batches_processed").intValue());
    }
    
    @Test
    void testMetadataReading_ParallelMode() throws Exception {
        // Given: Setup DummyIndexer in parallel mode (no runId specified)
        Config config = ConfigFactory.parseString("""
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            """);
        
        indexer = createDummyIndexer("test-indexer", config);
        indexer.start();
        
        // Wait for indexer to be running
        await().atMost(2, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);
        
        // Generate runId with timestamp in the future (avoid race condition)
        Instant runInstant = Instant.now().plusSeconds(1);
        String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSS")
            .withZone(java.time.ZoneId.systemDefault())
            .format(runInstant);
        String runId = timestamp + "-" + UUID.randomUUID();
        
        // Create metadata
        SimulationMetadata metadata = createTestMetadata(runId, 10);
        
        // Write metadata file to storage (simulates new run discovery)
        Files.createDirectories(tempStorageDir.resolve(runId + "/raw"));
        testStorage.writeMessage(runId + "/raw/metadata.pb", metadata);
        
        // Index metadata
        indexMetadata(runId, metadata);
        
        // Then: Should discover and read metadata, then keep running
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);
        
        // Verify still running (no batches to process)
        assertEquals(IService.State.RUNNING, indexer.getCurrentState());
        assertEquals(0, indexer.getMetrics().get("batches_processed").intValue());
    }
    
    // ========== Batch Processing Tests ==========
    
    @Test
    void testBatchProcessing_MultipleBatches() throws Exception {
        // Given: Create test run with metadata
        String runId = "20251018-120000-" + UUID.randomUUID();
        SimulationMetadata metadata = createTestMetadata(runId, 10);
        
        // Index metadata first
        indexMetadata(runId, metadata);
        
        // Write 3 chunks to storage (10 ticks each)
        List<TickDataChunk> chunks1 = createTestChunks(runId, 0, 10);
        List<TickDataChunk> chunks2 = createTestChunks(runId, 10, 10);
        List<TickDataChunk> chunks3 = createTestChunks(runId, 20, 10);
        
        StoragePath key1 = testStorage.writeChunkBatchStreaming(chunks1.iterator()).path();
        StoragePath key2 = testStorage.writeChunkBatchStreaming(chunks2.iterator()).path();
        StoragePath key3 = testStorage.writeChunkBatchStreaming(chunks3.iterator()).path();
        
        // Create indexer with real topic (not mock!)
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 1000
            """.formatted(runId));
        
        indexer = createDummyIndexerWithRealTopic("test-indexer", config);
        
        // When: Start indexer
        indexer.start();
        
        // Wait for metadata to be loaded
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);
        
        // Send 3 BatchInfo messages to topic
        sendBatchInfoToTopic(runId, key1.asString(), 0, 9);
        sendBatchInfoToTopic(runId, key2.asString(), 10, 19);
        sendBatchInfoToTopic(runId, key3.asString(), 20, 29);
        
        // Then: Verify all batches processed
        // Phase 14.2.5: tick-by-tick processing (30 ticks = 30 flush calls)
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("ticks_processed").longValue() >= 30);
        
        // Verify metrics
        Map<String, Number> metrics = indexer.getMetrics();
        assertEquals(3, metrics.get("batches_processed").intValue(), 
            "Should have processed 3 batches");
        assertEquals(30, metrics.get("ticks_processed").intValue(), 
            "Should have processed 30 ticks (10 per batch)");
    }
    
    // ========== Buffering Tests (Phase 14.2.6) ==========
    
    @Test
    void testBuffering_NormalFlush() throws Exception {
        // Given: Create test run with metadata
        String runId = "20251018-140000-" + UUID.randomUUID();
        SimulationMetadata metadata = createTestMetadata(runId, 10);
        indexMetadata(runId, metadata);
        
        // Write 3 chunks to storage (100 ticks each), insertBatchSize=3 (chunks)
        List<TickDataChunk> chunks1 = createTestChunks(runId, 0, 100);
        List<TickDataChunk> chunks2 = createTestChunks(runId, 100, 100);
        List<TickDataChunk> chunks3 = createTestChunks(runId, 200, 100);
        
        StoragePath key1 = testStorage.writeChunkBatchStreaming(chunks1.iterator()).path();
        StoragePath key2 = testStorage.writeChunkBatchStreaming(chunks2.iterator()).path();
        StoragePath key3 = testStorage.writeChunkBatchStreaming(chunks3.iterator()).path();
        
        // Create indexer WITH buffering (insertBatchSize=3 chunks)
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            insertBatchSize = 3
            flushTimeoutMs = 10000
            """.formatted(runId));
        
        indexer = createDummyIndexerWithBuffering("test-indexer", config);
        
        // When: Start indexer and send 3 batches (each containing 1 chunk)
        indexer.start();
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);
        
        sendBatchInfoToTopic(runId, key1.asString(), 0, 99);
        sendBatchInfoToTopic(runId, key2.asString(), 100, 199);
        sendBatchInfoToTopic(runId, key3.asString(), 200, 299);
        
        // Then: Verify flush triggered by size (buffer: 3 chunks >= insertBatchSize: 3)
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("ticks_processed").longValue() >= 300);
        
        // Verify all ticks flushed
        Map<String, Number> metrics = indexer.getMetrics();
        assertTrue(metrics.get("ticks_processed").intValue() >= 300,
            "All 300 ticks should be flushed");
    }
    
    @Test
    void testBuffering_TimeoutFlush() throws Exception {
        // Given: Create test run with metadata
        String runId = "20251018-141000-" + UUID.randomUUID();
        SimulationMetadata metadata = createTestMetadata(runId, 10);
        indexMetadata(runId, metadata);
        
        // Write one chunk (< insertBatchSize of 10 chunks)
        List<TickDataChunk> chunks1 = createTestChunks(runId, 0, 50);
        StoragePath key1 = testStorage.writeChunkBatchStreaming(chunks1.iterator()).path();
        
        // Create indexer with short flush timeout (insertBatchSize=10 chunks, but only 1 chunk sent)
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            insertBatchSize = 10
            flushTimeoutMs = 1000
            """.formatted(runId));
        
        indexer = createDummyIndexerWithBuffering("test-indexer", config);
        
        // When: Start and send batch
        indexer.start();
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);
        
        sendBatchInfoToTopic(runId, key1.asString(), 0, 49);
        
        // Then: Verify timeout-triggered flush (wait for flushTimeout + buffer)
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("ticks_processed").longValue() >= 50);
        
        assertEquals(50, indexer.getMetrics().get("ticks_processed").intValue(),
            "All 50 ticks should be flushed after timeout");
    }
    
    @Test
    void testBuffering_CrossBatchAck() throws Exception {
        // Given: insertBatchSize=3 chunks, each batch message contains 1 chunk with 100 ticks
        String runId = "20251018-142000-" + UUID.randomUUID();
        SimulationMetadata metadata = createTestMetadata(runId, 10);
        indexMetadata(runId, metadata);
        
        // Write 5 chunks to storage (each chunk = 100 ticks)
        List<TickDataChunk> chunks1 = createTestChunks(runId, 0, 100);
        List<TickDataChunk> chunks2 = createTestChunks(runId, 100, 100);
        List<TickDataChunk> chunks3 = createTestChunks(runId, 200, 100);
        List<TickDataChunk> chunks4 = createTestChunks(runId, 300, 100);
        List<TickDataChunk> chunks5 = createTestChunks(runId, 400, 100);
        
        StoragePath key1 = testStorage.writeChunkBatchStreaming(chunks1.iterator()).path();
        StoragePath key2 = testStorage.writeChunkBatchStreaming(chunks2.iterator()).path();
        StoragePath key3 = testStorage.writeChunkBatchStreaming(chunks3.iterator()).path();
        StoragePath key4 = testStorage.writeChunkBatchStreaming(chunks4.iterator()).path();
        StoragePath key5 = testStorage.writeChunkBatchStreaming(chunks5.iterator()).path();
        
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            insertBatchSize = 3
            flushTimeoutMs = 10000
            """.formatted(runId));
        
        indexer = createDummyIndexerWithBuffering("test-indexer", config);
        
        // When: Start and send batches (each batch = 1 chunk)
        indexer.start();
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);
        
        sendBatchInfoToTopic(runId, key1.asString(), 0, 99);      // buffer: 1 chunk
        sendBatchInfoToTopic(runId, key2.asString(), 100, 199);   // buffer: 2 chunks
        sendBatchInfoToTopic(runId, key3.asString(), 200, 299);   // buffer: 3 chunks → FLUSH!
        
        // Then: Verify first flush (3 chunks = 300 ticks)
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("ticks_processed").longValue() >= 300);
        
        // Continue with more batches
        sendBatchInfoToTopic(runId, key4.asString(), 300, 399);   // buffer: 1 chunk
        sendBatchInfoToTopic(runId, key5.asString(), 400, 499);   // buffer: 2 chunks (need timeout or more)
        
        // Wait for final flush via timeout (only 2 chunks in buffer < 3)
        await().atMost(15, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("ticks_processed").longValue() >= 500);
        
        // All 5 batches should now be fully flushed
        assertEquals(5, indexer.getMetrics().get("batches_processed").intValue(),
            "All 5 batches should be ACKed (fully flushed)");
        assertEquals(500, indexer.getMetrics().get("ticks_processed").intValue(),
            "All 500 ticks should be processed");
    }
    
    @Test
    void testBuffering_FinalFlush() throws Exception {
        // Given: Write chunks that will trigger normal flush first
        String runId = "20251018-143000-" + UUID.randomUUID();
        SimulationMetadata metadata = createTestMetadata(runId, 10);
        indexMetadata(runId, metadata);
        
        // Write 3 chunks: first 2 will trigger flush (insertBatchSize=2), 3rd will remain in buffer
        List<TickDataChunk> chunks1 = createTestChunks(runId, 0, 100);
        List<TickDataChunk> chunks2 = createTestChunks(runId, 100, 100);
        List<TickDataChunk> chunks3 = createTestChunks(runId, 200, 100);
        
        StoragePath key1 = testStorage.writeChunkBatchStreaming(chunks1.iterator()).path();
        StoragePath key2 = testStorage.writeChunkBatchStreaming(chunks2.iterator()).path();
        StoragePath key3 = testStorage.writeChunkBatchStreaming(chunks3.iterator()).path();
        
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            insertBatchSize = 2
            flushTimeoutMs = 60000
            """.formatted(runId));
        
        indexer = createDummyIndexerWithBuffering("test-indexer", config);
        
        // When: Start and send batches
        indexer.start();
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);
        
        sendBatchInfoToTopic(runId, key1.asString(), 0, 99);
        sendBatchInfoToTopic(runId, key2.asString(), 100, 199);
        sendBatchInfoToTopic(runId, key3.asString(), 200, 299);
        
        // Wait for first flush (2 chunks = 200 ticks)
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("ticks_processed").longValue() >= 200);
        
        // At this point: 1 chunk (100 ticks) remains in buffer
        // Stop service (triggers final flush of remaining chunk)
        indexer.stop();
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.STOPPED);
        
        // Then: Verify final flush executed remaining buffered chunks
        assertEquals(300, indexer.getMetrics().get("ticks_processed").intValue(),
            "All 300 ticks should be flushed (200 normal + 100 final flush)");
        assertEquals(3, indexer.getMetrics().get("batches_processed").intValue(),
            "All 3 batches should be ACKed (batch3 ACKed after final flush)");
    }
    
    // ========== Helper Methods ==========
    
    private DummyIndexer<?> createDummyIndexerWithBuffering(String name, Config config) {
        // Wrap database resource with db-meta-read usage type
        ResourceContext dbContext = new ResourceContext(
            name,
            "metadata",
            "db-meta-read",
            "test-db",
            Collections.emptyMap()
        );
        IResource wrappedDatabase = testDatabase.getWrappedResource(dbContext);
        
        // Wrap REAL topic resource with topic-read usage type
        ResourceContext topicContext = new ResourceContext(
            name,
            "topic",
            "topic-read",
            "batch-topic",
            Map.of("consumerGroup", "test-buffering-" + UUID.randomUUID())  // Unique consumer group
        );
        IResource wrappedTopic = testBatchTopic.getWrappedResource(topicContext);
        
        Map<String, List<IResource>> resources = Map.of(
            "storage", List.of(testStorage),
            "metadata", List.of(wrappedDatabase),
            "topic", List.of(wrappedTopic)
        );
        
        // Phase 14.2.6 tests: Use regular DummyIndexer WITH buffering
        return new DummyIndexer<>(name, config, resources);
    }
    
    private List<TickDataChunk> createTestChunks(String runId, long startTick, int tickCount) {
        // Create a single chunk containing tickCount ticks
        TickData snapshot = TickData.newBuilder()
            .setSimulationRunId(runId)
            .setTickNumber(startTick)
            .setCaptureTimeMs(System.currentTimeMillis())
            .build();
        
        TickDataChunk chunk = TickDataChunk.newBuilder()
            .setSimulationRunId(runId)
            .setFirstTick(startTick)
            .setLastTick(startTick + tickCount - 1)
            .setTickCount(tickCount)
            .setSnapshot(snapshot)
            .build();
        
        return List.of(chunk);
    }
    
    private void sendBatchInfoToTopic(String runId, String storageKey, long tickStart, long tickEnd) throws Exception {
        ResourceContext writerContext = new ResourceContext(
            "test-sender",
            "topic",
            "topic-write",
            "batch-topic",
            Collections.emptyMap()
        );
        
        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) testBatchTopic.getWrappedResource(writerContext);
        writer.setSimulationRun(runId);
        
        BatchInfo batchInfo = BatchInfo.newBuilder()
            .setSimulationRunId(runId)
            .setStoragePath(storageKey)
            .setTickStart(tickStart)
            .setTickEnd(tickEnd)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        writer.send(batchInfo);
    }
    
    private DummyIndexer<?> createDummyIndexerWithRealTopic(String name, Config config) {
        // Wrap database resource with db-meta-read usage type
        ResourceContext dbContext = new ResourceContext(
            name,
            "metadata",
            "db-meta-read",
            "test-db",
            Collections.emptyMap()
        );
        IResource wrappedDatabase = testDatabase.getWrappedResource(dbContext);
        
        // Wrap REAL topic resource with topic-read usage type
        ResourceContext topicContext = new ResourceContext(
            name,
            "topic",
            "topic-read",
            "batch-topic",
            Map.of("consumerGroup", "test-consumer")
        );
        IResource wrappedTopic = testBatchTopic.getWrappedResource(topicContext);
        
        Map<String, List<IResource>> resources = Map.of(
            "storage", List.of(testStorage),
            "metadata", List.of(wrappedDatabase),
            "topic", List.of(wrappedTopic)  // REAL topic, not mock!
        );
        
        // Phase 14.2.5 tests: Use indexer without buffering (tick-by-tick)
        return new TestDummyIndexerWithoutBuffering(name, config, resources);
    }
    
    private SimulationMetadata createTestMetadata(String runId, int samplingInterval) {
        return SimulationMetadata.newBuilder()
            .setSimulationRunId(runId)
            .setResolvedConfigJson(TestMetadataHelper.builder()
                .samplingInterval(samplingInterval)
                .build())
            .setInitialSeed(12345L)
            .setStartTimeMs(System.currentTimeMillis())
            .build();
    }
    
    private void indexMetadata(String runId, SimulationMetadata metadata) throws Exception {
        // Write metadata file to storage
        String storageKey = runId + "/raw/metadata.pb";
        testStorage.writeMessage(storageKey, metadata);
        
        // Write metadata directly to database (bypassing MetadataIndexer/Topic for simplicity)
        ResourceContext dbContext = new ResourceContext(
            "test-indexer",
            "database",
            "db-meta-write",
            "test-db",
            Collections.emptyMap()
        );
        IResource wrappedDatabase = testDatabase.getWrappedResource(dbContext);
        
        if (wrappedDatabase instanceof org.evochora.datapipeline.api.resources.database.IMetadataWriter metadataWriter) {
            ((IResourceSchemaAwareMetadataWriter) metadataWriter).setSimulationRun(runId);
            metadataWriter.insertMetadata(metadata);
            metadataWriter.close();
        } else {
            throw new IllegalStateException("Expected IMetadataWriter but got: " + wrappedDatabase.getClass());
        }
    }
    
    private DummyIndexer<?> createDummyIndexer(String name, Config config) {
        // Wrap database resource with db-meta-read usage type
        ResourceContext dbContext = new ResourceContext(
            name,
            "metadata",  // Port name must match getRequiredResource() call in DummyIndexer
            "db-meta-read",
            "test-db",
            Collections.emptyMap()
        );
        IResource wrappedDatabase = testDatabase.getWrappedResource(dbContext);
        
        Map<String, List<IResource>> resources = Map.of(
            "storage", List.of(testStorage),
            "metadata", List.of(wrappedDatabase),  // Port name must match getRequiredResource() call
            "topic", List.of(mockTopic)  // Mock topic for batch processing (not tested yet)
        );
        
        // Phase 14.2.5 tests: Use indexer without buffering (tick-by-tick)
        return new TestDummyIndexerWithoutBuffering(name, config, resources);
    }
    
    /**
     * Test implementation of DummyIndexer that disables buffering.
     * <p>
     * Used by Phase 14.2.5 tests to test tick-by-tick processing.
     * Phase 14.2.6 tests will use regular DummyIndexer with buffering.
     */
    private static class TestDummyIndexerWithoutBuffering extends DummyIndexer<Object> {
        public TestDummyIndexerWithoutBuffering(String name, Config options, Map<String, List<IResource>> resources) {
            super(name, options, resources);
        }
        
        @Override
        protected java.util.Set<ComponentType> getRequiredComponents() {
            // Disable buffering for Phase 14.2.5 tests
            return java.util.EnumSet.of(ComponentType.METADATA);
        }
    }
}


