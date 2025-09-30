package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.PipelineContracts;
import org.evochora.datapipeline.api.contracts.PipelineContracts.DummyMessage;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.evochora.datapipeline.api.resources.wrappers.queues.IDeadLetterQueueResource;
import org.evochora.datapipeline.api.resources.wrappers.queues.IInputQueueResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A dummy consumer service that receives Protobuf messages from an input queue with
 * idempotency guarantees and dead letter queue support.
 * It serves as a test service and a reference implementation for production-ready message processing.
 *
 * <h3>Configuration Options:</h3>
 * <ul>
 *   <li><b>processingDelayMs</b>: Artificial delay per message in milliseconds (default: 0).</li>
 *   <li><b>logReceivedMessages</b>: Whether to log received messages at DEBUG level (default: false).</li>
 *   <li><b>maxMessages</b>: Maximum messages to process, -1 for unlimited (default: -1).</li>
 *   <li><b>throughputWindowSeconds</b>: Time window in seconds for throughput calculation (default: 5).</li>
 *   <li><b>maxRetries</b>: Maximum processing attempts before sending to DLQ (default: 3).</li>
 * </ul>
 *
 * <h3>Resources:</h3>
 * <ul>
 *   <li><b>input</b>: IInputQueueResource&lt;DummyMessage&gt; - Required input queue</li>
 *   <li><b>idempotencyTracker</b>: IIdempotencyTracker&lt;Integer&gt; - Required idempotency tracker</li>
 *   <li><b>dlq</b>: IDeadLetterQueueResource&lt;DummyMessage&gt; - Optional dead letter queue</li>
 * </ul>
 */
public class DummyConsumerService extends AbstractService implements IMonitorable {

    private static final Logger logger = LoggerFactory.getLogger(DummyConsumerService.class);

    private final IInputQueueResource<DummyMessage> inputQueue;
    private final IDeadLetterQueueResource<DummyMessage> deadLetterQueue;
    private final long processingDelayMs;
    private final boolean logReceivedMessages;
    private final long maxMessages;
    private final int throughputWindowSeconds;
    private final int maxRetries;

    private final IIdempotencyTracker<Integer> idempotencyTracker;
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong messagesDuplicate = new AtomicLong(0);
    private final AtomicLong messagesRetried = new AtomicLong(0);
    private final AtomicLong messagesSentToDLQ = new AtomicLong(0);
    private final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Long> messageTimestamps = new ConcurrentLinkedDeque<>();
    private final Map<Integer, RetryInfo> retryTracker = new HashMap<>();

    public DummyConsumerService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.processingDelayMs = options.hasPath("processingDelayMs") ? options.getLong("processingDelayMs") : 0L;
        this.logReceivedMessages = options.hasPath("logReceivedMessages") && options.getBoolean("logReceivedMessages");
        this.maxMessages = options.hasPath("maxMessages") ? options.getLong("maxMessages") : -1L;
        this.throughputWindowSeconds = options.hasPath("throughputWindowSeconds") ? options.getInt("throughputWindowSeconds") : 5;
        this.maxRetries = options.hasPath("maxRetries") ? options.getInt("maxRetries") : 3;

        // Get required resources
        this.inputQueue = getRequiredResource("input", IInputQueueResource.class);
        this.idempotencyTracker = getRequiredResource("idempotencyTracker", IIdempotencyTracker.class);

        // Get optional DLQ resource using try-catch pattern
        IDeadLetterQueueResource<DummyMessage> dlq = null;
        try {
            dlq = getRequiredResource("dlq", IDeadLetterQueueResource.class);
            String dlqName = dlq instanceof org.evochora.datapipeline.resources.AbstractResource
                    ? ((org.evochora.datapipeline.resources.AbstractResource) dlq).getResourceName()
                    : "unknown";
            logger.info("Dead Letter Queue configured for service '{}': {}", name, dlqName);
        } catch (IllegalStateException e) {
            logger.warn("No Dead Letter Queue configured for service '{}' - failed messages will be logged only", name);
        }
        this.deadLetterQueue = dlq;
    }

    /**
     * Tracks retry information for a message.
     */
    private static class RetryInfo {
        final Instant firstAttempt;
        Instant lastAttempt;
        int attemptCount;

        RetryInfo() {
            this.firstAttempt = Instant.now();
            this.lastAttempt = this.firstAttempt;
            this.attemptCount = 1;
        }

        void recordRetry() {
            this.lastAttempt = Instant.now();
            this.attemptCount++;
        }
    }

    @Override
    protected void run() throws InterruptedException {
        long messageCounter = 0;
        while (!Thread.currentThread().isInterrupted() && (maxMessages == -1 || messageCounter < maxMessages)) {
            checkPause();

            DummyMessage message = null;
            try {
                message = inputQueue.take();
                if (message == null) {
                    continue;
                }

                messagesReceived.incrementAndGet();
                int messageId = message.getId();

                // Check for duplicate (idempotency)
                if (!idempotencyTracker.checkAndMarkProcessed(messageId)) {
                    messagesDuplicate.incrementAndGet();
                    logger.debug("Skipping duplicate message with ID: {}", messageId);
                    continue;
                }

                // Process the message
                processMessage(message);
                messageCounter++;

                // Clean up retry tracking for successfully processed message
                retryTracker.remove(messageId);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.info("Consumer service interrupted while waiting for message.");
                break;
            } catch (Exception e) {
                handleProcessingError(message, e);
            }
        }
        if (maxMessages != -1 && messageCounter >= maxMessages) {
            logger.info("Reached max message limit of {}. Stopping service.", maxMessages);
        }
    }

    /**
     * Processes a message. Override this method to implement actual business logic.
     *
     * @param message The message to process.
     * @throws Exception if processing fails.
     */
    protected void processMessage(DummyMessage message) throws Exception {
        messageTimestamps.add(System.currentTimeMillis());

        if (logReceivedMessages) {
            logger.debug("Processing message ID={}: {}", message.getId(), message.getContent());
        }

        if (processingDelayMs > 0) {
            Thread.sleep(processingDelayMs);
        }

        // Actual processing would go here
        // For the dummy service, we just simulate processing
    }

    /**
     * Handles processing errors with retry logic and DLQ support.
     *
     * @param message The message that failed processing (may be null if error occurred before message retrieval).
     * @param error   The exception that occurred.
     */
    private void handleProcessingError(DummyMessage message, Exception error) {
        OperationalError opError = new OperationalError(Instant.now(), "PROCESSING_ERROR",
                "Error processing message", error.getMessage());
        errors.add(opError);

        if (message == null) {
            logger.error("Error receiving message from queue", error);
            return;
        }

        int messageId = message.getId();
        logger.warn("Failed to process message ID={}: {}", messageId, error.getMessage());

        // Track retry attempts
        RetryInfo retryInfo = retryTracker.computeIfAbsent(messageId, k -> new RetryInfo());
        retryInfo.recordRetry();

        if (retryInfo.attemptCount >= maxRetries) {
            // Exceeded retry limit - send to DLQ
            sendToDeadLetterQueue(message, retryInfo, error);
            retryTracker.remove(messageId);
            // Remove from idempotency tracker to allow potential reprocessing from DLQ
            idempotencyTracker.remove(messageId);
        } else {
            // Will retry on next poll
            messagesRetried.incrementAndGet();
            logger.info("Message ID={} will be retried (attempt {}/{})",
                    messageId, retryInfo.attemptCount, maxRetries);
        }
    }

    /**
     * Sends a failed message to the Dead Letter Queue.
     *
     * @param message   The message that failed processing.
     * @param retryInfo Retry tracking information.
     * @param error     The exception that caused the failure.
     */
    private void sendToDeadLetterQueue(DummyMessage message, RetryInfo retryInfo, Exception error) {
        if (deadLetterQueue == null) {
            logger.error("Message ID={} exceeded retry limit but no DLQ configured. Message will be lost: {}",
                    message.getId(), message.getContent());
            return;
        }

        try {
            // Build stack trace from exception
            java.util.List<String> stackTraceLines = new java.util.ArrayList<>();
            for (StackTraceElement element : error.getStackTrace()) {
                stackTraceLines.add(element.toString());
                if (stackTraceLines.size() >= 10) break; // Limit stack trace size
            }

            PipelineContracts.DeadLetterMessage dlqMessage = PipelineContracts.DeadLetterMessage.newBuilder()
                    .setOriginalMessage(message.toByteString())
                    .setMessageType(message.getClass().getName())
                    .setFirstFailureTimeMs(retryInfo.firstAttempt.toEpochMilli())
                    .setLastFailureTimeMs(retryInfo.lastAttempt.toEpochMilli())
                    .setRetryCount(retryInfo.attemptCount)
                    .setFailureReason(error.getClass().getName() + ": " + error.getMessage())
                    .setSourceService(serviceName)
                    .setSourceQueue(inputQueue instanceof org.evochora.datapipeline.resources.AbstractResource
                            ? ((org.evochora.datapipeline.resources.AbstractResource) inputQueue).getResourceName()
                            : "unknown")
                    .addAllStackTraceLines(stackTraceLines)
                    .build();

            deadLetterQueue.offer(dlqMessage);
            messagesSentToDLQ.incrementAndGet();
            logger.warn("Message ID={} sent to Dead Letter Queue after {} attempts",
                    message.getId(), retryInfo.attemptCount);

        } catch (Exception dlqError) {
            logger.error("CRITICAL: Failed to send message ID={} to Dead Letter Queue: {}. Message may be lost!",
                    message.getId(), dlqError.getMessage(), dlqError);
        }
    }

    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new HashMap<>();
        metrics.put("messages_received", messagesReceived.get());
        metrics.put("messages_duplicate", messagesDuplicate.get());
        metrics.put("messages_retried", messagesRetried.get());
        metrics.put("messages_sent_to_dlq", messagesSentToDLQ.get());
        metrics.put("messages_in_retry", retryTracker.size());
        metrics.put("idempotency_tracker_size", idempotencyTracker.size());
        metrics.put("throughput_per_sec", calculateThroughput());
        return metrics;
    }

    @Override
    public List<OperationalError> getErrors() {
        // Return an immutable copy to ensure thread safety
        return Collections.unmodifiableList(List.copyOf(errors));
    }

    @Override
    public void clearErrors() {
        errors.clear();
    }

    @Override
    public boolean isHealthy() {
        return getCurrentState() != State.ERROR;
    }

    private double calculateThroughput() {
        long now = System.currentTimeMillis();
        long windowStart = now - (throughputWindowSeconds * 1000L);
        messageTimestamps.removeIf(timestamp -> timestamp < windowStart);
        if (throughputWindowSeconds == 0) return 0;
        return (double) messageTimestamps.size() / throughputWindowSeconds;
    }
}