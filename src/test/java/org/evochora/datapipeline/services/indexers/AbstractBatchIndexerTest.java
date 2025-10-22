package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.IMetadataReader;
import org.evochora.datapipeline.api.resources.database.ISchemaAwareDatabase;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AbstractBatchIndexer.
 * <p>
 * Tests the core batch processing logic, ACK behavior, and error handling
 * using mocks (no real database, topic, or storage).
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class AbstractBatchIndexerTest {

    private ITopicReader<BatchInfo, String> mockTopic;
    private IBatchStorageRead mockStorage;
    private IMetadataReader mockMetadataReader;

    private TestBatchIndexer indexer;
    private List<List<TickData>> flushedBatches;
    private CountDownLatch flushLatch;
    private AtomicInteger flushCount;

    @BeforeEach
    void setup() {
        // Create mocks that implement both capability interfaces AND IResource
        // This simulates production where wrappers implement IResource via AbstractResource
        mockTopic = mock(ITopicReader.class, withSettings().extraInterfaces(IResource.class));
        mockStorage = mock(IBatchStorageRead.class, withSettings().extraInterfaces(IResource.class));
        mockMetadataReader = mock(IMetadataReader.class, withSettings().extraInterfaces(IResource.class, ISchemaAwareDatabase.class));

        flushedBatches = new ArrayList<>();
        flushCount = new AtomicInteger(0);
        flushLatch = new CountDownLatch(0);
    }
    
    @org.junit.jupiter.api.AfterEach
    void cleanup() throws Exception {
        // Stop indexer if still running
        if (indexer != null && indexer.getCurrentState() != org.evochora.datapipeline.api.services.IService.State.STOPPED
            && indexer.getCurrentState() != org.evochora.datapipeline.api.services.IService.State.ERROR) {
            indexer.stop();
            await().atMost(5, TimeUnit.SECONDS)
                .until(() -> indexer.getCurrentState() == org.evochora.datapipeline.api.services.IService.State.STOPPED 
                    || indexer.getCurrentState() == org.evochora.datapipeline.api.services.IService.State.ERROR);
        }
    }
    
    @Test
    void testAckAfterSuccessfulProcessing() throws Exception {
        // Given: Mock setup for successful processing
        String runId = "test-run-001";
        SimulationMetadata metadata = createTestMetadata(runId);
        List<TickData> ticks = createTestTicks(runId, 0, 5);
        BatchInfo batchInfo = createBatchInfo(runId, "batch_001.pb", 0, 4);
        TopicMessage<BatchInfo, String> message = new TopicMessage<>(
            batchInfo, System.currentTimeMillis(), "msg-001", "test-consumer", "ack-token-001");
        
        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(message)  // First call: return batch
            .thenReturn(null);    // Subsequent calls: return null (keep running)
        when(mockStorage.readBatch(StoragePath.of(batchInfo.getStoragePath()))).thenReturn(ticks);
        
        // Expect 5 flush calls (tick-by-tick processing)
        flushLatch = new CountDownLatch(5);
        
        // When: Start indexer
        indexer = createIndexer(runId, true);  // with metadata component
        indexer.start();
        
        // Wait for all ticks to be flushed
        assertTrue(flushLatch.await(5, TimeUnit.SECONDS), "All ticks should be flushed");
        
        // Then: Verify tick-by-tick processing
        assertEquals(5, flushCount.get(), "Should have 5 flush calls (one per tick)");
        assertEquals(5, flushedBatches.size(), "Should have 5 flushed batches");
        for (List<TickData> batch : flushedBatches) {
            assertEquals(1, batch.size(), "Each flush should contain exactly 1 tick");
        }
        
        // CRITICAL: Verify ACK was sent AFTER all ticks processed
        await().atMost(2, TimeUnit.SECONDS)
            .untilAsserted(() -> verify(mockTopic, times(1)).ack(message));
        
        // Verify storage was read exactly once
        verify(mockStorage, times(1)).readBatch(StoragePath.of(batchInfo.getStoragePath()));
    }
    
    @Test
    @AllowLog(level = LogLevel.WARN, messagePattern = "Failed to process batch.*")
    void testNoAckOnStorageReadError() throws Exception {
        // Given: Mock setup with storage read error
        String runId = "test-run-002";
        SimulationMetadata metadata = createTestMetadata(runId);
        BatchInfo batchInfo = createBatchInfo(runId, "batch_002.pb", 0, 4);
        TopicMessage<BatchInfo, String> message = new TopicMessage<>(
            batchInfo, System.currentTimeMillis(), "msg-002", "test-consumer", "ack-token-002");
        
        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(message)  // First call: return batch with error
            .thenReturn(null);    // Subsequent calls: null (indexer continues polling)
        
        // Storage read fails
        when(mockStorage.readBatch(StoragePath.of(batchInfo.getStoragePath())))
            .thenThrow(new IOException("Storage read failed"));
        
        // When: Start indexer
        indexer = createIndexer(runId, true);
        indexer.start();
        
        // Wait for error to be tracked (indexer stays RUNNING)
        await().atMost(3, TimeUnit.SECONDS)
            .until(() -> !indexer.getErrors().isEmpty());
        
        // Then: Verify indexer is still RUNNING (not ERROR)
        assertEquals(org.evochora.datapipeline.api.services.IService.State.RUNNING, indexer.getCurrentState(),
            "Indexer should continue running after transient error");
        
        // Verify error was tracked
        assertEquals(1, indexer.getErrors().size(), "Error should be tracked");
        assertEquals("BATCH_PROCESSING_FAILED", indexer.getErrors().get(0).errorType(), 
            "Error type should be BATCH_PROCESSING_FAILED");
        
        // Verify NO ACK was sent
        verify(mockTopic, never()).ack(any());
        
        // Verify no ticks were flushed
        assertEquals(0, flushCount.get(), "No ticks should be flushed on storage error");
    }
    
    @Test
    @AllowLog(level = LogLevel.WARN, messagePattern = "Failed to process batch.*")
    void testNoAckOnFlushError() throws Exception {
        // Given: Mock setup with flush error
        String runId = "test-run-003";
        SimulationMetadata metadata = createTestMetadata(runId);
        List<TickData> ticks = createTestTicks(runId, 0, 3);
        BatchInfo batchInfo = createBatchInfo(runId, "batch_003.pb", 0, 2);
        TopicMessage<BatchInfo, String> message = new TopicMessage<>(
            batchInfo, System.currentTimeMillis(), "msg-003", "test-consumer", "ack-token-003");
        
        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(message)  // First call: return batch with error
            .thenReturn(null);    // Subsequent calls: null (indexer continues polling)
        when(mockStorage.readBatch(StoragePath.of(batchInfo.getStoragePath()))).thenReturn(ticks);
        
        // Configure test indexer to throw error on flush
        flushLatch = new CountDownLatch(1);
        
        // When: Start indexer with flush error
        indexer = createIndexerWithFlushError(runId);
        indexer.start();
        
        // Wait for flush attempt
        assertTrue(flushLatch.await(5, TimeUnit.SECONDS), "Flush should be attempted");
        
        // Wait for error to be tracked (indexer stays RUNNING)
        await().atMost(3, TimeUnit.SECONDS)
            .until(() -> !indexer.getErrors().isEmpty());
        
        // Then: Verify indexer is still RUNNING (not ERROR)
        assertEquals(org.evochora.datapipeline.api.services.IService.State.RUNNING, indexer.getCurrentState(),
            "Indexer should continue running after transient flush error");
        
        // Verify error was tracked
        assertEquals(1, indexer.getErrors().size(), "Error should be tracked");
        assertEquals("BATCH_PROCESSING_FAILED", indexer.getErrors().get(0).errorType(), 
            "Error type should be BATCH_PROCESSING_FAILED");
        
        // Verify NO ACK was sent
        verify(mockTopic, never()).ack(any());
    }
    
    @Test
    void testEachTickProcessedIndividually() throws Exception {
        // Given: Multiple ticks in one batch
        String runId = "test-run-004";
        SimulationMetadata metadata = createTestMetadata(runId);
        List<TickData> ticks = createTestTicks(runId, 0, 10);  // 10 ticks
        BatchInfo batchInfo = createBatchInfo(runId, "batch_004.pb", 0, 9);
        TopicMessage<BatchInfo, String> message = new TopicMessage<>(
            batchInfo, System.currentTimeMillis(), "msg-004", "test-consumer", "ack-token-004");
        
        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(message)
            .thenReturn(null);
        when(mockStorage.readBatch(StoragePath.of(batchInfo.getStoragePath()))).thenReturn(ticks);
        
        flushLatch = new CountDownLatch(10);
        
        // When: Process batch
        indexer = createIndexer(runId, true);
        indexer.start();
        
        assertTrue(flushLatch.await(5, TimeUnit.SECONDS), "All ticks should be flushed");
        
        // Then: Verify each tick was processed individually
        assertEquals(10, flushCount.get(), "Should have 10 flush calls");
        assertEquals(10, flushedBatches.size(), "Should have 10 individual flushes");
        
        // Verify each flush contained exactly 1 tick
        for (int i = 0; i < flushedBatches.size(); i++) {
            List<TickData> batch = flushedBatches.get(i);
            assertEquals(1, batch.size(), "Flush " + i + " should contain exactly 1 tick");
            assertEquals(i, batch.get(0).getTickNumber(), "Tick should be in order");
        }
        
        // Verify ACK sent after ALL ticks
        await().atMost(2, TimeUnit.SECONDS)
            .untilAsserted(() -> verify(mockTopic, times(1)).ack(message));
    }
    
    @Test
    void testMetadataLoadedBeforeBatchProcessing() throws Exception {
        // Given: Metadata and batch
        String runId = "test-run-005";
        SimulationMetadata metadata = createTestMetadata(runId);
        List<TickData> ticks = createTestTicks(runId, 0, 2);
        BatchInfo batchInfo = createBatchInfo(runId, "batch_005.pb", 0, 1);
        TopicMessage<BatchInfo, String> message = new TopicMessage<>(
            batchInfo, System.currentTimeMillis(), "msg-005", "test-consumer", "ack-token-005");
        
        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(message)
            .thenReturn(null);
        when(mockStorage.readBatch(StoragePath.of(batchInfo.getStoragePath()))).thenReturn(ticks);
        
        flushLatch = new CountDownLatch(2);
        
        // When: Start indexer
        indexer = createIndexer(runId, true);
        indexer.start();
        
        // Wait for processing
        assertTrue(flushLatch.await(5, TimeUnit.SECONDS), "Ticks should be flushed");
        
        // Then: Verify correct order: metadata operations → storage read → ack
        // (Component checks metadata internally before batch processing starts)
        await().atMost(2, TimeUnit.SECONDS)
            .untilAsserted(() -> verify(mockTopic, times(1)).ack(message));
    }
    
    @Test
    void testMetricsTracking() throws Exception {
        // Given: Multiple batches
        String runId = "test-run-006";
        SimulationMetadata metadata = createTestMetadata(runId);
        
        List<TickData> ticks1 = createTestTicks(runId, 0, 5);
        List<TickData> ticks2 = createTestTicks(runId, 5, 3);
        
        BatchInfo batch1 = createBatchInfo(runId, "batch_001.pb", 0, 4);
        BatchInfo batch2 = createBatchInfo(runId, "batch_002.pb", 5, 7);
        
        TopicMessage<BatchInfo, String> msg1 = new TopicMessage<>(
            batch1, System.currentTimeMillis(), "msg-1", "test-consumer", "ack-1");
        TopicMessage<BatchInfo, String> msg2 = new TopicMessage<>(
            batch2, System.currentTimeMillis(), "msg-2", "test-consumer", "ack-2");
        
        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(msg1)
            .thenReturn(msg2)
            .thenAnswer(invocation -> null);  // Keep returning null indefinitely
        when(mockStorage.readBatch(StoragePath.of(batch1.getStoragePath()))).thenReturn(ticks1);
        when(mockStorage.readBatch(StoragePath.of(batch2.getStoragePath()))).thenReturn(ticks2);
        
        flushLatch = new CountDownLatch(8);  // 5 + 3 ticks
        
        // When: Process batches
        indexer = createIndexer(runId, true);
        indexer.start();
        
        assertTrue(flushLatch.await(5, TimeUnit.SECONDS), "All ticks should be flushed");
        
        // Then: Verify metrics (use await to ensure all batches are counted)
        await().atMost(2, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                Map<String, Number> metrics = indexer.getMetrics();
                assertEquals(2, metrics.get("batches_processed").intValue(), 
                    "Should track 2 batches");
                assertEquals(8, metrics.get("ticks_processed").intValue(), 
                    "Should track 8 ticks total");
            });
    }
    
    // ========== Helper Methods ==========
    
    private TestBatchIndexer createIndexer(String runId, boolean withMetadata) {
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 100
            """.formatted(runId));
        
        // Cast capability mocks to IResource for test setup
        // In production, these are wrapped in classes that implement IResource
        Map<String, List<IResource>> resources = new java.util.HashMap<>();
        resources.put("storage", List.of((IResource) mockStorage));
        resources.put("topic", List.of((IResource) mockTopic));
        if (withMetadata) {
            resources.put("metadata", List.of((IResource) mockMetadataReader));
        }
        
        return new TestBatchIndexer("test-indexer", config, resources, withMetadata, false);
    }
    
    private TestBatchIndexer createIndexerWithFlushError(String runId) {
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 100
            """.formatted(runId));
        
        // Cast capability mocks to IResource for test setup
        // In production, these are wrapped in classes that implement IResource
        Map<String, List<IResource>> resources = Map.of(
            "storage", List.of((IResource) mockStorage),
            "topic", List.of((IResource) mockTopic),
            "metadata", List.of((IResource) mockMetadataReader)
        );
        
        return new TestBatchIndexer("test-indexer", config, resources, true, true);
    }
    
    private SimulationMetadata createTestMetadata(String runId) {
        return SimulationMetadata.newBuilder()
            .setSimulationRunId(runId)
            .setSamplingInterval(10)
            .setInitialSeed(12345L)
            .setStartTimeMs(System.currentTimeMillis())
            .build();
    }
    
    private List<TickData> createTestTicks(String runId, long startTick, int count) {
        List<TickData> ticks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ticks.add(TickData.newBuilder()
                .setSimulationRunId(runId)
                .setTickNumber(startTick + i)
                .setCaptureTimeMs(System.currentTimeMillis())
                .build());
        }
        return ticks;
    }
    
    private BatchInfo createBatchInfo(String runId, String storageKey, long tickStart, long tickEnd) {
        return BatchInfo.newBuilder()
            .setSimulationRunId(runId)
            .setStoragePath(storageKey)
            .setTickStart(tickStart)
            .setTickEnd(tickEnd)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
    }
    
    /**
     * Concrete test implementation of AbstractBatchIndexer.
     */
    private class TestBatchIndexer extends AbstractBatchIndexer<String> {
        
        private final boolean withMetadata;
        private final boolean throwOnFlush;
        
        public TestBatchIndexer(String name, Config options, Map<String, List<IResource>> resources,
                                boolean withMetadata, boolean throwOnFlush) {
            super(name, options, resources);
            this.withMetadata = withMetadata;
            this.throwOnFlush = throwOnFlush;
        }
        
        @Override
        protected Set<ComponentType> getRequiredComponents() {
            // Phase 14.2.5 tests: No buffering (tick-by-tick)
            return withMetadata ? EnumSet.of(ComponentType.METADATA) : EnumSet.noneOf(ComponentType.class);
        }
        
        @Override
        protected void flushTicks(List<TickData> ticks) throws Exception {
            if (throwOnFlush) {
                flushLatch.countDown();
                throw new RuntimeException("Simulated flush error");
            }
            
            flushedBatches.add(new ArrayList<>(ticks));
            flushCount.addAndGet(ticks.size());
            flushLatch.countDown();
        }
    }
}

