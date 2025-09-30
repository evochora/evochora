package org.evochora.datapipeline.api.services;

/**
 * Interface for tracking processed messages to ensure idempotent message processing.
 * Idempotency is critical in distributed systems where messages may be delivered more
 * than once due to retries, network issues, or service restarts.
 *
 * <p>Implementations of this interface maintain a record of processed message identifiers
 * and provide methods to check whether a message has already been processed.</p>
 *
 * <p><strong>Architectural Note:</strong> This interface supports dual-mode deployment:
 * <ul>
 *   <li>In-process mode: Can use in-memory data structures (with TTL-based cleanup)</li>
 *   <li>Cloud mode: Can use distributed caches like Redis, DynamoDB, or Memcached</li>
 * </ul>
 * </p>
 *
 * @param <K> The type of the idempotency key (typically String, Long, or UUID).
 */
public interface IIdempotencyTracker<K> {

    /**
     * Checks if a message with the given key has already been processed.
     *
     * @param key The unique identifier for the message.
     * @return true if the message has been processed, false otherwise.
     */
    boolean isProcessed(K key);

    /**
     * Atomically checks if a message has been processed and marks it as processed if not.
     * This operation must be atomic to prevent race conditions in concurrent processing.
     *
     * @param key The unique identifier for the message.
     * @return true if the message was newly marked as processed (first time seeing it),
     *         false if it was already processed.
     */
    boolean checkAndMarkProcessed(K key);

    /**
     * Explicitly marks a message as processed.
     * Use this method when you want to separate the check and mark operations,
     * though {@link #checkAndMarkProcessed(K)} is preferred for atomicity.
     *
     * @param key The unique identifier for the message.
     */
    void markProcessed(K key);

    /**
     * Removes the record of a processed message, allowing it to be processed again.
     * This is useful for manual reprocessing or testing scenarios.
     *
     * @param key The unique identifier for the message.
     * @return true if the key was found and removed, false otherwise.
     */
    boolean remove(K key);

    /**
     * Clears all records of processed messages.
     * Use with caution - this will allow duplicate processing of all previous messages.
     */
    void clear();

    /**
     * Gets the number of tracked processed message keys.
     * This is primarily for monitoring and debugging.
     *
     * @return The count of tracked keys.
     */
    long size();

    /**
     * Performs cleanup of expired entries to prevent unbounded memory growth.
     * Implementations should call this periodically or automatically.
     */
    void cleanup();
}