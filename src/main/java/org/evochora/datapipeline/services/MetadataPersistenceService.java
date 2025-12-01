package org.evochora.datapipeline.services;

import com.google.protobuf.ByteString;
import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.MetadataInfo;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.SystemContracts;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One-shot service that persists a single SimulationMetadata message to storage
 * and publishes a notification to the metadata topic.
 * <p>
 * MetadataPersistenceService follows the one-shot pattern:
 * <ul>
 *   <li>Starts at simulation initialization</li>
 *   <li>Blocks waiting for the single metadata message from SimulationEngine</li>
 *   <li>Persists message to storage with retry logic and DLQ support</li>
 *   <li>Publishes MetadataInfo notification to topic for event-driven indexing</li>
 *   <li>Stops itself after successful processing (thread terminates)</li>
 * </ul>
 * <p>
 * The service provides reliable metadata persistence with:
 * <ul>
 *   <li>Retry logic with exponential backoff for transient failures</li>
 *   <li>Dead letter queue support for unrecoverable failures</li>
 *   <li>Topic notification for instant indexing (no polling)</li>
 *   <li>Graceful shutdown handling</li>
 * </ul>
 * <p>
 * <strong>Storage Pattern:</strong> Writes metadata to <code>{simulationRunId}/raw/metadata.pb</code>
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
 *     topic = "topic-write:metadata-topic"
 *     dlq = "queue-out:persistence-dlq"
 *   }
 *   options {
 *     maxRetries = 3
 *     retryBackoffMs = 1000
 *   }
 * }
 * </pre>
 */
public class MetadataPersistenceService extends AbstractService {

    // Required resources
    private final IInputQueueResource<SimulationMetadata> inputQueue;
    private final IBatchStorageWrite storage;
    private final ITopicWriter<MetadataInfo> topic;

    // Optional resources
    private final IOutputQueueResource<SystemContracts.DeadLetterMessage> dlq;

    // Configuration
    private final int maxRetries;
    private final int retryBackoffMs;

    // Metrics
    private final AtomicLong metadataWritten = new AtomicLong(0);
    private final AtomicLong metadataFailed = new AtomicLong(0);
    private final AtomicLong bytesWritten = new AtomicLong(0);

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
        this.storage = getRequiredResource("storage", IBatchStorageWrite.class);

        // Optional resources
        this.topic = getOptionalResource("topic", ITopicWriter.class).orElse(null);
        this.dlq = getOptionalResource("dlq", IOutputQueueResource.class).orElse(null);
        
        // Warn if topic is not configured
        if (this.topic == null) {
            log.warn("MetadataPersistenceService initialized WITHOUT topic - event-driven metadata indexing disabled!");
        }

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

        log.debug("MetadataPersistenceService initialized: maxRetries={}, retryBackoff={}ms",
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
            log.warn("Metadata contains empty or null simulationRunId, sending to DLQ");
            recordError(
                "INVALID_SIMULATION_RUN_ID",
                "Metadata contains empty or null simulationRunId",
                "Cannot persist metadata without valid simulation run ID"
            );
            sendToDLQ(metadata, "Empty or null simulationRunId",
                new IllegalStateException("Empty or null simulationRunId"));
            metadataFailed.incrementAndGet();
            return;
        }

        // Generate storage key: {simulationRunId}/raw/metadata.pb
        String simulationRunId = metadata.getSimulationRunId();
        String key = simulationRunId + "/raw/metadata.pb";

        // Write metadata with retry logic
        writeMetadataWithRetry(key, metadata);

        log.debug("Metadata persisted successfully for simulation {}, service stopping", simulationRunId);
        // Exit run() - service stops naturally (one-shot pattern)
    }

    /**
     * Writes metadata to storage with retry logic and exponential backoff.
     *
     * @param key storage key for the metadata file
     * @param metadata the simulation metadata to persist
     * @throws InterruptedException if interrupted while sending topic notification
     */
    private void writeMetadataWithRetry(String key, SimulationMetadata metadata) throws InterruptedException {
        int attempt = 0;
        int backoff = retryBackoffMs;
        Exception lastException = null;

        while (attempt <= maxRetries) {
            try {
                StoragePath storagePath = writeMetadata(key, metadata);

                metadataWritten.incrementAndGet();
                bytesWritten.addAndGet(metadata.getSerializedSize());
                log.debug("Successfully wrote metadata to {}", storagePath);
                
                // Send topic notification if topic is configured
                if (topic != null) {
                    topic.setSimulationRun(metadata.getSimulationRunId());
                    try {
                        sendTopicNotification(storagePath.asString(), metadata);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.debug("Interrupted while sending topic notification");
                        throw ie;
                    }
                }
                
                return;

            } catch (IOException e) {
                lastException = e;
                attempt++;

                if (attempt <= maxRetries) {
                    log.debug("Failed to write metadata {} (attempt {}/{}): {}, retrying in {}ms",
                        key, attempt, maxRetries, e.getMessage(), backoff);

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
                    log.warn("Failed to write metadata {} after {} retries, sending to DLQ", key, maxRetries);
                }
            }
        }

        // All retries exhausted - record error
        String errorDetails = String.format("Key: %s, Retries: %d, Exception: %s",
            key, maxRetries, lastException != null ? lastException.getMessage() : "Unknown");
        recordError(
            "METADATA_WRITE_FAILED",
            "Failed to write metadata after all retries",
            errorDetails
        );
        sendToDLQ(metadata, lastException != null ? lastException.getMessage() : "Unknown error", lastException);
        metadataFailed.incrementAndGet();
    }

    /**
     * Performs a single attempt to write metadata to storage.
     * <p>
     * Uses the storage abstraction's writeMessage() method, which:
     * <ul>
     *   <li>Works with any storage backend (local filesystem, cloud storage, etc.)</li>
     *   <li>Handles compression automatically based on storage configuration</li>
     *   <li>Writes in length-delimited protobuf format for compatibility</li>
     *   <li>Performs atomic write (temp file → final file)</li>
     * </ul>
     *
     * @param key storage key for the metadata file (logical path without compression)
     * @param metadata the simulation metadata to persist
     * @return the physical storage path where metadata was written (includes compression extension)
     * @throws IOException if write operation fails
     */
    private StoragePath writeMetadata(String key, SimulationMetadata metadata) throws IOException {
        return storage.writeMessage(key, metadata);
    }

    /**
     * Sends a metadata notification to the topic after successful storage write.
     * <p>
     * This notification enables event-driven metadata indexing - MetadataIndexer
     * subscribes to the topic and reads from storage only when notified.
     *
     * @param storagePath physical storage path where metadata was written (includes compression extension)
     * @param metadata the simulation metadata that was persisted
     * @throws InterruptedException if interrupted while sending notification
     */
    private void sendTopicNotification(String storagePath, SimulationMetadata metadata) throws InterruptedException {
        MetadataInfo info = MetadataInfo.newBuilder()
            .setSimulationRunId(metadata.getSimulationRunId())
            .setStoragePath(storagePath)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        
        topic.send(info);
        log.debug("Sent metadata notification for {}", storagePath);
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
            log.warn("Failed metadata has no DLQ configured, data will be lost: simulation {}",
                metadata.getSimulationRunId());
            recordError(
                "DLQ_NOT_CONFIGURED",
                "Failed metadata lost - no DLQ configured",
                String.format("SimulationRunId: %s", metadata.getSimulationRunId())
            );
            return;
        }

        try {
            String simulationRunId = metadata.getSimulationRunId() != null ?
                metadata.getSimulationRunId() : "UNKNOWN";
            String storageKey = simulationRunId + "/raw/metadata.pb";

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
                log.warn("DLQ is full, failed metadata lost for simulation {}", simulationRunId);
                recordError(
                    "DLQ_FULL",
                    "Dead letter queue is full, failed metadata lost",
                    String.format("SimulationRunId: %s", simulationRunId)
                );
            }

        } catch (Exception e) {
            log.warn("Failed to send metadata to DLQ");
            recordError(
                "DLQ_SEND_FAILED",
                "Failed to send metadata to dead letter queue",
                String.format("SimulationRunId: %s, Exception: %s",
                    metadata.getSimulationRunId(), e.getMessage())
            );
        }
    }

    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);
        
        metrics.put("metadata_written", metadataWritten.get());
        metrics.put("metadata_failed", metadataFailed.get());
        metrics.put("bytes_written", bytesWritten.get());
    }
}
