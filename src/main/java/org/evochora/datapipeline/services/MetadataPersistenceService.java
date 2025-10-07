package org.evochora.datapipeline.services;

import com.google.protobuf.ByteString;
import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.SystemContracts;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.api.resources.storage.IStorageWriteResource;
import org.evochora.datapipeline.api.resources.storage.MessageWriter;
import org.evochora.datapipeline.api.services.IService.State;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One-shot service that persists a single SimulationMetadata message to storage.
 * <p>
 * MetadataPersistenceService follows the one-shot pattern:
 * <ul>
 *   <li>Starts at simulation initialization</li>
 *   <li>Blocks waiting for the single metadata message from SimulationEngine</li>
 *   <li>Persists message to storage with retry logic and DLQ support</li>
 *   <li>Stops itself after successful processing (thread terminates)</li>
 * </ul>
 * <p>
 * The service provides reliable metadata persistence with:
 * <ul>
 *   <li>Retry logic with exponential backoff for transient failures</li>
 *   <li>Dead letter queue support for unrecoverable failures</li>
 *   <li>Graceful shutdown handling</li>
 * </ul>
 * <p>
 * <strong>Storage Pattern:</strong> Writes metadata to <code>{simulationRunId}/metadata.pb</code>
 * in the same directory as tick data batches for easy correlation.
 * <p>
 * <strong>Thread Safety:</strong> Runs in its own thread. No synchronization needed.
 * Service terminates after processing the single message.
 * <p>
 * <strong>Configuration Example:</strong>
 * <pre>
 * metadata-persistence-service {
 *   className = "org.evochora.datapipeline.services.MetadataPersistenceService"
 *   resources {
 *     input = "queue-in:context-data"
 *     storage = "storage-write:tick-storage"
 *     dlq = "queue-out:persistence-dlq"
 *   }
 *   options {
 *     maxRetries = 3
 *     retryBackoffMs = 1000
 *   }
 * }
 * </pre>
 */
public class MetadataPersistenceService extends AbstractService implements IMonitorable {

    // Required resources
    private final IInputQueueResource<SimulationMetadata> inputQueue;
    private final IStorageWriteResource storage;

    // Optional resources
    private final IOutputQueueResource<SystemContracts.DeadLetterMessage> dlq;

    // Configuration
    private final int maxRetries;
    private final int retryBackoffMs;

    // Metrics
    private final AtomicLong metadataWritten = new AtomicLong(0);
    private final AtomicLong metadataFailed = new AtomicLong(0);
    private final AtomicLong bytesWritten = new AtomicLong(0);

    // Error tracking
    private final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();

    /**
     * Creates a new MetadataPersistenceService.
     *
     * @param name service name for identification
     * @param options configuration options (maxRetries, retryBackoffMs)
     * @param resources resource map containing input queue, storage, and optional DLQ
     * @throws IllegalArgumentException if configuration validation fails
     * @throws IllegalStateException if required resources are missing
     */
    public MetadataPersistenceService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);

        // Required resources
        this.inputQueue = getRequiredResource("input", IInputQueueResource.class);
        this.storage = getRequiredResource("storage", IStorageWriteResource.class);

        // Optional resources
        this.dlq = getOptionalResource("dlq", IOutputQueueResource.class).orElse(null);

        // Configuration with defaults
        this.maxRetries = options.hasPath("maxRetries") ? options.getInt("maxRetries") : 3;
        this.retryBackoffMs = options.hasPath("retryBackoffMs") ? options.getInt("retryBackoffMs") : 1000;

        // Validation
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries cannot be negative");
        }
        if (retryBackoffMs < 0) {
            throw new IllegalArgumentException("retryBackoffMs cannot be negative");
        }

        log.info("MetadataPersistenceService initialized: maxRetries={}, retryBackoff={}ms",
            maxRetries, retryBackoffMs);
    }

    @Override
    protected void logStarted() {
        log.info("MetadataPersistenceService started: retry=[max={}, backoff={}ms], dlq={}",
            maxRetries, retryBackoffMs, dlq != null ? "configured" : "none");
    }

    /**
     * Main service loop - processes single metadata message then exits.
     * <p>
     * One-shot pattern:
     * <ol>
     *   <li>Blocks waiting for metadata message from queue</li>
     *   <li>Validates and persists message with retry logic</li>
     *   <li>Exits run() method, causing service to stop naturally</li>
     * </ol>
     *
     * @throws InterruptedException if interrupted during queue take or retry backoff
     */
    @Override
    protected void run() throws InterruptedException {
        // Block until metadata message arrives (one-shot pattern)
        SimulationMetadata metadata = inputQueue.take();

        log.debug("Received metadata message for simulation {}", metadata.getSimulationRunId());

        // Validate simulation run ID
        if (metadata.getSimulationRunId() == null || metadata.getSimulationRunId().isEmpty()) {
            log.error("Metadata contains empty or null simulationRunId");
            errors.add(new OperationalError(
                Instant.now(),
                "INVALID_SIMULATION_RUN_ID",
                "Metadata contains empty or null simulationRunId",
                "Cannot persist metadata without valid simulation run ID"
            ));
            sendToDLQ(metadata, "Empty or null simulationRunId",
                new IllegalStateException("Empty or null simulationRunId"));
            metadataFailed.incrementAndGet();
            return;
        }

        // Generate storage key: {simulationRunId}/metadata.pb
        String simulationRunId = metadata.getSimulationRunId();
        String key = simulationRunId + "/metadata.pb";

        // Write metadata with retry logic
        writeMetadataWithRetry(key, metadata);

        log.info("Metadata persisted successfully for simulation {}, service stopping", simulationRunId);
        // Exit run() - service stops naturally (one-shot pattern)
    }

    /**
     * Writes metadata to storage with retry logic and exponential backoff.
     *
     * @param key storage key for the metadata file
     * @param metadata the simulation metadata to persist
     */
    private void writeMetadataWithRetry(String key, SimulationMetadata metadata) {
        int attempt = 0;
        int backoff = retryBackoffMs;
        Exception lastException = null;

        while (attempt <= maxRetries) {
            try {
                writeMetadata(key, metadata);

                // Success
                metadataWritten.incrementAndGet();
                bytesWritten.addAndGet(metadata.getSerializedSize());
                log.debug("Successfully wrote metadata to {}", key);
                return;

            } catch (IOException e) {
                lastException = e;
                attempt++;

                if (attempt <= maxRetries) {
                    log.warn("Failed to write metadata {} (attempt {}/{}): {}, retrying in {}ms",
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
                    log.error("Failed to write metadata {} after {} retries: {}", key, maxRetries, e.getMessage());
                }
            }
        }

        // All retries exhausted - record error
        String errorDetails = String.format("Key: %s, Retries: %d, Exception: %s",
            key, maxRetries, lastException != null ? lastException.getMessage() : "Unknown");
        errors.add(new OperationalError(
            Instant.now(),
            "METADATA_WRITE_FAILED",
            "Failed to write metadata after all retries",
            errorDetails
        ));
        sendToDLQ(metadata, lastException != null ? lastException.getMessage() : "Unknown error", lastException);
        metadataFailed.incrementAndGet();
    }

    /**
     * Performs a single attempt to write metadata to storage.
     * <p>
     * Metadata is written using MessageWriter's delimited format (with length prefix),
     * which is compatible with storage.readMessage() that uses parseDelimitedFrom().
     * Even though metadata is a single message, the delimited format is used for
     * consistency with batch writes and correct round-trip serialization.
     *
     * @param key storage key for the metadata file
     * @param metadata the simulation metadata to persist
     * @throws IOException if write operation fails
     */
    private void writeMetadata(String key, SimulationMetadata metadata) throws IOException {
        try (MessageWriter writer = storage.openWriter(key)) {
            // writeMessage() uses writeDelimitedTo() internally (length-prefixed)
            // readMessage() uses parseDelimitedFrom() - perfectly compatible
            writer.writeMessage(metadata);
        }
    }

    /**
     * Sends failed metadata to dead letter queue for manual recovery.
     *
     * @param metadata the metadata that failed to persist
     * @param errorMessage description of the failure
     * @param exception the exception that caused the failure
     */
    private void sendToDLQ(SimulationMetadata metadata, String errorMessage, Exception exception) {
        if (dlq == null) {
            log.error("Failed metadata has no DLQ configured, data will be lost: simulation {}",
                metadata.getSimulationRunId());
            return;
        }

        try {
            String simulationRunId = metadata.getSimulationRunId() != null ?
                metadata.getSimulationRunId() : "UNKNOWN";
            String storageKey = simulationRunId + "/metadata.pb";

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
                .setOriginalMessage(ByteString.copyFrom(metadata.toByteArray()))
                .setMessageType("SimulationMetadata")
                .setFirstFailureTimeMs(System.currentTimeMillis())
                .setLastFailureTimeMs(System.currentTimeMillis())
                .setRetryCount(maxRetries)
                .setFailureReason(errorMessage)
                .setSourceService(serviceName)
                .setSourceQueue(sourceQueue)
                .addAllStackTraceLines(stackTraceLines)
                .putMetadata("simulationRunId", simulationRunId)
                .putMetadata("initialSeed", String.valueOf(metadata.getInitialSeed()))
                .putMetadata("startTimeMs", String.valueOf(metadata.getStartTimeMs()))
                .putMetadata("storageKey", storageKey)
                .putMetadata("exceptionType", exception != null ? exception.getClass().getName() : "Unknown")
                .build();

            if (dlq.offer(dlqMessage)) {
                log.info("Sent failed metadata to DLQ for simulation {}", simulationRunId);
            } else {
                log.error("DLQ is full, failed metadata lost for simulation {}", simulationRunId);
                errors.add(new OperationalError(
                    Instant.now(),
                    "DLQ_FULL",
                    "Dead letter queue is full, failed metadata lost",
                    String.format("SimulationRunId: %s", simulationRunId)
                ));
            }

        } catch (Exception e) {
            log.error("Failed to send metadata to DLQ", e);
            errors.add(new OperationalError(
                Instant.now(),
                "DLQ_SEND_FAILED",
                "Failed to send metadata to dead letter queue",
                String.format("SimulationRunId: %s, Exception: %s",
                    metadata.getSimulationRunId(), e.getMessage())
            ));
        }
    }

    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
            "metadata_written", metadataWritten.get(),
            "metadata_failed", metadataFailed.get(),
            "bytes_written", bytesWritten.get()
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
