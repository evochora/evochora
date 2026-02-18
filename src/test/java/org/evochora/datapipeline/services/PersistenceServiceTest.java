package org.evochora.datapipeline.services;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.StreamingBatch;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.storage.StreamingWriteResult;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.api.services.IService.State;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for PersistenceService with streaming write architecture.
 * <p>
 * Tests cover the receive-process-acknowledge pattern: receiveBatch → writeChunkBatchStreaming → commit.
 * Write failure causes rollback via close() without commit, relying on broker redelivery.
 */
@Tag("unit")
@ExtendWith({MockitoExtension.class, LogWatchExtension.class})
@AllowLog(level = LogLevel.WARN, messagePattern = "PersistenceService initialized WITHOUT batch-topic - event-driven indexing disabled!")
class PersistenceServiceTest {

    @Mock
    private IInputQueueResource<TickDataChunk> mockInputQueue;

    @Mock
    private IBatchStorageWrite mockStorage;

    @Mock
    private ITopicWriter<BatchInfo> mockBatchTopic;

    @Mock
    private IIdempotencyTracker<Long> mockIdempotencyTracker;

    private PersistenceService service;
    private Map<String, List<IResource>> resources;
    private Config config;

    @BeforeEach
    void setUp() {
        resources = new HashMap<>();
        resources.put("input", Collections.singletonList(mockInputQueue));
        resources.put("storage", Collections.singletonList(mockStorage));
        // topic is optional - add it per test as needed

        config = ConfigFactory.parseMap(Map.of(
            "maxBatchSize", 100,
            "batchTimeoutSeconds", 2
        ));
    }

    // ========== Constructor Tests ==========

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*PersistenceService.*")
    void testConstructorWithRequiredResources() {
        service = new PersistenceService("test-persistence", config, resources);

        assertNotNull(service);
        assertEquals("test-persistence", service.serviceName);
        assertTrue(service.isHealthy());
        assertEquals(0, service.getErrors().size());
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*PersistenceService.*")
    void testConstructorWithOptionalResources() {
        resources.put("idempotencyTracker", Collections.singletonList(mockIdempotencyTracker));

        service = new PersistenceService("test-persistence", config, resources);

        assertNotNull(service);
        assertTrue(service.isHealthy());
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*PersistenceService.*")
    void testConstructorWithInvalidMaxBatchSize() {
        Config invalidConfig = ConfigFactory.parseMap(Map.of("maxBatchSize", 0));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            new PersistenceService("test-persistence", invalidConfig, resources);
        });

        assertTrue(exception.getMessage().contains("maxBatchSize must be positive"));
    }

    @Test
    void testConstructorWithInvalidBatchTimeout() {
        Config invalidConfig = ConfigFactory.parseMap(Map.of("batchTimeoutSeconds", -1));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            new PersistenceService("test-persistence", invalidConfig, resources);
        });

        assertTrue(exception.getMessage().contains("batchTimeoutSeconds must be positive"));
    }

    @Test
    void testConstructorWithMissingInputResource() {
        resources.remove("input");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            new PersistenceService("test-persistence", config, resources);
        });

        assertTrue(exception.getMessage().contains("Resource port 'input' is not configured"));
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*PersistenceService.*")
    void testConstructorWithMissingStorageResource() {
        resources.remove("storage");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            new PersistenceService("test-persistence", config, resources);
        });

        assertTrue(exception.getMessage().contains("Resource port 'storage' is not configured"));
    }

    // ========== Streaming Write Tests ==========

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*PersistenceService.*")
    void testSuccessfulStreamingWrite() throws Exception {
        service = new PersistenceService("test-persistence", config, resources);

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class)))
            .thenReturn(batchOf(createTestBatch("sim-123", 100, 102)))
            .thenThrow(new InterruptedException("Test shutdown"));
        when(mockStorage.writeChunkBatchStreaming(any()))
            .thenReturn(new StreamingWriteResult(
                StoragePath.of("001/batch_0000000000000000100_0000000000000000102.pb"),
                "sim-123", 100L, 102L, 3, 3, 1024L));

        service.start();

        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_written").longValue() > 0);

        // Verify streaming write was called (not the old batch write)
        verify(mockStorage).writeChunkBatchStreaming(any());
        verify(mockStorage, never()).writeChunkBatch(any(), anyLong(), anyLong());

        assertEquals(1, service.getMetrics().get("batches_written").longValue());
        assertEquals(3, service.getMetrics().get("ticks_written").longValue());
        assertEquals(1024, service.getMetrics().get("bytes_written").longValue());
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Failed to write streaming batch: .*")
    void testWriteFailureDoesNotCommit() throws Exception {
        service = new PersistenceService("test-persistence", config, resources);

        StreamingBatch<TickDataChunk> spyBatch = new SpyStreamingBatch<>(createTestBatch("sim-123", 100, 102));

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class)))
            .thenReturn(spyBatch)
            .thenThrow(new InterruptedException("Test shutdown"));
        when(mockStorage.writeChunkBatchStreaming(any()))
            .thenThrow(new IOException("Storage unavailable"));

        service.start();

        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_failed").longValue() > 0);

        // Verify commit was NOT called (rollback via close)
        assertEquals(0, ((SpyStreamingBatch<?>) spyBatch).commitCount);
        assertEquals(1, ((SpyStreamingBatch<?>) spyBatch).closeCount);

        assertEquals(0, service.getMetrics().get("batches_written").longValue());
        assertEquals(1, service.getMetrics().get("batches_failed").longValue());

        // Verify error was recorded
        assertEquals(1, service.getErrors().size());
        assertEquals("BATCH_WRITE_FAILED", service.getErrors().get(0).errorType());
    }

    @Test
    void testEmptyBatchHandling() throws Exception {
        service = new PersistenceService("test-persistence", config, resources);

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class)))
            .thenReturn(emptyBatch())
            .thenThrow(new InterruptedException("Test shutdown"));

        service.start();

        await().atMost(2, TimeUnit.SECONDS)
            .untilAsserted(() -> verify(mockInputQueue, atLeastOnce())
                .receiveBatch(anyInt(), anyLong(), any(TimeUnit.class)));

        // Verify no storage operations occurred
        verify(mockStorage, never()).writeChunkBatchStreaming(any());

        Map<String, Number> metrics = service.getMetrics();
        assertEquals(0, metrics.get("batches_written").longValue());
        assertEquals(0, metrics.get("ticks_written").longValue());
    }

    @Test
    void testMetricsAccuracy() throws Exception {
        service = new PersistenceService("test-persistence", config, resources);

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class)))
            .thenReturn(batchOf(createTestBatch("sim-123", 100, 102)))
            .thenThrow(new InterruptedException("Test shutdown"));
        when(mockStorage.writeChunkBatchStreaming(any()))
            .thenReturn(new StreamingWriteResult(
                StoragePath.of("batch_file.pb"), "sim-123", 100L, 102L, 3, 3, 2048L));

        service.start();

        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_written").longValue() > 0);

        Map<String, Number> metrics = service.getMetrics();

        assertEquals(1, metrics.get("batches_written").longValue());
        assertEquals(3, metrics.get("ticks_written").longValue());
        assertEquals(2048, metrics.get("bytes_written").longValue());
        assertEquals(0, metrics.get("batches_failed").longValue());
        assertEquals(0, metrics.get("duplicate_ticks_detected").longValue());
        assertEquals(3, metrics.get("current_batch_size").intValue());
    }

    @Test
    void testCurrentBatchSizeResetToZero() throws Exception {
        service = new PersistenceService("test-persistence", config, resources);

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class)))
            .thenReturn(batchOf(createTestBatch("sim-123", 100, 102)))
            .thenReturn(emptyBatch())
            .thenThrow(new InterruptedException("Test shutdown"));
        when(mockStorage.writeChunkBatchStreaming(any()))
            .thenReturn(new StreamingWriteResult(
                StoragePath.of("batch_file.pb"), "sim-123", 100L, 102L, 3, 3, 1024L));

        service.start();

        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_written").longValue() > 0);

        // Wait for empty batch to reset currentBatchSize to 0
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("current_batch_size").intValue() == 0);

        assertEquals(0, service.getMetrics().get("current_batch_size").intValue());
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*PersistenceService.*")
    void testGracefulShutdown() throws Exception {
        service = new PersistenceService("test-persistence", config, resources);

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class)))
            .thenReturn(batchOf(createTestBatch("sim-123", 100, 102)))
            .thenThrow(new InterruptedException("Shutdown signal"));
        when(mockStorage.writeChunkBatchStreaming(any()))
            .thenReturn(new StreamingWriteResult(
                StoragePath.of("batch_file.pb"), "sim-123", 100L, 102L, 3, 3, 1024L));

        service.start();

        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> service.getCurrentState() == State.STOPPED);

        verify(mockStorage).writeChunkBatchStreaming(any());
        assertEquals(1, service.getMetrics().get("batches_written").longValue());
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*PersistenceService.*")
    void testHealthCheckReflectsServiceState() throws Exception {
        service = new PersistenceService("test-persistence", config, resources);

        assertTrue(service.isHealthy());

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class)))
            .thenReturn(emptyBatch())
            .thenThrow(new InterruptedException("Test shutdown"));

        service.start();

        await().atMost(2, TimeUnit.SECONDS)
            .untilAsserted(() -> verify(mockInputQueue, atLeastOnce())
                .receiveBatch(anyInt(), anyLong(), any(TimeUnit.class)));

        assertTrue(service.isHealthy());

        await().atMost(2, TimeUnit.SECONDS)
            .until(() -> service.getCurrentState() == State.STOPPED);

        assertTrue(service.isHealthy());
    }

    // ========== Idempotency Tests ==========

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Duplicate chunk detected: .*", occurrences = -1)
    void testDuplicateChunkFiltering() throws Exception {
        resources.put("idempotencyTracker", Collections.singletonList(mockIdempotencyTracker));
        service = new PersistenceService("test-persistence", config, resources);

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class)))
            .thenReturn(batchOf(createTestBatch("sim-123", 100, 102)))
            .thenThrow(new InterruptedException("Test shutdown"));

        // Mock must drain the iterator to trigger the filtering logic
        when(mockStorage.writeChunkBatchStreaming(any())).thenAnswer(invocation -> {
            Iterator<TickDataChunk> iter = invocation.getArgument(0);
            int count = 0;
            long lastTick = 0;
            while (iter.hasNext()) {
                TickDataChunk chunk = iter.next();
                lastTick = chunk.getLastTick();
                count++;
            }
            return new StreamingWriteResult(
                StoragePath.of("batch_file.pb"), "sim-123", 101L, lastTick, count, count, 512L);
        });

        // First tick is duplicate (returns false), others are new (returns true)
        when(mockIdempotencyTracker.checkAndMarkProcessed(100L)).thenReturn(false);
        when(mockIdempotencyTracker.checkAndMarkProcessed(101L)).thenReturn(true);
        when(mockIdempotencyTracker.checkAndMarkProcessed(102L)).thenReturn(true);

        service.start();

        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_written").longValue() > 0);

        verify(mockStorage).writeChunkBatchStreaming(any());
        verify(mockIdempotencyTracker).checkAndMarkProcessed(100L);
        verify(mockIdempotencyTracker).checkAndMarkProcessed(101L);
        verify(mockIdempotencyTracker).checkAndMarkProcessed(102L);

        assertEquals(1, service.getMetrics().get("duplicate_ticks_detected").longValue());
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Duplicate chunk detected: .*", occurrences = -1)
    void testAllDuplicatesSkipped() throws Exception {
        resources.put("idempotencyTracker", Collections.singletonList(mockIdempotencyTracker));
        service = new PersistenceService("test-persistence", config, resources);

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class)))
            .thenReturn(batchOf(createTestBatch("sim-123", 100, 102)))
            .thenThrow(new InterruptedException("Test shutdown"));

        // All ticks are duplicates
        when(mockIdempotencyTracker.checkAndMarkProcessed(anyLong())).thenReturn(false);

        service.start();

        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("duplicate_ticks_detected").longValue() >= 3);

        // Storage should never be called (all duplicates)
        verify(mockStorage, never()).writeChunkBatchStreaming(any());

        assertEquals(3, service.getMetrics().get("duplicate_ticks_detected").longValue());
        assertEquals(0, service.getMetrics().get("batches_written").longValue());
    }

    // ========== Batch Notification Tests ==========

    @Test
    void shouldSendBatchNotificationAfterSuccessfulWrite() throws Exception {
        resources.put("topic", Collections.singletonList(mockBatchTopic));
        service = new PersistenceService("test-persistence", config, resources);
        List<TickDataChunk> batch = createTestBatch("run-123", 0, 99);

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class)))
            .thenReturn(batchOf(batch))
            .thenReturn(emptyBatch());
        when(mockStorage.writeChunkBatchStreaming(any()))
            .thenReturn(new StreamingWriteResult(
                StoragePath.of("run-123/raw/batch_0000000000000000000_0000000000000000099.pb"),
                "run-123", 0L, 99L, 100, 100, 4096L));

        service.start();
        await().atMost(2, TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_written").longValue() == 1);
        service.stop();

        verify(mockStorage).writeChunkBatchStreaming(any());
        verify(mockBatchTopic).send(argThat(notification ->
            notification.getSimulationRunId().equals("run-123") &&
            notification.getStoragePath().equals("run-123/raw/batch_0000000000000000000_0000000000000000099.pb") &&
            notification.getTickStart() == 0 &&
            notification.getTickEnd() == 99 &&
            notification.getWrittenAtMs() > 0
        ));

        assertEquals(1, service.getMetrics().get("batches_written").longValue());
        assertEquals(1, service.getMetrics().get("notifications_sent").longValue());
    }

    @Test
    void shouldIncludeCorrectBatchInfoFields() throws Exception {
        resources.put("topic", Collections.singletonList(mockBatchTopic));
        service = new PersistenceService("test-persistence", config, resources);
        List<TickDataChunk> batch = createTestBatch("run-abc-def", 1000, 1099);

        long beforeWrite = System.currentTimeMillis();

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class)))
            .thenReturn(batchOf(batch))
            .thenReturn(emptyBatch());
        when(mockStorage.writeChunkBatchStreaming(any()))
            .thenReturn(new StreamingWriteResult(
                StoragePath.of("run-abc-def/raw/batch_0000000000000001000_0000000000000001099.pb"),
                "run-abc-def", 1000L, 1099L, 100, 100, 4096L));

        service.start();
        await().atMost(2, TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_written").longValue() == 1);
        service.stop();

        long afterWrite = System.currentTimeMillis();

        verify(mockBatchTopic).send(argThat(notification -> {
            assertEquals("run-abc-def", notification.getSimulationRunId());
            assertEquals("run-abc-def/raw/batch_0000000000000001000_0000000000000001099.pb", notification.getStoragePath());
            assertEquals(1000, notification.getTickStart());
            assertEquals(1099, notification.getTickEnd());
            assertTrue(notification.getWrittenAtMs() >= beforeWrite);
            assertTrue(notification.getWrittenAtMs() <= afterWrite);
            return true;
        }));
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Failed to write streaming batch: .*")
    void shouldNotSendNotificationIfStorageWriteFails() throws Exception {
        resources.put("topic", Collections.singletonList(mockBatchTopic));
        service = new PersistenceService("test-persistence", config, resources);
        List<TickDataChunk> batch = createTestBatch("run-123", 0, 99);

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class)))
            .thenReturn(batchOf(batch))
            .thenReturn(emptyBatch());
        when(mockStorage.writeChunkBatchStreaming(any()))
            .thenThrow(new IOException("Storage unavailable"));

        service.start();
        await().atMost(2, TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_failed").longValue() == 1);
        service.stop();

        // Topic was never called because storage failed
        verify(mockStorage).writeChunkBatchStreaming(any());
        verify(mockBatchTopic, never()).send(any(BatchInfo.class));

        assertEquals(0, service.getMetrics().get("batches_written").longValue());
        assertEquals(0, service.getMetrics().get("notifications_sent").longValue());
        assertEquals(1, service.getMetrics().get("batches_failed").longValue());
    }

    @Test
    void shouldNotCommitIfTopicSendIsInterrupted() throws Exception {
        resources.put("topic", Collections.singletonList(mockBatchTopic));
        service = new PersistenceService("test-persistence", config, resources);
        List<TickDataChunk> batch = createTestBatch("run-123", 0, 99);

        SpyStreamingBatch<TickDataChunk> spyBatch = new SpyStreamingBatch<>(batch);
        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class)))
            .thenReturn(spyBatch)
            .thenReturn(emptyBatch());
        when(mockStorage.writeChunkBatchStreaming(any()))
            .thenReturn(new StreamingWriteResult(
                StoragePath.of("run-123/raw/batch.pb"), "run-123", 0L, 99L, 100, 100, 4096L));

        // Topic send throws InterruptedException → service stops, no commit
        doThrow(new InterruptedException("Topic unavailable"))
            .when(mockBatchTopic).send(any(BatchInfo.class));

        service.start();
        await().atMost(2, TimeUnit.SECONDS)
            .until(() -> service.getCurrentState() == State.STOPPED);

        // Storage was written but commit was not called (topic failed)
        verify(mockStorage).writeChunkBatchStreaming(any());
        assertEquals(0, spyBatch.commitCount);

        // Batch not counted as written (no commit)
        assertEquals(0, service.getMetrics().get("batches_written").longValue());
    }

    // ========== Error Tracking Tests ==========

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Failed to write streaming batch: .*")
    void testErrorTracking() throws Exception {
        service = new PersistenceService("test-persistence", config, resources);

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class)))
            .thenReturn(batchOf(createTestBatch("sim-123", 100, 102)))
            .thenThrow(new InterruptedException("Test shutdown"));
        when(mockStorage.writeChunkBatchStreaming(any()))
            .thenThrow(new IOException("Disk full"));

        service.start();

        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> !service.getErrors().isEmpty());

        assertEquals(1, service.getErrors().size());
        assertEquals("BATCH_WRITE_FAILED", service.getErrors().get(0).errorType());
        assertEquals("Streaming write failed", service.getErrors().get(0).message());
        assertEquals("Disk full", service.getErrors().get(0).details());

        service.clearErrors();
        assertEquals(0, service.getErrors().size());
    }

    // ========== Memory Estimate Tests ==========

    @Test
    void testMemoryEstimateReflectsStreaming() {
        service = new PersistenceService("test-persistence", config, resources);

        SimulationParameters params = SimulationParameters.of(new int[]{400, 300}, 100);
        List<org.evochora.datapipeline.api.memory.MemoryEstimate> estimates = service.estimateWorstCaseMemory(params);

        assertEquals(1, estimates.size());
        var estimate = estimates.get(0);
        assertEquals("test-persistence", estimate.componentName());

        // Streaming: N × serialized chunk + 1 × deserialized chunk
        long serializedBytesPerChunk = params.estimateSerializedBytesPerChunk();
        long deserializedBytesPerChunk = params.estimateBytesPerChunk();
        long expected = 100L * serializedBytesPerChunk + deserializedBytesPerChunk;
        assertEquals(expected, estimate.estimatedBytes());

        // Verify it's less than the old N × deserialized chunk model
        long oldEstimate = 100L * deserializedBytesPerChunk;
        assertTrue(estimate.estimatedBytes() < oldEstimate,
            "Streaming estimate should be less than old batch estimate");
    }

    // ========== Helper Methods ==========

    private List<TickDataChunk> createTestBatch(String simulationRunId, long startTick, long endTick) {
        List<TickDataChunk> batch = new ArrayList<>();
        for (long tick = startTick; tick <= endTick; tick++) {
            batch.add(createChunk(simulationRunId, tick));
        }
        return batch;
    }

    private TickDataChunk createChunk(String simulationRunId, long firstTick) {
        TickData snapshot = TickData.newBuilder()
            .setSimulationRunId(simulationRunId)
            .setTickNumber(firstTick)
            .build();
        return TickDataChunk.newBuilder()
            .setSimulationRunId(simulationRunId)
            .setFirstTick(firstTick)
            .setLastTick(firstTick)
            .setTickCount(1)
            .setSnapshot(snapshot)
            .build();
    }

    private static <T> StreamingBatch<T> batchOf(List<T> items) {
        return new StreamingBatch<T>() {
            @Override public int size() { return items.size(); }
            @Override public Iterator<T> iterator() { return items.iterator(); }
            @Override public void commit() {}
            @Override public void close() {}
        };
    }

    private static <T> StreamingBatch<T> emptyBatch() {
        return new StreamingBatch<T>() {
            @Override public int size() { return 0; }
            @Override public Iterator<T> iterator() { return Collections.emptyIterator(); }
            @Override public void commit() {}
            @Override public void close() {}
        };
    }

    /**
     * A StreamingBatch that tracks commit and close calls for verification.
     */
    private static class SpyStreamingBatch<T> implements StreamingBatch<T> {
        private final List<T> items;
        int commitCount = 0;
        int closeCount = 0;

        SpyStreamingBatch(List<T> items) {
            this.items = items;
        }

        @Override public int size() { return items.size(); }
        @Override public Iterator<T> iterator() { return items.iterator(); }
        @Override public void commit() { commitCount++; }
        @Override public void close() { closeCount++; }
    }
}
