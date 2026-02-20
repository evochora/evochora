package org.evochora.datapipeline.services.indexers;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareMetadataReader;
import org.evochora.datapipeline.api.resources.storage.CheckedConsumer;
import org.evochora.datapipeline.api.resources.storage.IResourceBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.topics.IResourceTopicReader;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for AbstractBatchIndexer.
 * <p>
 * Tests the core batch processing logic, ACK behavior, and error handling
 * using mocks (no real database, topic, or storage).
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class AbstractBatchIndexerTest {

    private IResourceTopicReader<BatchInfo, String> mockTopic;
    private IResourceBatchStorageRead mockStorage;
    private IResourceSchemaAwareMetadataReader mockMetadataReader;

    private TestBatchIndexer indexer;
    private List<List<TickDataChunk>> flushedBatches;
    private CountDownLatch flushLatch;
    private AtomicInteger flushCount;

    // Streaming test state
    private StreamingTestBatchIndexer streamingIndexer;
    private List<TickDataChunk> streamingProcessedChunks;
    private AtomicInteger streamingCommitCount;

    @BeforeEach
    void setup() throws Exception {
        // Create mocks that implement both capability interfaces AND IResource
        // This simulates production where wrappers implement IResource via AbstractResource
        @SuppressWarnings("unchecked")
        IResourceTopicReader<BatchInfo, String> topicMock = mock(IResourceTopicReader.class);
        mockTopic = topicMock;
        mockStorage = mock(IResourceBatchStorageRead.class);
        mockMetadataReader = mock(IResourceSchemaAwareMetadataReader.class);

        flushedBatches = new ArrayList<>();
        flushCount = new AtomicInteger(0);
        flushLatch = new CountDownLatch(0);

        streamingProcessedChunks = Collections.synchronizedList(new ArrayList<>());
        streamingCommitCount = new AtomicInteger(0);

        // All tests use configured runIds — stub storage validation to pass
        lenient().when(mockStorage.findMetadataPath(any(String.class)))
            .thenReturn(Optional.of(StoragePath.of("dummy/raw/metadata.pb")));

        // Support multi-arg readChunkBatch: delegate to interface default methods,
        // which ultimately call the 1-arg version (stubbed per-test).
        stubReadChunkBatchOverloads();
    }
    
    private void stubReadChunkBatchOverloads() throws Exception {
        // forEachChunk is abstract — provide an answer that iterates from readChunkBatch(path)
        lenient().doAnswer(invocation -> {
            StoragePath p = invocation.getArgument(0);
            CheckedConsumer<TickDataChunk> c = invocation.getArgument(2);
            for (TickDataChunk chunk : mockStorage.readChunkBatch(p)) {
                c.accept(chunk);
            }
            return null;
        }).when(mockStorage).forEachChunk(any(), any(), any());
        // readChunkBatch(path, filter) is a default that delegates to forEachChunk
        lenient().when(mockStorage.readChunkBatch(any(StoragePath.class), any(org.evochora.datapipeline.api.resources.storage.ChunkFieldFilter.class)))
            .thenCallRealMethod();
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() throws Exception {
        stopIfRunning(indexer);
        stopIfRunning(streamingIndexer);
    }

    private void stopIfRunning(AbstractBatchIndexer<?> idx) {
        if (idx != null
                && idx.getCurrentState() != org.evochora.datapipeline.api.services.IService.State.STOPPED
                && idx.getCurrentState() != org.evochora.datapipeline.api.services.IService.State.ERROR) {
            idx.stop();
            await().atMost(5, TimeUnit.SECONDS)
                .until(() -> idx.getCurrentState() == org.evochora.datapipeline.api.services.IService.State.STOPPED
                    || idx.getCurrentState() == org.evochora.datapipeline.api.services.IService.State.ERROR);
        }
    }
    
    @Test
    void testAckAfterSuccessfulProcessing() throws Exception {
        // Given: Mock setup for successful processing
        String runId = "test-run-001";
        SimulationMetadata metadata = createTestMetadata(runId);
        List<TickDataChunk> chunks = createTestChunks(runId, 0, 5);
        BatchInfo batchInfo = createBatchInfo(runId, "batch_001.pb", 0, 4);
        TopicMessage<BatchInfo, String> message = new TopicMessage<>(
            batchInfo, System.currentTimeMillis(), "msg-001", "test-consumer", "ack-token-001");
        
        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(message)  // First call: return batch
            .thenReturn(null);    // Subsequent calls: return null (keep running)
        when(mockStorage.readChunkBatch(StoragePath.of(batchInfo.getStoragePath()))).thenReturn(chunks);
        
        // Expect 1 flush call (batch-passthrough: all ticks in one flush)
        flushLatch = new CountDownLatch(1);
        
        // When: Start indexer
        indexer = createIndexer(runId, true);  // with metadata component
        indexer.start();
        
        // Wait for batch to be flushed
        assertTrue(flushLatch.await(5, TimeUnit.SECONDS), "Batch should be flushed");
        
        // Then: Verify batch-passthrough processing (one flush with all ticks)
        assertEquals(5, flushCount.get(), "Should have flushed 5 ticks total");
        assertEquals(1, flushedBatches.size(), "Should have 1 flushed batch (batch-passthrough)");
        assertEquals(5, flushedBatches.get(0).size(), "Batch should contain all 5 chunks");
        
        // CRITICAL: Verify ACK was sent AFTER batch processed
        await().atMost(2, TimeUnit.SECONDS)
            .untilAsserted(() -> verify(mockTopic, times(1)).ack(message));
        
        // Verify storage was read exactly once
        verify(mockStorage, times(1)).readChunkBatch(StoragePath.of(batchInfo.getStoragePath()));
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
        when(mockStorage.readChunkBatch(StoragePath.of(batchInfo.getStoragePath())))
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
        List<TickDataChunk> chunks = createTestChunks(runId, 0, 3);
        BatchInfo batchInfo = createBatchInfo(runId, "batch_003.pb", 0, 2);
        TopicMessage<BatchInfo, String> message = new TopicMessage<>(
            batchInfo, System.currentTimeMillis(), "msg-003", "test-consumer", "ack-token-003");
        
        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(message)  // First call: return batch with error
            .thenReturn(null);    // Subsequent calls: null (indexer continues polling)
        when(mockStorage.readChunkBatch(StoragePath.of(batchInfo.getStoragePath()))).thenReturn(chunks);
        
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
    void testBatchPassthrough() throws Exception {
        // Given: Multiple ticks in one batch (batch-passthrough: all ticks flushed together)
        String runId = "test-run-004";
        SimulationMetadata metadata = createTestMetadata(runId);
        List<TickDataChunk> chunks = createTestChunks(runId, 0, 10);  // 10 ticks
        BatchInfo batchInfo = createBatchInfo(runId, "batch_004.pb", 0, 9);
        TopicMessage<BatchInfo, String> message = new TopicMessage<>(
            batchInfo, System.currentTimeMillis(), "msg-004", "test-consumer", "ack-token-004");
        
        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(message)
            .thenReturn(null);
        when(mockStorage.readChunkBatch(StoragePath.of(batchInfo.getStoragePath()))).thenReturn(chunks);
        
        // Batch-passthrough: 1 flush for entire batch
        flushLatch = new CountDownLatch(1);
        
        // When: Process batch
        indexer = createIndexer(runId, true);
        indexer.start();
        
        assertTrue(flushLatch.await(5, TimeUnit.SECONDS), "Batch should be flushed");
        
        // Then: Verify batch-passthrough (all chunks in one flush)
        assertEquals(10, flushCount.get(), "Should have flushed 10 ticks total");
        assertEquals(1, flushedBatches.size(), "Should have 1 batch flush (batch-passthrough)");
        assertEquals(10, flushedBatches.get(0).size(), "Batch should contain all 10 chunks");
        
        // Verify chunks are in order within the batch
        for (int i = 0; i < flushedBatches.get(0).size(); i++) {
            assertEquals(i, flushedBatches.get(0).get(i).getFirstTick(), "Chunk should be in order");
        }
        
        // Verify ACK sent after batch flushed
        await().atMost(2, TimeUnit.SECONDS)
            .untilAsserted(() -> verify(mockTopic, times(1)).ack(message));
    }
    
    @Test
    void testMetadataLoadedBeforeBatchProcessing() throws Exception {
        // Given: Metadata and batch
        String runId = "test-run-005";
        SimulationMetadata metadata = createTestMetadata(runId);
        List<TickDataChunk> chunks = createTestChunks(runId, 0, 2);
        BatchInfo batchInfo = createBatchInfo(runId, "batch_005.pb", 0, 1);
        TopicMessage<BatchInfo, String> message = new TopicMessage<>(
            batchInfo, System.currentTimeMillis(), "msg-005", "test-consumer", "ack-token-005");
        
        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(message)
            .thenReturn(null);
        when(mockStorage.readChunkBatch(StoragePath.of(batchInfo.getStoragePath()))).thenReturn(chunks);
        
        // Batch-passthrough: 1 flush for entire batch
        flushLatch = new CountDownLatch(1);
        
        // When: Start indexer
        indexer = createIndexer(runId, true);
        indexer.start();
        
        // Wait for processing
        assertTrue(flushLatch.await(5, TimeUnit.SECONDS), "Batch should be flushed");
        
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
        
        List<TickDataChunk> chunks1 = createTestChunks(runId, 0, 5);
        List<TickDataChunk> chunks2 = createTestChunks(runId, 5, 3);
        
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
        when(mockStorage.readChunkBatch(StoragePath.of(batch1.getStoragePath()))).thenReturn(chunks1);
        when(mockStorage.readChunkBatch(StoragePath.of(batch2.getStoragePath()))).thenReturn(chunks2);
        
        // Batch-passthrough: 2 flushes (one per batch)
        flushLatch = new CountDownLatch(2);
        
        // When: Process batches
        indexer = createIndexer(runId, true);
        indexer.start();
        
        assertTrue(flushLatch.await(5, TimeUnit.SECONDS), "Both batches should be flushed");
        
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
    
    // ========== Streaming Processing Tests ==========

    @Test
    void testStreamingAckAfterSuccessfulProcessing() throws Exception {
        // Given: 3 chunks, insertBatchSize=3 (exactly one commit during forEachChunk,
        // then completeBatch triggers ackCompletedBatches → ACK)
        String runId = "test-run-s01";
        SimulationMetadata metadata = createTestMetadata(runId);
        List<TickDataChunk> chunks = createTestChunks(runId, 0, 3);
        BatchInfo batchInfo = createBatchInfo(runId, "batch_s01.pb", 0, 2);
        TopicMessage<BatchInfo, String> message = new TopicMessage<>(
            batchInfo, System.currentTimeMillis(), "msg-s01", "test-consumer", "ack-s01");

        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(message)
            .thenReturn(null);
        when(mockStorage.readChunkBatch(StoragePath.of(batchInfo.getStoragePath()))).thenReturn(chunks);

        // When
        streamingIndexer = createStreamingIndexer(runId, 3);
        streamingIndexer.start();

        // Then: ACK after all chunks committed
        await().atMost(5, TimeUnit.SECONDS)
            .untilAsserted(() -> verify(mockTopic, times(1)).ack(message));

        assertEquals(3, streamingProcessedChunks.size(), "All 3 chunks should be processed");
        assertEquals(1, streamingCommitCount.get(), "Should have 1 commit");
    }

    @Test
    void testStreamingMultipleCommitsPerBatch() throws Exception {
        // Given: 6 chunks, insertBatchSize=3 → 2 commits during forEachChunk
        String runId = "test-run-s02";
        SimulationMetadata metadata = createTestMetadata(runId);
        List<TickDataChunk> chunks = createTestChunks(runId, 0, 6);
        BatchInfo batchInfo = createBatchInfo(runId, "batch_s02.pb", 0, 5);
        TopicMessage<BatchInfo, String> message = new TopicMessage<>(
            batchInfo, System.currentTimeMillis(), "msg-s02", "test-consumer", "ack-s02");

        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(message)
            .thenReturn(null);
        when(mockStorage.readChunkBatch(StoragePath.of(batchInfo.getStoragePath()))).thenReturn(chunks);

        // When
        streamingIndexer = createStreamingIndexer(runId, 3);
        streamingIndexer.start();

        // Then
        await().atMost(5, TimeUnit.SECONDS)
            .untilAsserted(() -> verify(mockTopic, times(1)).ack(message));

        assertEquals(6, streamingProcessedChunks.size(), "All 6 chunks should be processed");
        assertEquals(2, streamingCommitCount.get(), "Should have 2 commits (at chunks 3 and 6)");
    }

    @Test
    void testStreamingCrossBatchAckOrder() throws Exception {
        // Given: batch1=2 chunks, batch2=1 chunk, insertBatchSize=3
        // Commit spans both batches (2 from batch1 + 1 from batch2 = 3)
        // batch1 ACKed at commit (complete + committed), batch2 ACKed after completeBatch
        String runId = "test-run-s03";
        SimulationMetadata metadata = createTestMetadata(runId);

        List<TickDataChunk> chunks1 = createTestChunks(runId, 0, 2);
        List<TickDataChunk> chunks2 = createTestChunks(runId, 2, 1);

        BatchInfo batch1 = createBatchInfo(runId, "batch_s03a.pb", 0, 1);
        BatchInfo batch2 = createBatchInfo(runId, "batch_s03b.pb", 2, 2);

        TopicMessage<BatchInfo, String> msg1 = new TopicMessage<>(
            batch1, System.currentTimeMillis(), "msg-s03a", "test-consumer", "ack-s03a");
        TopicMessage<BatchInfo, String> msg2 = new TopicMessage<>(
            batch2, System.currentTimeMillis(), "msg-s03b", "test-consumer", "ack-s03b");

        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(msg1)
            .thenReturn(msg2)
            .thenReturn(null);
        when(mockStorage.readChunkBatch(StoragePath.of(batch1.getStoragePath()))).thenReturn(chunks1);
        when(mockStorage.readChunkBatch(StoragePath.of(batch2.getStoragePath()))).thenReturn(chunks2);

        // When
        streamingIndexer = createStreamingIndexer(runId, 3);
        streamingIndexer.start();

        // Then: Both batches ACKed
        await().atMost(5, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                verify(mockTopic, times(1)).ack(msg1);
                verify(mockTopic, times(1)).ack(msg2);
            });

        assertEquals(3, streamingProcessedChunks.size(), "All 3 chunks should be processed");
        assertEquals(1, streamingCommitCount.get(), "Should have 1 cross-batch commit");
    }

    @Test
    @AllowLog(level = LogLevel.WARN, messagePattern = "Failed to process batch.*")
    void testStreamingNoAckOnStorageReadError() throws Exception {
        // Given: forEachChunk fails (via readChunkBatch throwing in mock answer)
        String runId = "test-run-s04";
        SimulationMetadata metadata = createTestMetadata(runId);
        BatchInfo batchInfo = createBatchInfo(runId, "batch_s04.pb", 0, 2);
        TopicMessage<BatchInfo, String> message = new TopicMessage<>(
            batchInfo, System.currentTimeMillis(), "msg-s04", "test-consumer", "ack-s04");

        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(message)
            .thenReturn(null);
        when(mockStorage.readChunkBatch(StoragePath.of(batchInfo.getStoragePath())))
            .thenThrow(new IOException("Storage read failed"));

        // When
        streamingIndexer = createStreamingIndexer(runId, 3);
        streamingIndexer.start();

        // Then: Error tracked, no ACK, indexer continues
        await().atMost(3, TimeUnit.SECONDS)
            .until(() -> !streamingIndexer.getErrors().isEmpty());

        assertEquals("BATCH_PROCESSING_FAILED", streamingIndexer.getErrors().get(0).errorType());
        verify(mockTopic, never()).ack(any());
        assertEquals(0, streamingProcessedChunks.size(), "No chunks should be processed");
    }

    @Test
    @AllowLog(level = LogLevel.WARN, messagePattern = "Failed to process batch.*")
    void testStreamingNoAckOnProcessChunkError() throws Exception {
        // Given: processChunk throws on first chunk
        String runId = "test-run-s05";
        SimulationMetadata metadata = createTestMetadata(runId);
        List<TickDataChunk> chunks = createTestChunks(runId, 0, 3);
        BatchInfo batchInfo = createBatchInfo(runId, "batch_s05.pb", 0, 2);
        TopicMessage<BatchInfo, String> message = new TopicMessage<>(
            batchInfo, System.currentTimeMillis(), "msg-s05", "test-consumer", "ack-s05");

        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(message)
            .thenReturn(null);
        when(mockStorage.readChunkBatch(StoragePath.of(batchInfo.getStoragePath()))).thenReturn(chunks);

        // When: processChunk error
        streamingIndexer = createStreamingIndexerWithError(runId, true, false);
        streamingIndexer.start();

        // Then
        await().atMost(3, TimeUnit.SECONDS)
            .until(() -> !streamingIndexer.getErrors().isEmpty());

        assertEquals("BATCH_PROCESSING_FAILED", streamingIndexer.getErrors().get(0).errorType());
        verify(mockTopic, never()).ack(any());
    }

    @Test
    @AllowLog(level = LogLevel.WARN, messagePattern = "Failed to process batch.*")
    @AllowLog(level = LogLevel.WARN, messagePattern = "Final streaming commit failed.*")
    void testStreamingNoAckOnCommitError() throws Exception {
        // Given: 3 chunks, insertBatchSize=3 → commit triggered after chunk 3 but fails
        String runId = "test-run-s06";
        SimulationMetadata metadata = createTestMetadata(runId);
        List<TickDataChunk> chunks = createTestChunks(runId, 0, 3);
        BatchInfo batchInfo = createBatchInfo(runId, "batch_s06.pb", 0, 2);
        TopicMessage<BatchInfo, String> message = new TopicMessage<>(
            batchInfo, System.currentTimeMillis(), "msg-s06", "test-consumer", "ack-s06");

        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(message)
            .thenReturn(null);
        when(mockStorage.readChunkBatch(StoragePath.of(batchInfo.getStoragePath()))).thenReturn(chunks);

        // When: commit error (insertBatchSize=3 triggers commit after all 3 chunks processed)
        streamingIndexer = createStreamingIndexerWithError(runId, false, true);
        streamingIndexer.start();

        // Then
        await().atMost(3, TimeUnit.SECONDS)
            .until(() -> !streamingIndexer.getErrors().isEmpty());

        assertEquals("BATCH_PROCESSING_FAILED", streamingIndexer.getErrors().get(0).errorType());
        verify(mockTopic, never()).ack(any());
        assertEquals(3, streamingProcessedChunks.size(), "All 3 chunks processed before commit failed");
    }

    @Test
    void testStreamingTimeoutBasedCommit() throws Exception {
        // Given: 2 chunks, insertBatchSize=100 (never reached), flushTimeoutMs=100
        // No commit during processing → timeout triggers commit → ACK
        String runId = "test-run-s07";
        SimulationMetadata metadata = createTestMetadata(runId);
        List<TickDataChunk> chunks = createTestChunks(runId, 0, 2);
        BatchInfo batchInfo = createBatchInfo(runId, "batch_s07.pb", 0, 1);
        TopicMessage<BatchInfo, String> message = new TopicMessage<>(
            batchInfo, System.currentTimeMillis(), "msg-s07", "test-consumer", "ack-s07");

        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(message)
            .thenReturn(null);
        when(mockStorage.readChunkBatch(StoragePath.of(batchInfo.getStoragePath()))).thenReturn(chunks);

        // When: insertBatchSize too large to trigger during processing, timeout will trigger
        streamingIndexer = createStreamingIndexer(runId, 100, 100);
        streamingIndexer.start();

        // Then: ACK after timeout-based commit
        await().atMost(5, TimeUnit.SECONDS)
            .untilAsserted(() -> verify(mockTopic, times(1)).ack(message));

        assertEquals(2, streamingProcessedChunks.size(), "All 2 chunks should be processed");
        assertEquals(1, streamingCommitCount.get(), "Should have 1 timeout-triggered commit");
    }

    @Test
    void testStreamingMetricsTracking() throws Exception {
        // Given: 2 batches × 3 chunks each, insertBatchSize=3
        String runId = "test-run-s08";
        SimulationMetadata metadata = createTestMetadata(runId);

        List<TickDataChunk> chunks1 = createTestChunks(runId, 0, 3);
        List<TickDataChunk> chunks2 = createTestChunks(runId, 3, 3);

        BatchInfo batch1 = createBatchInfo(runId, "batch_s08a.pb", 0, 2);
        BatchInfo batch2 = createBatchInfo(runId, "batch_s08b.pb", 3, 5);

        TopicMessage<BatchInfo, String> msg1 = new TopicMessage<>(
            batch1, System.currentTimeMillis(), "msg-s08a", "test-consumer", "ack-s08a");
        TopicMessage<BatchInfo, String> msg2 = new TopicMessage<>(
            batch2, System.currentTimeMillis(), "msg-s08b", "test-consumer", "ack-s08b");

        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(msg1)
            .thenReturn(msg2)
            .thenReturn(null);
        when(mockStorage.readChunkBatch(StoragePath.of(batch1.getStoragePath()))).thenReturn(chunks1);
        when(mockStorage.readChunkBatch(StoragePath.of(batch2.getStoragePath()))).thenReturn(chunks2);

        // When
        streamingIndexer = createStreamingIndexer(runId, 3);
        streamingIndexer.start();

        // Then: Metrics track both batches
        await().atMost(5, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                Map<String, Number> metrics = streamingIndexer.getMetrics();
                assertEquals(2, metrics.get("batches_processed").intValue(),
                    "Should track 2 batches");
                assertEquals(6, metrics.get("ticks_processed").intValue(),
                    "Should track 6 ticks total");
            });
    }

    // ========== readAndProcessChunks Override Test ==========

    @Test
    void testStreamingReadAndProcessChunks_OverrideDispatchesToSubclass() throws Exception {
        // Verify that a subclass can override readAndProcessChunks to bypass
        // the default forEachChunk path (e.g., EnvironmentIndexer's raw-byte path).
        // Uses direct reflection invocation to avoid lifecycle overhead.
        String runId = "test-run-override";
        List<TickDataChunk> chunks = createTestChunks(runId, 0, 3);

        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            insertBatchSize = 100
            flushTimeoutMs = 5000
            """.formatted(runId));

        Map<String, List<IResource>> resources = new java.util.HashMap<>();
        resources.put("storage", List.of((IResource) mockStorage));
        resources.put("topic", List.of((IResource) mockTopic));
        resources.put("metadata", List.of((IResource) mockMetadataReader));

        AtomicInteger customReadCount = new AtomicInteger(0);

        StreamingTestBatchIndexer customIndexer = new StreamingTestBatchIndexer(
                "test-override-indexer", config, resources, false, false) {
            @Override
            protected void readAndProcessChunks(StoragePath path, String batchId) throws Exception {
                customReadCount.incrementAndGet();
                for (TickDataChunk chunk : chunks) {
                    processChunk(chunk);
                    onChunkStreamed(batchId, chunk.getTickCount());
                }
            }
        };

        // Register batch in tracker (required for onChunkStreamed)
        java.lang.reflect.Field trackerField =
            AbstractBatchIndexer.class.getDeclaredField("streamingTracker");
        trackerField.setAccessible(true);
        Object tracker = trackerField.get(customIndexer);
        java.lang.reflect.Method registerMethod = tracker.getClass()
            .getDeclaredMethod("registerBatch", String.class,
                org.evochora.datapipeline.api.resources.topics.TopicMessage.class);
        registerMethod.setAccessible(true);
        registerMethod.invoke(tracker, "batch-override",
            mock(org.evochora.datapipeline.api.resources.topics.TopicMessage.class));

        // Invoke readAndProcessChunks directly
        java.lang.reflect.Method method = AbstractBatchIndexer.class.getDeclaredMethod(
            "readAndProcessChunks", StoragePath.class, String.class);
        method.setAccessible(true);
        method.invoke(customIndexer, StoragePath.of("batch_override.pb"), "batch-override");

        // Override was called (not the default forEachChunk path)
        assertEquals(1, customReadCount.get(), "Custom readAndProcessChunks should be called");
        assertEquals(3, streamingProcessedChunks.size(), "All 3 chunks should be processed");

        // forEachChunk was NOT called (override bypassed it)
        verify(mockStorage, never()).forEachChunk(any(), any(), any());
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
    
    private StreamingTestBatchIndexer createStreamingIndexer(String runId, int insertBatchSize) {
        return createStreamingIndexer(runId, insertBatchSize, 5000);
    }

    private StreamingTestBatchIndexer createStreamingIndexer(String runId, int insertBatchSize, long flushTimeoutMs) {
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            insertBatchSize = %d
            flushTimeoutMs = %d
            """.formatted(runId, insertBatchSize, flushTimeoutMs));

        Map<String, List<IResource>> resources = new java.util.HashMap<>();
        resources.put("storage", List.of((IResource) mockStorage));
        resources.put("topic", List.of((IResource) mockTopic));
        resources.put("metadata", List.of((IResource) mockMetadataReader));

        return new StreamingTestBatchIndexer("test-streaming-indexer", config, resources, false, false);
    }

    private StreamingTestBatchIndexer createStreamingIndexerWithError(String runId,
                                                                      boolean throwOnProcess,
                                                                      boolean throwOnCommit) {
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            insertBatchSize = 3
            flushTimeoutMs = 5000
            """.formatted(runId));

        Map<String, List<IResource>> resources = new java.util.HashMap<>();
        resources.put("storage", List.of((IResource) mockStorage));
        resources.put("topic", List.of((IResource) mockTopic));
        resources.put("metadata", List.of((IResource) mockMetadataReader));

        return new StreamingTestBatchIndexer("test-streaming-indexer", config, resources, throwOnProcess, throwOnCommit);
    }

    private SimulationMetadata createTestMetadata(String runId) {
        return SimulationMetadata.newBuilder()
            .setSimulationRunId(runId)
            .setResolvedConfigJson(TestMetadataHelper.builder()
                .samplingInterval(10)
                .build())
            .setInitialSeed(12345L)
            .setStartTimeMs(System.currentTimeMillis())
            .build();
    }
    
    private List<TickDataChunk> createTestChunks(String runId, long startTick, int count) {
        List<TickDataChunk> chunks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long chunkStartTick = startTick + i;
            TickData snapshot = TickData.newBuilder()
                .setSimulationRunId(runId)
                .setTickNumber(chunkStartTick)
                .setCaptureTimeMs(System.currentTimeMillis())
                .build();
            chunks.add(TickDataChunk.newBuilder()
                .setSimulationRunId(runId)
                .setFirstTick(chunkStartTick)
                .setLastTick(chunkStartTick)
                .setTickCount(1)  // Each chunk contains 1 tick (snapshot only)
                .setSnapshot(snapshot)
                .build());
        }
        return chunks;
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
     * Streaming test implementation of AbstractBatchIndexer.
     * Uses processChunk/commitProcessedChunks instead of flushChunks.
     */
    private class StreamingTestBatchIndexer extends AbstractBatchIndexer<String> {

        private final boolean throwOnProcessChunk;
        private final boolean throwOnCommit;

        StreamingTestBatchIndexer(String name, Config options, Map<String, List<IResource>> resources,
                                  boolean throwOnProcessChunk, boolean throwOnCommit) {
            super(name, options, resources);
            this.throwOnProcessChunk = throwOnProcessChunk;
            this.throwOnCommit = throwOnCommit;
        }

        @Override
        protected boolean useStreamingProcessing() {
            return true;
        }

        @Override
        protected Set<ComponentType> getRequiredComponents() {
            return EnumSet.of(ComponentType.METADATA);
        }

        @Override
        protected void processChunk(TickDataChunk chunk) throws Exception {
            if (throwOnProcessChunk) {
                throw new RuntimeException("Simulated processChunk error");
            }
            streamingProcessedChunks.add(chunk);
        }

        @Override
        protected void commitProcessedChunks() throws Exception {
            if (throwOnCommit) {
                throw new RuntimeException("Simulated commit error");
            }
            streamingCommitCount.incrementAndGet();
        }

        @Override
        protected void flushChunks(List<TickDataChunk> chunks) throws Exception {
            throw new UnsupportedOperationException("Streaming indexer should not call flushChunks");
        }
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
            // No BUFFERING: Uses batch-passthrough (one flush per topic message)
            return withMetadata ? EnumSet.of(ComponentType.METADATA) : EnumSet.noneOf(ComponentType.class);
        }
        
        @Override
        protected void flushChunks(List<TickDataChunk> chunks) throws Exception {
            if (throwOnFlush) {
                flushLatch.countDown();
                throw new RuntimeException("Simulated flush error");
            }
            
            flushedBatches.add(new ArrayList<>(chunks));
            int totalTicks = chunks.stream().mapToInt(TickDataChunk::getTickCount).sum();
            flushCount.addAndGet(totalTicks);
            flushLatch.countDown();
        }
    }
}

