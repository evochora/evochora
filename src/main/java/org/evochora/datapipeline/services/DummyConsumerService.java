package org.evochora.datapipeline.services;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.datapipeline.api.contracts.SystemContracts;
import org.evochora.datapipeline.api.contracts.SystemContracts.DummyMessage;
import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IDeadLetterQueueResource;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * A generic dummy consumer service that receives messages from an input queue with
 * optional idempotency guarantees and dead letter queue support.
 * It serves as a test service and a reference implementation for production-ready message processing.
 *
 * <h3>Configuration Options:</h3>
 * <ul>
 *   <li><b>processingDelayMs</b>: Artificial delay per message in milliseconds (default: 0).</li>
 *   <li><b>logReceivedMessages</b>: Whether to log received messages at DEBUG level (default: false).</li>
 *   <li><b>maxMessages</b>: Maximum messages to process, -1 for unlimited (default: -1).</li>
 *   <li><b>metricsWindowSeconds</b>: Time window in seconds for throughput calculation (default: 5).</li>
 *   <li><b>maxRetries</b>: Maximum processing attempts before sending to DLQ (default: 3).</li>
 * </ul>
 *
 * <h3>Resources:</h3>
 * <ul>
 *   <li><b>input</b>: IInputQueueResource&lt;T&gt; - Required input queue</li>
 *   <li><b>idempotencyTracker</b>: IIdempotencyTracker&lt;Integer&gt; - Optional idempotency tracker</li>
 *   <li><b>dlq</b>: IDeadLetterQueueResource&lt;T&gt; - Optional dead letter queue</li>
 * </ul>
 *
 * @param <T> The type of messages consumed from the input queue
 */
public class DummyConsumerService<T> extends AbstractService {

    private static final Logger logger = LoggerFactory.getLogger(DummyConsumerService.class);

    private final IInputQueueResource<T> inputQueue;
    private final IDeadLetterQueueResource<T> deadLetterQueue;
    private final long processingDelayMs;
    private final boolean logReceivedMessages;
    private final long maxMessages;
    private final int metricsWindowSeconds;
    private final int maxRetries;

    private final IIdempotencyTracker<Integer> idempotencyTracker;
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong messagesDuplicate = new AtomicLong(0);
    private final AtomicLong messagesRetried = new AtomicLong(0);
    private final AtomicLong messagesSentToDLQ = new AtomicLong(0);
    private final SlidingWindowCounter throughputCounter;
    private final Map<Integer, RetryInfo> retryTracker = new HashMap<>();

    public DummyConsumerService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.processingDelayMs = options.hasPath("processingDelayMs") ? options.getLong("processingDelayMs") : 0L;
        this.logReceivedMessages = options.hasPath("logReceivedMessages") && options.getBoolean("logReceivedMessages");
        this.maxMessages = options.hasPath("maxMessages") ? options.getLong("maxMessages") : -1L;
        this.metricsWindowSeconds = options.hasPath("metricsWindowSeconds") ? options.getInt("metricsWindowSeconds") : 5;
        this.maxRetries = options.hasPath("maxRetries") ? options.getInt("maxRetries") : 3;
        this.throughputCounter = new SlidingWindowCounter(metricsWindowSeconds);

        // Get required resources
        @SuppressWarnings("unchecked")
        IInputQueueResource<T> queue = (IInputQueueResource<T>) getRequiredResource("input", IInputQueueResource.class);
        this.inputQueue = queue;

        // Get optional resources
        @SuppressWarnings("unchecked")
        IIdempotencyTracker<Integer> tracker = (IIdempotencyTracker<Integer>) getOptionalResource("idempotencyTracker", IIdempotencyTracker.class)
                .map(t -> {
                    logger.info("Idempotency tracker configured for service '{}'", name);
                    return t;
                })
                .orElseGet(() -> {
                    logger.info("No idempotency tracker configured for service '{}' - duplicate detection disabled", name);
                    return null;
                });
        this.idempotencyTracker = tracker;

        @SuppressWarnings("unchecked")
        IDeadLetterQueueResource<T> dlq = (IDeadLetterQueueResource<T>) getOptionalResource("dlq", IDeadLetterQueueResource.class)
                .map(d -> {
                    String dlqName = d.getResourceName();
                    logger.info("Dead Letter Queue configured for service '{}': {}", name, dlqName);
                    return d;
                })
                .orElseGet(() -> {
                    logger.warn("No Dead Letter Queue configured for service '{}' - failed messages will be logged only", name);
                    return null;
                });
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

            T message = null;
            try {
                message = inputQueue.take();

                messagesReceived.incrementAndGet();
                int messageId = extractMessageId(message);

                // Check for duplicate (idempotency) if tracker is configured
                if (idempotencyTracker != null && !idempotencyTracker.checkAndMarkProcessed(messageId)) {
                    messagesDuplicate.incrementAndGet();
                    logger.debug("Skipping duplicate message with ID: {}", messageId);
                    continue;
                }

                // Process the message
                processMessage(message);
                messageCounter++;

                // Clean up retry tracking for successfully processed message
                retryTracker.remove(messageId);
                
                // Check if we've reached the max message limit
                if (maxMessages != -1 && messageCounter >= maxMessages) {
                    logger.info("Reached max message limit of {}. Stopping service.", maxMessages);
                    break;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.debug("Consumer service interrupted while waiting for message");
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
     * Extracts a unique message ID for tracking purposes.
     * For DummyMessage, uses getId(). For other types, uses hashCode().
     */
    private int extractMessageId(T message) {
        if (message instanceof DummyMessage) {
            return ((DummyMessage) message).getId();
        }
        return message.hashCode();
    }

    /**
     * Processes a message. Override this method to implement actual business logic.
     *
     * @param message The message to process.
     * @throws Exception if processing fails.
     */
    protected void processMessage(T message) throws Exception {
        throughputCounter.recordCount();

        if (logReceivedMessages) {
            int messageId = extractMessageId(message);
            String content = (message instanceof DummyMessage)
                    ? ((DummyMessage) message).getContent()
                    : message.getClass().getSimpleName();
            logger.debug("Processing message ID={}: {}", messageId, content);
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
    private void handleProcessingError(T message, Exception error) {
        if (message == null) {
            logger.warn("Error receiving message from queue");
            recordError("PROCESSING_ERROR", "Error receiving message from queue", "null message");
            return;
        }

        int messageId = extractMessageId(message);
        logger.warn("Failed to process message ID={}", messageId);
        recordError("PROCESSING_ERROR", "Error processing message", String.format("Message ID: %d", messageId));

        // Track retry attempts
        RetryInfo retryInfo = retryTracker.computeIfAbsent(messageId, k -> new RetryInfo());
        retryInfo.recordRetry();

        if (retryInfo.attemptCount >= maxRetries) {
            // Exceeded retry limit - send to DLQ
            sendToDeadLetterQueue(message, retryInfo, error);
            retryTracker.remove(messageId);
            // Remove from idempotency tracker if configured
            if (idempotencyTracker != null) {
                idempotencyTracker.remove(messageId);
            }
        } else {
            // Will retry on next poll
            messagesRetried.incrementAndGet();
            logger.debug("Message ID={} will be retried (attempt {}/{})",
                    messageId, retryInfo.attemptCount, maxRetries);
        }
    }

    /**
     * Sends a failed message to the Dead Letter Queue.
     * Only supports protobuf Message types for DLQ serialization.
     *
     * @param message   The message that failed processing.
     * @param retryInfo Retry tracking information.
     * @param error     The exception that caused the failure.
     */
    private void sendToDeadLetterQueue(T message, RetryInfo retryInfo, Exception error) {
        if (deadLetterQueue == null) {
            int messageId = extractMessageId(message);
            logger.warn("Message ID={} exceeded retry limit but no DLQ configured, message will be lost", messageId);
            recordError("DLQ_NOT_CONFIGURED", "Message lost - no DLQ configured", String.format("Message ID: %d", messageId));
            return;
        }

        try {
            // Build stack trace from exception
            java.util.List<String> stackTraceLines = new java.util.ArrayList<>();
            for (StackTraceElement element : error.getStackTrace()) {
                stackTraceLines.add(element.toString());
                if (stackTraceLines.size() >= 10) break; // Limit stack trace size
            }

            int messageId = extractMessageId(message);

            // Check if message is a protobuf Message for serialization
            com.google.protobuf.ByteString originalBytes;
            if (message instanceof com.google.protobuf.Message) {
                originalBytes = ((com.google.protobuf.Message) message).toByteString();
            } else {
                // For non-protobuf messages, use empty bytes
                originalBytes = com.google.protobuf.ByteString.EMPTY;
                logger.warn("Message type {} is not a protobuf Message - DLQ will contain empty originalMessage",
                        message.getClass().getName());
            }

            SystemContracts.DeadLetterMessage dlqMessage = SystemContracts.DeadLetterMessage.newBuilder()
                    .setOriginalMessage(originalBytes)
                    .setMessageType(message.getClass().getName())
                    .setFirstFailureTimeMs(retryInfo.firstAttempt.toEpochMilli())
                    .setLastFailureTimeMs(retryInfo.lastAttempt.toEpochMilli())
                    .setRetryCount(retryInfo.attemptCount)
                    .setFailureReason(error.getClass().getName() + ": " + error.getMessage())
                    .setSourceService(serviceName)
                    .setSourceQueue(inputQueue.getResourceName())
                    .addAllStackTraceLines(stackTraceLines)
                    .build();

            deadLetterQueue.offer(dlqMessage);
            messagesSentToDLQ.incrementAndGet();
            logger.warn("Message ID={} sent to Dead Letter Queue after {} attempts", messageId, retryInfo.attemptCount);

        } catch (Exception dlqError) {
            int messageId = extractMessageId(message);
            logger.warn("Failed to send message ID={} to Dead Letter Queue, message may be lost", messageId);
            recordError(
                "DLQ_SEND_FAILED",
                "Failed to send message to Dead Letter Queue",
                String.format("Message ID=%d", messageId)
            );
        }
    }

    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);
        
        metrics.put("messages_received", messagesReceived.get());
        metrics.put("messages_duplicate", messagesDuplicate.get());
        metrics.put("messages_retried", messagesRetried.get());
        metrics.put("messages_sent_to_dlq", messagesSentToDLQ.get());
        metrics.put("messages_in_retry", retryTracker.size());
        metrics.put("idempotency_tracker_size", idempotencyTracker != null ? idempotencyTracker.size() : 0);
        metrics.put("throughput_per_sec", throughputCounter.getRate());
    }
}