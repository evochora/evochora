package org.evochora.datapipeline.services;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.contracts.SystemContracts;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;

import com.google.protobuf.ByteString;
import com.typesafe.config.Config;

/**
 * Service that drains TickDataChunk batches from queues and persists them to storage.
 * <p>
 * PersistenceService provides reliable batch persistence with:
 * <ul>
 *   <li>Configurable batch size and timeout for flexible throughput/latency trade-offs</li>
 *   <li>Optional idempotency tracking to detect duplicate ticks (bug detection)</li>
 *   <li>Retry logic with exponential backoff for transient failures</li>
 *   <li>Dead letter queue support for unrecoverable failures</li>
 *   <li>Graceful shutdown that persists partial batches without data loss</li>
 * </ul>
 * <p>
 * Multiple instances can run concurrently as competing consumers on the same queue.
 * All instances should share the same idempotencyTracker and dlq resources.
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
    private final IOutputQueueResource<SystemContracts.DeadLetterMessage> dlq;
    private final IIdempotencyTracker<Long> idempotencyTracker;

    // Configuration
    private final int maxBatchSize;
    private final int batchTimeoutSeconds;
    private final int maxRetries;
    private final int retryBackoffMs;

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
    
    // Track current batch for shutdown cleanup in finally-block
    private List<TickDataChunk> currentBatch = null;

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
        IOutputQueueResource<SystemContracts.DeadLetterMessage> dlqResource = (IOutputQueueResource<SystemContracts.DeadLetterMessage>) getOptionalResource("dlq", IOutputQueueResource.class).orElse(null);
        this.dlq = dlqResource;

        @SuppressWarnings("unchecked")
        IIdempotencyTracker<Long> tracker = (IIdempotencyTracker<Long>) getOptionalResource("idempotencyTracker", IIdempotencyTracker.class).orElse(null);
        this.idempotencyTracker = tracker;
        
        // Warn if batch topic is not configured (event-driven indexing disabled)
        if (batchTopic == null) {
            log.warn("PersistenceService initialized WITHOUT batch-topic - event-driven indexing disabled!");
        }

        // Configuration with defaults
        this.maxBatchSize = options.hasPath("maxBatchSize") ? options.getInt("maxBatchSize") : 20;
        this.batchTimeoutSeconds = options.hasPath("batchTimeoutSeconds") ? options.getInt("batchTimeoutSeconds") : 30;
        this.maxRetries = options.hasPath("maxRetries") ? options.getInt("maxRetries") : 3;
        this.retryBackoffMs = options.hasPath("retryBackoffMs") ? options.getInt("retryBackoffMs") : 1000;

        // Validation
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }
        if (batchTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("batchTimeoutSeconds must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries cannot be negative");
        }
        if (retryBackoffMs < 0) {
            throw new IllegalArgumentException("retryBackoffMs cannot be negative");
        }

        log.debug("PersistenceService initialized: maxBatchSize={}, batchTimeout={}s, maxRetries={}, idempotency={}",
            maxBatchSize, batchTimeoutSeconds, maxRetries, idempotencyTracker != null ? "enabled" : "disabled");
    }

    @Override
    protected void logStarted() {
        log.info("PersistenceService started: batch=[size={}, timeout={}s], retry=[max={}, backoff={}ms], dlq={}, idempotency={}",
            maxBatchSize, batchTimeoutSeconds, maxRetries, retryBackoffMs,
            dlq != null ? "configured" : "none", idempotencyTracker != null ? "enabled" : "disabled");
    }

    @Override
    protected void run() throws InterruptedException {
        try {
            // Check both isStopRequested() (graceful) and isInterrupted() (forced)
            while (!isStopRequested() && !Thread.currentThread().isInterrupted()) {
                checkPause();

                currentBatch = new ArrayList<>();

                try {
                    // Drain batch from queue with timeout
                    int count = inputQueue.drainTo(currentBatch, maxBatchSize, batchTimeoutSeconds, TimeUnit.SECONDS);
                    currentBatchSize.set(count); // Always update, even if zero

                    if (count == 0) {
                        currentBatch = null;  // No batch to clean up
                        continue; // No data available, loop again
                    }

                    log.debug("Drained {} chunks from queue", count);

                    // Process and persist batch
                    processBatch(currentBatch);
                    currentBatch = null;  // Successfully processed
                    
                } catch (InterruptedException e) {
                    // Keep currentBatch for finally-block (even if partially filled by drainTo)
                    // drainTo() may have transferred data to collection before interruption!
                    throw e;  // Re-throw to exit loop
                }
            }
        } finally {
            // Final batch completion (if interrupted during processing)
            if (currentBatch != null && !currentBatch.isEmpty()) {
                log.info("Shutdown: Completing batch of {} chunks", currentBatch.size());
                
                // Clear interrupt flag to allow DB operations
                boolean wasInterrupted = Thread.interrupted();
                try {
                    processBatch(currentBatch);
                } catch (Exception e) {
                    log.warn("Failed to complete shutdown batch of {} chunks", currentBatch.size());
                } finally {
                    if (wasInterrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private void processBatch(List<TickDataChunk> batch) {
        if (batch.isEmpty()) {
            return;
        }

        // Validate batch consistency (first and last chunk must have same simulationRunId)
        String firstSimRunId = batch.get(0).getSimulationRunId();

        // Check for empty or null simulationRunId
        if (firstSimRunId == null || firstSimRunId.isEmpty()) {
            log.warn("Batch contains chunk with empty or null simulationRunId, sending to DLQ");
            recordError(
                "INVALID_SIMULATION_RUN_ID",
                "Batch contains chunk with empty or null simulationRunId",
                String.format("Batch size: %d chunks", batch.size())
            );
            sendToDLQ(batch, "Empty or null simulationRunId", 0,
                new IllegalStateException("Empty or null simulationRunId"));
            batchesFailed.incrementAndGet();
            return;
        }

        String lastSimRunId = batch.get(batch.size() - 1).getSimulationRunId();

        if (!firstSimRunId.equals(lastSimRunId)) {
            log.warn("Batch consistency violation: first='{}', last='{}', sending to DLQ",
                firstSimRunId, lastSimRunId);
            recordError(
                "BATCH_CONSISTENCY_VIOLATION",
                "Batch contains mixed simulationRunIds",
                String.format("First: %s, Last: %s, Batch size: %d chunks", firstSimRunId, lastSimRunId, batch.size())
            );
            sendToDLQ(batch, "Batch contains mixed simulationRunIds", 0,
                new IllegalStateException("Mixed simulationRunIds"));
            batchesFailed.incrementAndGet();
            return;
        }

        // Extract tick range from batch (before deduplication)
        // This determines the folder and filename, so must reflect the original range
        long startTick = batch.get(0).getFirstTick();
        long endTick = batch.get(batch.size() - 1).getLastTick();

        // Optional: Check for duplicate chunks (bug detection)
        List<TickDataChunk> dedupedBatch = batch;
        if (idempotencyTracker != null) {
            int originalSize = batch.size();
            dedupedBatch = deduplicateBatch(batch);
            int dedupedSize = dedupedBatch.size();
            int duplicatesRemoved = originalSize - dedupedSize;

            if (dedupedBatch.isEmpty()) {
                log.warn("[{}] Entire batch was duplicates ({} chunks), skipping", serviceName, originalSize);
                return;
            }

            if (duplicatesRemoved > 0) {
                long firstTick = dedupedBatch.get(0).getFirstTick();
                long lastTick = dedupedBatch.get(dedupedBatch.size() - 1).getLastTick();
                log.warn("[{}] Removed {} duplicate chunks, {} unique chunks remain: range [{}-{}]",
                    serviceName, duplicatesRemoved, dedupedSize, firstTick, lastTick);
            }
        }

        // Write batch with retry logic (storage handles folders, filenames, compression)
        writeBatchWithRetry(dedupedBatch, startTick, endTick);
    }

    private List<TickDataChunk> deduplicateBatch(List<TickDataChunk> batch) {
        List<TickDataChunk> deduped = new ArrayList<>(batch.size());

        for (TickDataChunk chunk : batch) {
            // Use firstTick as key - each chunk has a unique firstTick
            // and the tracker is per-service-instance (never shared across runs)
            long idempotencyKey = chunk.getFirstTick();

            // Atomic check-and-mark operation to prevent race conditions
            if (!idempotencyTracker.checkAndMarkProcessed(idempotencyKey)) {
                // Returns false = already processed
                log.warn("Duplicate chunk detected: firstTick={}", idempotencyKey);
                duplicateTicksDetected.incrementAndGet();
                continue; // Skip duplicate
            }

            // Returns true = newly marked, safe to add
            deduped.add(chunk);
        }

        return deduped;
    }

    /**
     * Writes a batch of chunks to storage with retry logic and sends a notification to the batch topic.
     * <p>
     * Both storage write and topic notification must succeed for the operation to complete.
     * If either fails, the entire operation is retried (including both storage write and notification).
     * <p>
     * <strong>Thread Safety:</strong> This method is called from the service's run loop thread.
     *
     * @param batch The batch of chunks to write (must not be empty).
     * @param firstTick The first tick number in the batch.
     * @param lastTick The last tick number in the batch.
     */
    private void writeBatchWithRetry(List<TickDataChunk> batch, long firstTick, long lastTick) {
        int attempt = 0;
        int backoff = retryBackoffMs;
        Exception lastException = null;

        while (attempt <= maxRetries) {
            try {
                // Storage handles everything: folders, compression, manifests
                StoragePath storagePath = storage.writeChunkBatch(batch, firstTick, lastTick);

                // Send notification to topic (if configured)
                if (batchTopic != null) {
                    // Initialize topic with simulation run ID on first batch
                    String simulationRunId = batch.get(0).getSimulationRunId();
                    if (!topicInitialized) {
                        batchTopic.setSimulationRun(simulationRunId);
                        topicInitialized = true;
                    }

                    // Send notification to topic (must succeed for operation to complete)
                    BatchInfo notification = BatchInfo.newBuilder()
                        .setSimulationRunId(simulationRunId)
                        .setStoragePath(storagePath.asString())
                        .setTickStart(firstTick)
                        .setTickEnd(lastTick)
                        .setWrittenAtMs(System.currentTimeMillis())
                        .build();
                    
                    batchTopic.send(notification);
                    notificationsSent.incrementAndGet();
                }

                // Success - update metrics
                batchesWritten.incrementAndGet();
                // Count total ticks in all chunks
                int totalTicks = batch.stream().mapToInt(TickDataChunk::getTickCount).sum();
                ticksWritten.addAndGet(totalTicks);

                // Calculate uncompressed bytes for metrics
                long bytesInBatch = batch.stream()
                    .mapToLong(TickDataChunk::getSerializedSize)
                    .sum();
                bytesWritten.addAndGet(bytesInBatch);

                log.debug("Successfully wrote batch {} with {} chunks ({} ticks) and sent notification", 
                    storagePath, batch.size(), totalTicks);
                return;

            } catch (IOException | InterruptedException e) {
                lastException = e;
                
                // Handle interruption immediately (topic send failed)
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    log.debug("Interrupted during batch write or notification, aborting retries");
                    notificationsFailed.incrementAndGet();
                    break;
                }
                
                // IOException - storage write failed (topic was never called)
                attempt++;

                if (attempt <= maxRetries) {
                    log.debug("Failed to write batch or send notification [ticks {}-{}] (attempt {}/{}): {}, retrying in {}ms",
                        firstTick, lastTick, attempt, maxRetries, e.getMessage(), backoff);

                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.debug("Interrupted during retry backoff, aborting retries");
                        break;
                    }

                    // Exponential backoff with cap to prevent overflow
                    backoff = Math.min(backoff * 2, 60000); // Max 60 seconds
                } else {
                    log.warn("Failed to write batch or send notification [ticks {}-{}] after {} retries, sending to DLQ",
                        firstTick, lastTick, maxRetries);
                }
            }
        }

        // All retries exhausted - record error
        String errorDetails = String.format("Batch: [ticks %d-%d], Retries: %d, Exception: %s",
            firstTick, lastTick, maxRetries, lastException != null ? lastException.getMessage() : "Unknown");
        recordError(
            "BATCH_WRITE_OR_NOTIFY_FAILED",
            "Failed to write batch or send notification after all retries",
            errorDetails
        );
        sendToDLQ(batch, lastException != null ? lastException.getMessage() : "Unknown error", attempt - 1, lastException);
        batchesFailed.incrementAndGet();
    }

    private void sendToDLQ(List<TickDataChunk> batch, String errorMessage, int retryAttempts, Exception exception) {
        if (dlq == null) {
            log.warn("Failed batch has no DLQ configured, data will be lost: {} chunks", batch.size());
            recordError(
                "DLQ_NOT_CONFIGURED",
                "Failed batch lost - no DLQ configured",
                String.format("Batch size: %d chunks", batch.size())
            );
            return;
        }

        try {
            // Serialize batch to bytes
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            for (TickDataChunk chunk : batch) {
                chunk.writeDelimitedTo(bos);
            }

            // Extract batch metadata
            String simulationRunId = batch.get(0).getSimulationRunId();
            long startTick = batch.get(0).getFirstTick();
            long endTick = batch.get(batch.size() - 1).getLastTick();
            int totalTicks = batch.stream().mapToInt(TickDataChunk::getTickCount).sum();
            String storageKey = String.format("%s/raw/batch_%019d_%019d.pb", simulationRunId, startTick, endTick);

            // Build stack trace
            List<String> stackTraceLines = new ArrayList<>();
            if (exception != null) {
                for (StackTraceElement element : exception.getStackTrace()) {
                    stackTraceLines.add(element.toString());
                    if (stackTraceLines.size() >= 10) break; // Limit stack trace size
                }
            }

            // Get source queue name from IResource interface
            String sourceQueue = inputQueue.getResourceName();

            SystemContracts.DeadLetterMessage dlqMessage = SystemContracts.DeadLetterMessage.newBuilder()
                .setOriginalMessage(ByteString.copyFrom(bos.toByteArray()))
                .setMessageType("List<TickDataChunk>")
                .setFirstFailureTimeMs(System.currentTimeMillis())
                .setLastFailureTimeMs(System.currentTimeMillis())
                .setRetryCount(retryAttempts)
                .setFailureReason(errorMessage)
                .setSourceService(serviceName)
                .setSourceQueue(sourceQueue)
                .addAllStackTraceLines(stackTraceLines)
                .putMetadata("simulationRunId", simulationRunId)
                .putMetadata("startTick", String.valueOf(startTick))
                .putMetadata("endTick", String.valueOf(endTick))
                .putMetadata("chunkCount", String.valueOf(batch.size()))
                .putMetadata("tickCount", String.valueOf(totalTicks))
                .putMetadata("storageKey", storageKey)
                .putMetadata("exceptionType", exception != null ? exception.getClass().getName() : "Unknown")
                .build();

            if (dlq.offer(dlqMessage)) {
                log.info("Sent failed batch to DLQ: {} chunks ({} ticks) ({}:{})", 
                    batch.size(), totalTicks, simulationRunId, startTick);
            } else {
                log.warn("DLQ is full, failed batch lost: {} chunks", batch.size());
                recordError(
                    "DLQ_FULL",
                    "Dead letter queue is full, failed batch lost",
                    String.format("Batch size: %d chunks, SimulationRunId: %s, StartTick: %d", batch.size(), simulationRunId, startTick)
                );
            }

        } catch (Exception e) {
            log.warn("Failed to send batch to DLQ");
            recordError(
                "DLQ_SEND_FAILED",
                "Failed to send batch to dead letter queue",
                String.format("Batch size: %d chunks, Exception: %s", batch.size(), e.getMessage())
            );
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
     * Estimates memory for the PersistenceService batch buffer at worst-case.
     * <p>
     * <strong>Calculation:</strong> maxBatchSize × bytesPerTick (100% occupancy)
     * <p>
     * The batch is held in memory during draining from queue until written to storage.
     * With streaming serialization, only the List&lt;TickDataChunk&gt; is held - no additional
     * byte array copies are created during write.
     */
    /**
     * {@inheritDoc}
     * <p>
     * Estimates memory for the PersistenceService batch buffer.
     * <p>
     * <strong>Important:</strong> After delta compression, maxBatchSize is in CHUNKS (not ticks).
     * Each chunk contains a full snapshot plus deltas, so memory is calculated as:
     * <ul>
     *   <li>Chunks in batch: maxBatchSize × bytesPerChunk</li>
     *   <li>Each chunk: 1 snapshot + (samplesPerChunk - 1) deltas</li>
     * </ul>
     */
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // Each chunk in the batch contains a snapshot + deltas
        long bytesPerChunk = params.estimateBytesPerChunk();
        long totalBytes = (long) maxBatchSize * bytesPerChunk;

        // Add ArrayList overhead (~24 bytes per reference + array backing)
        long arrayListOverhead = (long) maxBatchSize * 8 + 64;
        totalBytes += arrayListOverhead;

        String explanation = String.format("%d maxBatchSize (chunks) × %s/chunk (%d ticks/chunk, 100%% environment + %d organisms)",
            maxBatchSize,
            SimulationParameters.formatBytes(bytesPerChunk),
            params.ticksPerChunk(),
            params.maxOrganisms());

        return List.of(new MemoryEstimate(
            serviceName,
            totalBytes,
            explanation,
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}