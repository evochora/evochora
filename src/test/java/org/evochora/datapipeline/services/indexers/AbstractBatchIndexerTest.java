package org.evochora.datapipeline.services.indexers;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() throws Exception {
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
        // Given: 7 chunks, insertBatchSize=3 (not a multiple!)
        // → 2 threshold commits (at chunks 3 and 6) + 1 timeout commit for the remainder (chunk 7)
        String runId = "test-run-s02";
        SimulationMetadata metadata = createTestMetadata(runId);
        List<TickDataChunk> chunks = createTestChunks(runId, 0, 7);
        BatchInfo batchInfo = createBatchInfo(runId, "batch_s02.pb", 0, 6);
        TopicMessage<BatchInfo, String> message = new TopicMessage<>(
            batchInfo, System.currentTimeMillis(), "msg-s02", "test-consumer", "ack-s02");

        lenient().when(mockMetadataReader.hasMetadata(runId)).thenReturn(true);
        lenient().when(mockMetadataReader.getMetadata(runId)).thenReturn(metadata);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class)))
            .thenReturn(message)
            .thenReturn(null);
        when(mockStorage.readChunkBatch(StoragePath.of(batchInfo.getStoragePath()))).thenReturn(chunks);

        // When: flushTimeoutMs=200 so the remainder chunk commits quickly on timeout
        streamingIndexer = createStreamingIndexer(runId, 3, 200);
        streamingIndexer.start();

        // Then
        await().atMost(5, TimeUnit.SECONDS)
            .untilAsserted(() -> verify(mockTopic, times(1)).ack(message));

        assertEquals(7, streamingProcessedChunks.size(), "All 7 chunks should be processed");
        assertEquals(3, streamingCommitCount.get(),
            "Should have 3 commits (at chunks 3, 6, and 7 via timeout)");
    }

    @Test
    void testStreamingCrossBatchAckOrder() throws Exception {
        // Given: batch1=5 chunks, batch2=4 chunks, insertBatchSize=3 (not a multiple of either!)
        // Commit #1 at chunk 3: first 3 of batch1 committed
        // Commit #2 at chunk 6: remaining 2 of batch1 + first 1 of batch2 → batch1 fully committed → ACK batch1
        // Commit #3 at chunk 9: remaining 3 of batch2 → batch2 fully committed → ACK batch2 after completeBatch
        String runId = "test-run-s03";
        SimulationMetadata metadata = createTestMetadata(runId);

        List<TickDataChunk> chunks1 = createTestChunks(runId, 0, 5);
        List<TickDataChunk> chunks2 = createTestChunks(runId, 5, 4);

        BatchInfo batch1 = createBatchInfo(runId, "batch_s03a.pb", 0, 4);
        BatchInfo batch2 = createBatchInfo(runId, "batch_s03b.pb", 5, 8);

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

        assertEquals(9, streamingProcessedChunks.size(), "All 9 chunks should be processed");
        assertEquals(3, streamingCommitCount.get(),
            "Should have 3 commits (cross-batch boundaries, non-multiple of batch sizes)");
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
     * Test implementation of AbstractBatchIndexer for streaming tests.
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
    }

}

