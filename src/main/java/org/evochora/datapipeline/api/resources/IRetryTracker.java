package org.evochora.datapipeline.api.resources;

/**
 * Resource for tracking retry counts across competing consumers.
 * <p>
 * Backed by shared storage (in-memory or database) to coordinate
 * retry counting between multiple service instances. This enables
 * Dead Letter Queue (DLQ) functionality where poison messages
 * are moved to DLQ after exceeding retry limits.
 * <p>
 * <strong>Thread Safety:</strong> Implementations must be thread-safe for
 * concurrent access from multiple consumers. All methods may be called
 * concurrently by different service instances.
 * <p>
 * <strong>Use Case:</strong> Prevents poison messages from blocking pipeline
 * by tracking failures across all competing consumers and moving messages
 * to DLQ after shared retry limit is exceeded.
 * <p>
 * <strong>Architectural Note:</strong> This interface supports dual-mode deployment:
 * <ul>
 *   <li>In-process mode: Can use in-memory data structures (with FIFO eviction)</li>
 *   <li>Cloud mode: Can use distributed caches like Redis or DynamoDB</li>
 * </ul>
 */
public interface IRetryTracker extends IResource {
    
    /**
     * Increments and returns retry count for a message.
     * <p>
     * Thread-safe across competing consumers. Each call increments the
     * shared counter, regardless of which consumer instance makes the call.
     * <p>
     * This method is typically called when a message fails processing,
     * before checking if the retry limit has been exceeded.
     *
     * @param messageId Unique message identifier (must not be null)
     * @return Current retry count after increment (1 on first failure)
     * @throws Exception if tracking fails
     */
    int incrementAndGetRetryCount(String messageId) throws Exception;
    
    /**
     * Gets current retry count without incrementing.
     * <p>
     * Used to retrieve the final retry count when moving message to DLQ.
     *
     * @param messageId Unique message identifier (must not be null)
     * @return Current retry count (0 if never failed)
     * @throws Exception if tracking fails
     */
    int getRetryCount(String messageId) throws Exception;
    
    /**
     * Marks message as moved to DLQ (stops further tracking).
     * <p>
     * Called after successfully moving a poison message to DLQ.
     * Implementations should clean up tracking data to prevent memory growth.
     *
     * @param messageId Unique message identifier (must not be null)
     * @throws Exception if marking fails
     */
    void markMovedToDlq(String messageId) throws Exception;
    
    /**
     * Resets retry count after successful processing.
     * <p>
     * Called when message is successfully processed to prevent false
     * positives from earlier transient failures. Implementations should
     * remove tracking data to prevent memory growth.
     *
     * @param messageId Unique message identifier (must not be null)
     * @throws Exception if reset fails
     */
    void resetRetryCount(String messageId) throws Exception;
}

