package org.evochora.datapipeline.services.indexers.components;

import com.google.protobuf.Message;
import org.evochora.datapipeline.api.contracts.SystemContracts;
import org.evochora.datapipeline.api.resources.IRetryTracker;
import org.evochora.datapipeline.api.resources.queues.IDeadLetterQueueResource;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Component for moving poison messages to Dead Letter Queue after max retries.
 * <p>
 * Tracks retry counts across competing consumers using shared {@link IRetryTracker}.
 * When a message fails repeatedly, moves it to DLQ to prevent blocking the pipeline.
 * <p>
 * <strong>Thread Safety:</strong> This component is <strong>NOT thread-safe</strong>
 * and must not be accessed concurrently by multiple threads. It is designed for
 * single-threaded use within one service instance.
 * <p>
 * <strong>Competing Consumer Pattern:</strong> Multiple service instances (competing
 * consumers) each have their own {@code DlqComponent} instance. The underlying
 * {@link IRetryTracker} and {@link IDeadLetterQueueResource} resources ARE thread-safe
 * and coordinate retry counting across all consumers.
 * <p>
 * <strong>Usage Pattern:</strong> Each {@code AbstractBatchIndexer} instance creates
 * its own {@code DlqComponent} in {@code createComponents()}. Components are never
 * shared between service instances or threads.
 * <p>
 * <strong>Example:</strong> 3x DummyIndexer (competing consumers) each has own
 * DlqComponent, but all share the same IRetryTracker and IDeadLetterQueueResource.
 */
public class DlqComponent<T extends Message, ACK> {
    private static final Logger log = LoggerFactory.getLogger(DlqComponent.class);
    
    private final IRetryTracker retryTracker;
    private final IDeadLetterQueueResource<T> dlq;
    private final int maxRetries;
    private final String indexerName;
    
    /**
     * Creates DLQ component.
     * <p>
     * The indexer name is used for metadata in DeadLetterMessage to identify
     * which service moved the message to DLQ.
     *
     * @param retryTracker Shared retry tracker (must not be null)
     * @param dlq Dead letter queue resource (must not be null)
     * @param maxRetries Maximum retries before moving to DLQ (must be positive)
     * @param indexerName Name of indexer for metadata (must not be null/blank)
     * @throws IllegalArgumentException if any parameter is invalid
     */
    public DlqComponent(IRetryTracker retryTracker,
                        IDeadLetterQueueResource<T> dlq,
                        int maxRetries,
                        String indexerName) {
        if (retryTracker == null) {
            throw new IllegalArgumentException("RetryTracker must not be null");
        }
        if (dlq == null) {
            throw new IllegalArgumentException("DLQ must not be null");
        }
        if (maxRetries <= 0) {
            throw new IllegalArgumentException("MaxRetries must be positive");
        }
        if (indexerName == null || indexerName.isBlank()) {
            throw new IllegalArgumentException("Indexer name must not be null or blank");
        }
        
        this.retryTracker = retryTracker;
        this.dlq = dlq;
        this.maxRetries = maxRetries;
        this.indexerName = indexerName;
    }
    
    /**
     * Check if message should be moved to DLQ.
     * <p>
     * Increments retry count (shared across competing consumers).
     * Returns true if retry limit exceeded.
     * <p>
     * If tracking fails, returns false as safe default (retry again).
     *
     * @param messageId Unique message identifier (must not be null)
     * @return true if message should be moved to DLQ
     * @throws IllegalArgumentException if messageId is null
     */
    public boolean shouldMoveToDlq(String messageId) {
        if (messageId == null) {
            throw new IllegalArgumentException("messageId must not be null");
        }
        
        try {
            int retries = retryTracker.incrementAndGetRetryCount(messageId);
            return retries > maxRetries;
        } catch (Exception e) {
            log.debug("Retry tracking failed for {}, assuming not at limit: {}", 
                messageId, e.getMessage());
            return false;  // Safe default - retry again
        }
    }
    
    /**
     * Move message to DLQ with error metadata.
     * <p>
     * Wraps original message in DeadLetterMessage with error details
     * and retry count. Uses existing DLQ resource infrastructure.
     * <p>
     * After successful DLQ write, marks message as moved in retry tracker
     * (triggers cleanup to prevent memory growth).
     *
     * @param message Original topic message (must not be null)
     * @param error Exception that caused the failure (may be null)
     * @param storageKey Storage key for metadata (must not be null)
     * @throws InterruptedException if DLQ write is interrupted
     * @throws IllegalArgumentException if message or storageKey is null
     */
    public void moveToDlq(TopicMessage<T, ACK> message, 
                          Exception error,
                          String storageKey) throws InterruptedException {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        if (storageKey == null) {
            throw new IllegalArgumentException("storageKey must not be null");
        }
        
        try {
            int retries = retryTracker.getRetryCount(storageKey);
            
            // Build stack trace (limit to 10 lines)
            List<String> stackTraceLines = new ArrayList<>();
            if (error != null) {
                for (StackTraceElement element : error.getStackTrace()) {
                    stackTraceLines.add(element.toString());
                    if (stackTraceLines.size() >= 10) break;
                }
            }
            
            // Build DLQ message (reuse existing DeadLetterMessage protocol!)
            SystemContracts.DeadLetterMessage dlqMsg = 
                SystemContracts.DeadLetterMessage.newBuilder()
                    .setOriginalMessage(message.payload().toByteString())
                    .setMessageType(message.payload().getClass().getName())
                    .setFailureReason(error != null 
                        ? error.getClass().getName() + ": " + error.getMessage()
                        : "Unknown error")
                    .setRetryCount(retries)
                    .setSourceService(indexerName)
                    .setFirstFailureTimeMs(System.currentTimeMillis())  // Approximation
                    .setLastFailureTimeMs(System.currentTimeMillis())
                    .addAllStackTraceLines(stackTraceLines)
                    .build();
            
            // Write to DLQ
            dlq.put(dlqMsg);
            
            // Mark as moved (triggers cleanup in tracker)
            retryTracker.markMovedToDlq(storageKey);
            
            log.warn("Moved message to DLQ after {} retries: {}", retries, storageKey);
            
        } catch (InterruptedException e) {
            // Propagate interruption
            throw e;
        } catch (Exception e) {
            log.warn("Failed to move message to DLQ: {}", storageKey);
            throw new InterruptedException("DLQ write failed: " + e.getMessage());
        }
    }
    
    /**
     * Reset retry count after successful processing.
     * <p>
     * Should be called after successful flush to clear retry history.
     * This prevents false positives from earlier transient failures
     * and triggers cleanup to free memory.
     * <p>
     * If reset fails (e.g. tracker error), logs but continues. This is not critical
     * because worst-case is a false positive (premature DLQ move), which is
     * acceptable given FIFO safety net.
     *
     * @param messageId Unique message identifier (must not be null)
     * @throws IllegalArgumentException if messageId is null
     */
    public void resetRetryCount(String messageId) {
        if (messageId == null) {
            throw new IllegalArgumentException("messageId must not be null");
        }
        
        try {
            retryTracker.resetRetryCount(messageId);
        } catch (Exception e) {
            log.debug("Failed to reset retry count for {}: {}", messageId, e.getMessage());
            // Not critical - next successful processing will reset
            // Worst case: false positive after maxKeys messages (FIFO evicts anyway)
        }
    }
}

