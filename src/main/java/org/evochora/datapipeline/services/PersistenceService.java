package org.evochora.datapipeline.services;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.resources.storage.StreamingWriteResult;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;

import com.typesafe.config.Config;

/**
 * Service that streams TickDataChunk batches from queues to storage one chunk at a time.
 * <p>
 * PersistenceService uses a streaming architecture where chunks are lazily deserialized
 * from the message broker during the write to storage. This keeps peak heap usage at
 * 1x chunk instead of Nx chunks (where N is batch size).
 * <p>
 * <strong>Receive-Process-Acknowledge pattern:</strong>
 * <ol>
 *   <li>{@code receiveBatch()}: receive N message references from broker (lightweight)</li>
 *   <li>{@code writeChunkBatchStreaming()}: lazily deserialize and write one chunk at a time</li>
 *   <li>{@code commit()}: acknowledge messages — broker deletes them</li>
 * </ol>
 * <p>
 * On write failure, messages are not committed. The broker automatically redelivers them
 * on the next {@code receiveBatch()} call. Poison messages are handled by configuring
 * the broker's dead letter address with max delivery attempts.
 * <p>
 * Multiple instances can run concurrently as competing consumers on the same queue.
 * <p>
 * <strong>Thread Safety:</strong> Each instance runs in its own thread. No synchronization
 * needed between instances - queue handles distribution, idempotency tracker is thread-safe.
 */
public class PersistenceService extends AbstractService implements IMemoryEstimatable {

    // Required resources
    private final IInputQueueResource<TickDataChunk> inputQueue;
    private final IBatchStorageWrite storage;
    private final ITopicWriter<BatchInfo> batchTopic;

    // Optional resources
    private final IIdempotencyTracker<Long> idempotencyTracker;

    // Configuration
    private final int maxBatchSize;
    private final int batchTimeoutSeconds;

    // Metrics
    private final AtomicLong batchesWritten = new AtomicLong(0);
    private final AtomicLong ticksWritten = new AtomicLong(0);
    private final AtomicLong bytesWritten = new AtomicLong(0);
    private final AtomicLong batchesFailed = new AtomicLong(0);
    private final AtomicLong duplicateTicksDetected = new AtomicLong(0);
    private final AtomicInteger currentBatchSize = new AtomicInteger(0);
    private final AtomicLong notificationsSent = new AtomicLong(0);
    private final AtomicLong notificationsFailed = new AtomicLong(0);

    // State tracking
    private volatile boolean topicInitialized = false;

    /**
     * Constructs a PersistenceService with the given name, options, and resources.
     * <p>
     * <strong>Required resources:</strong>
     * <ul>
     *   <li>{@code input}: {@link IInputQueueResource} for TickDataChunk messages</li>
     *   <li>{@code storage}: {@link IBatchStorageWrite} for writing chunk batches</li>
     * </ul>
     * <strong>Optional resources:</strong>
     * <ul>
     *   <li>{@code topic}: {@link ITopicWriter} for batch notifications (enables event-driven indexing)</li>
     *   <li>{@code idempotencyTracker}: {@link IIdempotencyTracker} for duplicate chunk detection</li>
     * </ul>
     *
     * @param name      the service name
     * @param options   HOCON config with {@code maxBatchSize} and {@code batchTimeoutSeconds}
     * @param resources the resource map
     * @throws IllegalArgumentException if configuration values are invalid
     * @throws IllegalStateException    if required resources are missing
     */
    public PersistenceService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);

        // Required resources
        @SuppressWarnings("unchecked")
        IInputQueueResource<TickDataChunk> queue = (IInputQueueResource<TickDataChunk>) getRequiredResource("input", IInputQueueResource.class);
        this.inputQueue = queue;

        this.storage = getRequiredResource("storage", IBatchStorageWrite.class);

        // Optional resources
        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) getOptionalResource("topic", ITopicWriter.class).orElse(null);
        this.batchTopic = writer;

        @SuppressWarnings("unchecked")
        IIdempotencyTracker<Long> tracker = (IIdempotencyTracker<Long>) getOptionalResource("idempotencyTracker", IIdempotencyTracker.class).orElse(null);
        this.idempotencyTracker = tracker;

        // Warn if batch topic is not configured (event-driven indexing disabled)
        if (batchTopic == null) {
            log.warn("PersistenceService initialized WITHOUT batch-topic - event-driven indexing disabled!");
        }

        // Configuration with defaults
        this.maxBatchSize = options.hasPath("maxBatchSize") ? options.getInt("maxBatchSize") : 10;
        this.batchTimeoutSeconds = options.hasPath("batchTimeoutSeconds") ? options.getInt("batchTimeoutSeconds") : 30;

        // Validation
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }
        if (batchTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("batchTimeoutSeconds must be positive");
        }

        log.debug("PersistenceService initialized: maxBatchSize={}, batchTimeout={}s, idempotency={}",
            maxBatchSize, batchTimeoutSeconds, idempotencyTracker != null ? "enabled" : "disabled");
    }

    @Override
    protected void logStarted() {
        log.info("PersistenceService started: batch=[size={}, timeout={}s], idempotency={}",
            maxBatchSize, batchTimeoutSeconds, idempotencyTracker != null ? "enabled" : "disabled");
    }

    /**
     * Main service loop: receive → stream-write → notify → commit.
     * <p>
     * Each iteration receives a batch of N serialized messages from the broker,
     * then lazily deserializes one chunk at a time during the streaming write.
     * Peak heap: N × serialized chunk + 1 × deserialized chunk. On write failure,
     * the batch is not committed and the broker redelivers messages on the next
     * {@code receiveBatch()}.
     *
     * @throws InterruptedException if the thread is interrupted (triggers graceful shutdown)
     */
    @Override
    protected void run() throws InterruptedException {
        while (!isStopRequested() && !Thread.currentThread().isInterrupted()) {
            checkPause();

            try (var batch = inputQueue.receiveBatch(maxBatchSize, batchTimeoutSeconds, TimeUnit.SECONDS)) {
                if (batch.size() == 0) {
                    currentBatchSize.set(0);
                    continue;
                }

                currentBatchSize.set(batch.size());
                log.debug("Received batch of {} chunks", batch.size());

                try {
                    // Optional: filter duplicates during iteration.
                    // On write failure, close() rolls back and the broker redelivers all N messages.
                    // Chunks are only checked (not marked) during filtering — marking happens
                    // after successful commit to ensure redelivery retries on failure.
                    List<Long> processedKeys = new ArrayList<>();
                    Iterator<TickDataChunk> chunks = maybeFilterDuplicates(batch.iterator(), processedKeys);

                    // If all chunks were filtered as duplicates, commit and move on
                    if (!chunks.hasNext()) {
                        log.debug("All chunks in batch were duplicates, skipping");
                        currentBatchSize.set(0);
                        batch.commit();
                        continue;
                    }

                    setShutdownPhase(ShutdownPhase.PROCESSING);
                    Thread.interrupted();

                    // Stream-write to storage (one chunk at a time on heap)
                    StreamingWriteResult result = storage.writeChunkBatchStreaming(chunks);

                    // Send batch notification to topic (if configured)
                    sendBatchNotification(result);

                    // ACK: broker deletes messages
                    batch.commit();

                    setShutdownPhase(ShutdownPhase.WAITING);

                    // Mark chunks as processed AFTER successful commit.
                    // This ensures failed batches are fully retried on redelivery.
                    markAllProcessed(processedKeys);

                    // Update metrics
                    batchesWritten.incrementAndGet();
                    ticksWritten.addAndGet(result.totalTickCount());
                    bytesWritten.addAndGet(result.bytesWritten());
                    currentBatchSize.set(result.chunkCount());

                    log.debug("Wrote streaming batch {} with {} chunks ({} ticks)",
                        result.path(), result.chunkCount(), result.totalTickCount());

                } catch (IOException | RuntimeException e) {
                    setShutdownPhase(ShutdownPhase.WAITING);
                    // Write or deserialization failed — don't commit. close() will rollback.
                    // Messages are redelivered by the broker on next receiveBatch().
                    // RuntimeException covers lazy deserialization failures in the streaming
                    // batch iterator (e.g., corrupt protobuf messages).
                    log.warn("{}: failed to write streaming batch: {}", serviceName, e.getMessage());
                    recordError("BATCH_WRITE_FAILED", "Streaming write failed", e.getMessage());
                    batchesFailed.incrementAndGet();
                }
            }

            Thread.yield();
        }
    }

    /**
     * Wraps the chunk iterator with a filtering iterator that skips duplicates.
     * <p>
     * If no idempotency tracker is configured, returns the original iterator unchanged.
     * The filtering iterator uses a look-ahead pattern to support {@code hasNext()}
     * after filtering. Non-duplicate keys are collected in {@code processedKeys} for
     * deferred marking after successful commit.
     *
     * @param chunks        the original chunk iterator from the streaming batch
     * @param processedKeys collects firstTick keys of non-duplicate chunks (populated during iteration)
     * @return the same iterator (if no tracker) or a filtering iterator that skips duplicates
     */
    private Iterator<TickDataChunk> maybeFilterDuplicates(Iterator<TickDataChunk> chunks, List<Long> processedKeys) {
        if (idempotencyTracker == null) {
            return chunks;
        }

        return new Iterator<>() {
            private TickDataChunk next = advance();

            private TickDataChunk advance() {
                while (chunks.hasNext()) {
                    TickDataChunk chunk = chunks.next();
                    long key = chunk.getFirstTick();
                    if (!idempotencyTracker.isProcessed(key)) {
                        processedKeys.add(key);
                        return chunk;
                    }
                    log.warn("Duplicate chunk detected: firstTick={}", key);
                    duplicateTicksDetected.incrementAndGet();
                }
                return null;
            }

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public TickDataChunk next() {
                if (next == null) {
                    throw new NoSuchElementException();
                }
                TickDataChunk current = next;
                next = advance();
                return current;
            }
        };
    }

    /**
     * Marks all collected keys as processed in the idempotency tracker.
     * <p>
     * Called after successful commit to ensure failed batches are fully retried
     * on redelivery (keys are not prematurely marked as processed).
     *
     * @param keys the firstTick keys to mark as processed
     */
    private void markAllProcessed(List<Long> keys) {
        if (idempotencyTracker == null || keys.isEmpty()) {
            return;
        }
        for (long key : keys) {
            idempotencyTracker.markProcessed(key);
        }
    }

    /**
     * Sends a batch notification to the topic if configured.
     * <p>
     * Initializes the topic with the simulation run ID on the first notification.
     * If the topic is not configured, this method is a no-op.
     *
     * @param result the streaming write result containing batch metadata
     * @throws InterruptedException if interrupted during topic send
     */
    private void sendBatchNotification(StreamingWriteResult result) throws InterruptedException {
        if (batchTopic == null) {
            return;
        }

        String simulationRunId = result.simulationRunId();
        if (!topicInitialized) {
            log.debug("Initializing topic with runId: {}", simulationRunId);
            batchTopic.setSimulationRun(simulationRunId);
            topicInitialized = true;
        }

        BatchInfo notification = BatchInfo.newBuilder()
            .setSimulationRunId(simulationRunId)
            .setStoragePath(result.path().asString())
            .setTickStart(result.firstTick())
            .setTickEnd(result.lastTick())
            .setWrittenAtMs(System.currentTimeMillis())
            .build();

        log.debug("Sending BatchInfo to topic: ticks {}-{}", result.firstTick(), result.lastTick());
        try {
            batchTopic.send(notification);
            log.debug("BatchInfo sent successfully");
            notificationsSent.incrementAndGet();
        } catch (RuntimeException e) {
            // Batch is already persisted — notification failure is transient.
            // Indexers will catch up on next successful notification or via polling.
            log.warn("{}: failed to send batch notification for ticks {}-{}: {}",
                serviceName, result.firstTick(), result.lastTick(), e.getMessage());
            recordError("NOTIFICATION_SEND_FAILED", "Failed to send batch notification", e.getMessage());
            notificationsFailed.incrementAndGet();
        }
    }

    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);

        metrics.put("batches_written", batchesWritten.get());
        metrics.put("ticks_written", ticksWritten.get());
        metrics.put("bytes_written", bytesWritten.get());
        metrics.put("batches_failed", batchesFailed.get());
        metrics.put("duplicate_ticks_detected", duplicateTicksDetected.get());
        metrics.put("current_batch_size", currentBatchSize.get());
        metrics.put("notifications_sent", notificationsSent.get());
        metrics.put("notifications_failed", notificationsFailed.get());
    }

    // ==================== IMemoryEstimatable ====================

    /**
     * {@inheritDoc}
     * <p>
     * Estimates memory for the PersistenceService streaming write.
     * <p>
     * With streaming, N serialized chunks are held on heap (from Artemis {@code reset()})
     * plus 1 deserialized chunk during iteration. This is a reduction from the previous
     * batch model (maxBatchSize × deserialized bytesPerChunk).
     * <p>
     * <strong>Calculation:</strong> maxBatchSize × serializedBytesPerChunk + 1 × deserializedBytesPerChunk
     *
     * @param params simulation parameters for size estimates
     * @return list containing one worst-case memory estimate for this service
     */
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        long serializedBytesPerChunk = params.estimateSerializedBytesPerChunk();
        long deserializedBytesPerChunk = params.estimateBytesPerChunk();

        // N serialized chunks held on heap (from reset() in ArtemisStreamingBatch)
        // + 1 deserialized chunk during iteration/write
        long totalBytes = (long) maxBatchSize * serializedBytesPerChunk + deserializedBytesPerChunk;

        String explanation = String.format("%d × %s/chunk (serialized) + 1 × %s/chunk (deserialized)",
            maxBatchSize,
            SimulationParameters.formatBytes(serializedBytesPerChunk),
            SimulationParameters.formatBytes(deserializedBytesPerChunk));

        return List.of(new MemoryEstimate(
            serviceName,
            totalBytes,
            explanation,
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}
