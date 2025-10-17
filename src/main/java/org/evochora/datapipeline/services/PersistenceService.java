package org.evochora.datapipeline.services;

import com.google.protobuf.ByteString;
import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.contracts.SystemContracts;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service that drains TickData batches from queues and persists them to storage.
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
public class PersistenceService extends AbstractService {

    // Required resources
    private final IInputQueueResource<TickData> inputQueue;
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
    private final int shutdownBatchTimeoutSeconds;

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

    public PersistenceService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);

        // Required resources
        this.inputQueue = getRequiredResource("input", IInputQueueResource.class);
        this.storage = getRequiredResource("storage", IBatchStorageWrite.class);

        // Optional resources
        this.batchTopic = getOptionalResource("topic", ITopicWriter.class).orElse(null);
        this.dlq = getOptionalResource("dlq", IOutputQueueResource.class).orElse(null);
        this.idempotencyTracker = getOptionalResource("idempotencyTracker", IIdempotencyTracker.class).orElse(null);
        
        // Warn if batch topic is not configured (event-driven indexing disabled)
        if (batchTopic == null) {
            log.warn("PersistenceService initialized WITHOUT batch-topic - event-driven indexing disabled!");
        }

        // Configuration with defaults
        this.maxBatchSize = options.hasPath("maxBatchSize") ? options.getInt("maxBatchSize") : 1000;
        this.batchTimeoutSeconds = options.hasPath("batchTimeoutSeconds") ? options.getInt("batchTimeoutSeconds") : 5;
        this.maxRetries = options.hasPath("maxRetries") ? options.getInt("maxRetries") : 3;
        this.retryBackoffMs = options.hasPath("retryBackoffMs") ? options.getInt("retryBackoffMs") : 1000;
        this.shutdownBatchTimeoutSeconds = options.hasPath("shutdownBatchTimeoutSeconds") ? options.getInt("shutdownBatchTimeoutSeconds") : 15;

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
        if (shutdownBatchTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("shutdownBatchTimeoutSeconds must be positive");
        }

        log.debug("PersistenceService initialized: maxBatchSize={}, batchTimeout={}s, maxRetries={}, shutdownBatchTimeout={}s, idempotency={}",
            maxBatchSize, batchTimeoutSeconds, maxRetries, shutdownBatchTimeoutSeconds, idempotencyTracker != null ? "enabled" : "disabled");
    }

    @Override
    protected void logStarted() {
        log.info("PersistenceService started: batch=[size={}, timeout={}s], retry=[max={}, backoff={}ms], dlq={}, idempotency={}",
            maxBatchSize, batchTimeoutSeconds, maxRetries, retryBackoffMs,
            dlq != null ? "configured" : "none", idempotencyTracker != null ? "enabled" : "disabled");
    }

    @Override
    protected void run() throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            checkPause();

            List<TickData> batch = new ArrayList<>();

            try {
                // Drain batch from queue with timeout
                int count = inputQueue.drainTo(batch, maxBatchSize, batchTimeoutSeconds, TimeUnit.SECONDS);
                currentBatchSize.set(count); // Always update, even if zero

                if (count == 0) {
                    continue; // No data available, loop again
                }

                log.debug("Drained {} ticks from queue", count);

                // Process and persist batch
                processBatch(batch);

            } catch (InterruptedException e) {
                // Shutdown signal received during drain
                if (!batch.isEmpty()) {
                    log.debug("Interrupted during drain, completing batch of {} ticks with {}s timeout",
                        batch.size(), shutdownBatchTimeoutSeconds);
                    completeShutdownBatchWithTimeout(batch);
                }
                throw e; // Re-throw to signal clean shutdown
            }
        }
    }

    private void processBatch(List<TickData> batch) {
        if (batch.isEmpty()) {
            return;
        }

        // Validate batch consistency (first and last tick must have same simulationRunId)
        String firstSimRunId = batch.get(0).getSimulationRunId();

        // Check for empty or null simulationRunId
        if (firstSimRunId == null || firstSimRunId.isEmpty()) {
            log.warn("Batch contains tick with empty or null simulationRunId, sending to DLQ");
            recordError(
                "INVALID_SIMULATION_RUN_ID",
                "Batch contains tick with empty or null simulationRunId",
                String.format("Batch size: %d", batch.size())
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
                String.format("First: %s, Last: %s, Batch size: %d", firstSimRunId, lastSimRunId, batch.size())
            );
            sendToDLQ(batch, "Batch contains mixed simulationRunIds", 0,
                new IllegalStateException("Mixed simulationRunIds"));
            batchesFailed.incrementAndGet();
            return;
        }

        // Extract tick range from original batch (before deduplication)
        // This determines the folder and filename, so must reflect the original range
        long startTick = batch.get(0).getTickNumber();
        long endTick = batch.get(batch.size() - 1).getTickNumber();

        // Optional: Check for duplicate ticks (bug detection)
        List<TickData> dedupedBatch = batch;
        if (idempotencyTracker != null) {
            int originalSize = batch.size();
            dedupedBatch = deduplicateBatch(batch);
            int dedupedSize = dedupedBatch.size();
            int duplicatesRemoved = originalSize - dedupedSize;

            if (dedupedBatch.isEmpty()) {
                log.warn("[{}] Entire batch was duplicates ({} ticks), skipping", serviceName, originalSize);
                return;
            }

            if (duplicatesRemoved > 0) {
                long firstTick = dedupedBatch.get(0).getTickNumber();
                long lastTick = dedupedBatch.get(dedupedBatch.size() - 1).getTickNumber();
                log.warn("[{}] Removed {} duplicate ticks, {} unique ticks remain: range [{}-{}]",
                    serviceName, duplicatesRemoved, dedupedSize, firstTick, lastTick);
            }
        }

        // Write batch with retry logic (storage handles folders, filenames, compression)
        writeBatchWithRetry(dedupedBatch, startTick, endTick);
    }

    private List<TickData> deduplicateBatch(List<TickData> batch) {
        List<TickData> deduped = new ArrayList<>(batch.size());

        for (TickData tick : batch) {
            // Use only tickNumber as key - simulationRunId is constant within a run
            // and the tracker is per-service-instance (never shared across runs)
            long idempotencyKey = tick.getTickNumber();

            // Atomic check-and-mark operation to prevent race conditions
            if (!idempotencyTracker.checkAndMarkProcessed(idempotencyKey)) {
                // Returns false = already processed
                log.warn("Duplicate tick detected: tick={}", idempotencyKey);
                duplicateTicksDetected.incrementAndGet();
                continue; // Skip duplicate
            }

            // Returns true = newly marked, safe to add
            deduped.add(tick);
        }

        return deduped;
    }

    /**
     * Writes a batch to storage with retry logic and sends a notification to the batch topic.
     * <p>
     * Both storage write and topic notification must succeed for the operation to complete.
     * If either fails, the entire operation is retried (including both storage write and notification).
     * <p>
     * <strong>Thread Safety:</strong> This method is called from the service's run loop thread.
     *
     * @param batch The batch of ticks to write (must not be empty).
     * @param firstTick The first tick number in the original batch.
     * @param lastTick The last tick number in the original batch.
     */
    private void writeBatchWithRetry(List<TickData> batch, long firstTick, long lastTick) {
        int attempt = 0;
        int backoff = retryBackoffMs;
        Exception lastException = null;

        while (attempt <= maxRetries) {
            try {
                // Storage handles everything: folders, compression, manifests
                String storageKey = storage.writeBatch(batch, firstTick, lastTick);

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
                        .setStorageKey(storageKey)
                        .setTickStart(firstTick)
                        .setTickEnd(lastTick)
                        .setWrittenAtMs(System.currentTimeMillis())
                        .build();
                    
                    batchTopic.send(notification);
                    notificationsSent.incrementAndGet();
                }

                // Success - update metrics
                batchesWritten.incrementAndGet();
                ticksWritten.addAndGet(batch.size());

                // Calculate uncompressed bytes for metrics
                long bytesInBatch = batch.stream()
                    .mapToLong(TickData::getSerializedSize)
                    .sum();
                bytesWritten.addAndGet(bytesInBatch);

                log.debug("Successfully wrote batch {} with {} ticks and sent notification", storageKey, batch.size());
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

    private void sendToDLQ(List<TickData> batch, String errorMessage, int retryAttempts, Exception exception) {
        if (dlq == null) {
            log.warn("Failed batch has no DLQ configured, data will be lost: {} ticks", batch.size());
            recordError(
                "DLQ_NOT_CONFIGURED",
                "Failed batch lost - no DLQ configured",
                String.format("Batch size: %d ticks", batch.size())
            );
            return;
        }

        try {
            // Serialize batch to bytes
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            for (TickData tick : batch) {
                tick.writeDelimitedTo(bos);
            }

            // Extract batch metadata
            String simulationRunId = batch.get(0).getSimulationRunId();
            long startTick = batch.get(0).getTickNumber();
            long endTick = batch.get(batch.size() - 1).getTickNumber();
            String storageKey = String.format("%s/batch_%019d_%019d.pb", simulationRunId, startTick, endTick);

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
                .setMessageType("List<TickData>")
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
                .putMetadata("tickCount", String.valueOf(batch.size()))
                .putMetadata("storageKey", storageKey)
                .putMetadata("exceptionType", exception != null ? exception.getClass().getName() : "Unknown")
                .build();

            if (dlq.offer(dlqMessage)) {
                log.info("Sent failed batch to DLQ: {} ticks ({}:{})", batch.size(), simulationRunId, startTick);
            } else {
                log.warn("DLQ is full, failed batch lost: {} ticks", batch.size());
                recordError(
                    "DLQ_FULL",
                    "Dead letter queue is full, failed batch lost",
                    String.format("Batch size: %d ticks, SimulationRunId: %s, StartTick: %d", batch.size(), simulationRunId, startTick)
                );
            }

        } catch (Exception e) {
            log.warn("Failed to send batch to DLQ");
            recordError(
                "DLQ_SEND_FAILED",
                "Failed to send batch to dead letter queue",
                String.format("Batch size: %d ticks, Exception: %s", batch.size(), e.getMessage())
            );
        }
    }

    /**
     * Completes shutdown batch write with a bounded timeout to prevent hanging shutdown.
     * <p>
     * This method temporarily clears the interrupt flag to allow the batch write to complete,
     * then restores it afterward. If the write exceeds the timeout, the batch may be lost
     * but the .tmp file will remain as evidence.
     * <p>
     * This bounded approach is critical for spot instances where shutdown must complete
     * within 2 minutes or the instance is forcefully terminated.
     *
     * @param batch the batch of ticks to persist during shutdown
     */
    private void completeShutdownBatchWithTimeout(List<TickData> batch) {
        // Clear interrupt flag temporarily to allow write to complete
        boolean wasInterrupted = Thread.interrupted();

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<?> future = executor.submit(() -> {
            try {
                processBatch(batch);
                log.info("Successfully completed shutdown batch of {} ticks", batch.size());
            } catch (Exception ex) {
                log.warn("Failed to complete shutdown batch of {} ticks", batch.size());
            }
        });

        try {
            future.get(shutdownBatchTimeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("Shutdown batch write exceeded {}s timeout, {} ticks may be lost (tmp file remains)",
                shutdownBatchTimeoutSeconds, batch.size());
            future.cancel(true);
            recordError(
                "SHUTDOWN_BATCH_TIMEOUT",
                "Shutdown batch write exceeded timeout",
                String.format("Timeout: %ds, Batch size: %d ticks", shutdownBatchTimeoutSeconds, batch.size())
            );
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("Error during shutdown batch completion");
            recordError(
                "SHUTDOWN_BATCH_ERROR",
                "Error during shutdown batch write",
                String.format("Batch size: %d ticks, Exception: %s", batch.size(), e.getCause() != null ? e.getCause().getMessage() : "Unknown")
            );
        } catch (InterruptedException e) {
            log.debug("Interrupted while waiting for shutdown batch completion");
            future.cancel(true);
        } finally {
            executor.shutdownNow();
            // Restore interrupt flag
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
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
}