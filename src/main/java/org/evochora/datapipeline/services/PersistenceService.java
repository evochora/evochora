package org.evochora.datapipeline.services;

import com.google.protobuf.ByteString;
import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.SystemContracts;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.api.resources.storage.IStorageWriteResource;
import org.evochora.datapipeline.api.resources.storage.MessageWriter;
import org.evochora.datapipeline.api.services.IService.State;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
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
public class PersistenceService extends AbstractService implements IMonitorable {

    // Required resources
    private final IInputQueueResource<TickData> inputQueue;
    private final IStorageWriteResource storage;

    // Optional resources
    private final IOutputQueueResource<SystemContracts.DeadLetterMessage> dlq;
    private final IIdempotencyTracker<String> idempotencyTracker;

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

    // Error tracking
    private final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();

    public PersistenceService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);

        // Required resources
        this.inputQueue = getRequiredResource("input", IInputQueueResource.class);
        this.storage = getRequiredResource("storage", IStorageWriteResource.class);

        // Optional resources
        this.dlq = getOptionalResource("dlq", IOutputQueueResource.class).orElse(null);
        this.idempotencyTracker = getOptionalResource("idempotencyTracker", IIdempotencyTracker.class).orElse(null);

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

        log.info("PersistenceService initialized: maxBatchSize={}, batchTimeout={}s, maxRetries={}, shutdownBatchTimeout={}s, idempotency={}",
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
                    log.info("Shutdown requested, completing batch of {} ticks with {}s timeout",
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
            log.error("Batch contains tick with empty or null simulationRunId");
            errors.add(new OperationalError(
                Instant.now(),
                "INVALID_SIMULATION_RUN_ID",
                "Batch contains tick with empty or null simulationRunId",
                String.format("Batch size: %d", batch.size())
            ));
            sendToDLQ(batch, "Empty or null simulationRunId", 0,
                new IllegalStateException("Empty or null simulationRunId"));
            batchesFailed.incrementAndGet();
            return;
        }

        String lastSimRunId = batch.get(batch.size() - 1).getSimulationRunId();

        if (!firstSimRunId.equals(lastSimRunId)) {
            log.error("Batch consistency violation: first tick simulationRunId='{}', last tick simulationRunId='{}'",
                firstSimRunId, lastSimRunId);
            errors.add(new OperationalError(
                Instant.now(),
                "BATCH_CONSISTENCY_VIOLATION",
                "Batch contains mixed simulationRunIds",
                String.format("First: %s, Last: %s, Batch size: %d", firstSimRunId, lastSimRunId, batch.size())
            ));
            sendToDLQ(batch, "Batch contains mixed simulationRunIds", 0,
                new IllegalStateException("Mixed simulationRunIds"));
            batchesFailed.incrementAndGet();
            return;
        }

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

        // Generate storage key
        String simulationRunId = dedupedBatch.get(0).getSimulationRunId();
        long startTick = dedupedBatch.get(0).getTickNumber();
        long endTick = dedupedBatch.get(dedupedBatch.size() - 1).getTickNumber();
        String filename = String.format("batch_%019d_%019d.pb", startTick, endTick);
        String key = simulationRunId + "/" + filename;

        // Write batch with retry logic
        writeBatchWithRetry(key, dedupedBatch);
    }

    private List<TickData> deduplicateBatch(List<TickData> batch) {
        List<TickData> deduped = new ArrayList<>(batch.size());

        for (TickData tick : batch) {
            String idempotencyKey = tick.getSimulationRunId() + ":" + tick.getTickNumber();

            // Atomic check-and-mark operation to prevent race conditions
            if (!idempotencyTracker.checkAndMarkProcessed(idempotencyKey)) {
                // Returns false = already processed
                log.warn("Duplicate tick detected: {}", idempotencyKey);
                duplicateTicksDetected.incrementAndGet();
                continue; // Skip duplicate
            }

            // Returns true = newly marked, safe to add
            deduped.add(tick);
        }

        return deduped;
    }

    private void writeBatchWithRetry(String key, List<TickData> batch) {
        int attempt = 0;
        int backoff = retryBackoffMs;
        Exception lastException = null;

        while (attempt <= maxRetries) {
            try {
                writeBatch(key, batch);

                // Success
                batchesWritten.incrementAndGet();
                ticksWritten.addAndGet(batch.size());
                log.debug("Successfully wrote batch {} with {} ticks", key, batch.size());
                return;

            } catch (IOException e) {
                lastException = e;
                attempt++;

                if (attempt <= maxRetries) {
                    log.warn("Failed to write batch {} (attempt {}/{}): {}, retrying in {}ms",
                        key, attempt, maxRetries, e.getMessage(), backoff);

                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.info("Interrupted during retry backoff, aborting retries");
                        break;
                    }

                    // Exponential backoff with cap to prevent overflow
                    backoff = Math.min(backoff * 2, 60000); // Max 60 seconds
                } else {
                    log.error("Failed to write batch {} after {} retries: {}", key, maxRetries, e.getMessage());
                }
            }
        }

        // All retries exhausted - record error
        String errorDetails = String.format("Batch: %s, Retries: %d, Exception: %s",
            key, maxRetries, lastException != null ? lastException.getMessage() : "Unknown");
        errors.add(new OperationalError(
            Instant.now(),
            "BATCH_WRITE_FAILED",
            "Failed to write batch after all retries",
            errorDetails
        ));
        sendToDLQ(batch, lastException != null ? lastException.getMessage() : "Unknown error", attempt - 1, lastException);
        batchesFailed.incrementAndGet();
    }

    private void writeBatch(String key, List<TickData> batch) throws IOException {
        long bytesInBatch = 0;

        try (MessageWriter writer = storage.openWriter(key)) {
            for (TickData tick : batch) {
                writer.writeMessage(tick);
                bytesInBatch += tick.getSerializedSize();
            }
        }

        bytesWritten.addAndGet(bytesInBatch);
    }

    private void sendToDLQ(List<TickData> batch, String errorMessage, int retryAttempts, Exception exception) {
        if (dlq == null) {
            log.error("Failed batch has no DLQ configured, data will be lost: {} ticks", batch.size());
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
                log.error("DLQ is full, failed batch lost: {} ticks", batch.size());
                errors.add(new OperationalError(
                    Instant.now(),
                    "DLQ_FULL",
                    "Dead letter queue is full, failed batch lost",
                    String.format("Batch size: %d ticks, SimulationRunId: %s, StartTick: %d", batch.size(), simulationRunId, startTick)
                ));
            }

        } catch (Exception e) {
            log.error("Failed to send batch to DLQ", e);
            errors.add(new OperationalError(
                Instant.now(),
                "DLQ_SEND_FAILED",
                "Failed to send batch to dead letter queue",
                String.format("Batch size: %d ticks, Exception: %s", batch.size(), e.getMessage())
            ));
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
                log.error("Failed to complete shutdown batch of {} ticks", batch.size(), ex);
            }
        });

        try {
            future.get(shutdownBatchTimeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("Shutdown batch write exceeded {}s timeout, {} ticks may be lost (tmp file remains)",
                shutdownBatchTimeoutSeconds, batch.size());
            future.cancel(true);
            errors.add(new OperationalError(
                Instant.now(),
                "SHUTDOWN_BATCH_TIMEOUT",
                "Shutdown batch write exceeded timeout",
                String.format("Timeout: %ds, Batch size: %d ticks", shutdownBatchTimeoutSeconds, batch.size())
            ));
        } catch (java.util.concurrent.ExecutionException e) {
            log.error("Error during shutdown batch completion", e.getCause());
            errors.add(new OperationalError(
                Instant.now(),
                "SHUTDOWN_BATCH_ERROR",
                "Error during shutdown batch write",
                String.format("Batch size: %d ticks, Exception: %s", batch.size(), e.getCause().getMessage())
            ));
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for shutdown batch completion");
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
    public Map<String, Number> getMetrics() {
        return Map.of(
            "batches_written", batchesWritten.get(),
            "ticks_written", ticksWritten.get(),
            "bytes_written", bytesWritten.get(),
            "batches_failed", batchesFailed.get(),
            "duplicate_ticks_detected", duplicateTicksDetected.get(),
            "current_batch_size", currentBatchSize.get()
        );
    }

    @Override
    public boolean isHealthy() {
        return getCurrentState() != State.ERROR;
    }

    @Override
    public List<OperationalError> getErrors() {
        return new ArrayList<>(errors);
    }

    @Override
    public void clearErrors() {
        errors.clear();
    }
}