package org.evochora.datapipeline.services.idempotency;

import org.evochora.datapipeline.api.services.IIdempotencyTracker;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory implementation of {@link IIdempotencyTracker} suitable for single-instance
 * in-process deployments. This implementation uses a {@link ConcurrentHashMap} to track
 * processed message keys with TTL-based expiration.
 *
 * <p><strong>Characteristics:</strong>
 * <ul>
 *   <li>Thread-safe for concurrent access</li>
 *   <li>Automatic expiration of old entries based on TTL</li>
 *   <li>Atomic check-and-mark operations</li>
 *   <li>Bounded memory usage through periodic cleanup</li>
 * </ul>
 * </p>
 *
 * <p><strong>Limitations:</strong>
 * <ul>
 *   <li>Not suitable for distributed/multi-instance deployments</li>
 *   <li>Data lost on process restart</li>
 *   <li>Memory-based, so large key sets require careful capacity planning</li>
 * </ul>
 * </p>
 *
 * <p>For distributed deployments, use a Redis-based or DynamoDB-based implementation.</p>
 *
 * @param <K> The type of the idempotency key.
 */
public class InMemoryIdempotencyTracker<K> implements IIdempotencyTracker<K> {

    private final ConcurrentHashMap<K, Instant> processedKeys;
    private final Duration ttl;
    private final long cleanupThreshold;
    private long operationsSinceLastCleanup = 0;

    /**
     * Constructs an InMemoryIdempotencyTracker with the specified TTL.
     *
     * @param ttl The time-to-live for processed keys. After this duration,
     *            keys are eligible for removal during cleanup.
     */
    public InMemoryIdempotencyTracker(Duration ttl) {
        this(ttl, 10000); // Cleanup every 10,000 operations by default
    }

    /**
     * Constructs an InMemoryIdempotencyTracker with the specified TTL and cleanup threshold.
     *
     * @param ttl               The time-to-live for processed keys.
     * @param cleanupThreshold  The number of operations between automatic cleanup runs.
     */
    public InMemoryIdempotencyTracker(Duration ttl, long cleanupThreshold) {
        this.processedKeys = new ConcurrentHashMap<>();
        this.ttl = ttl;
        this.cleanupThreshold = cleanupThreshold;
    }

    @Override
    public boolean isProcessed(K key) {
        maybeCleanup();
        Instant processedTime = processedKeys.get(key);
        if (processedTime == null) {
            return false;
        }
        // Check if the entry has expired
        if (Instant.now().isAfter(processedTime.plus(ttl))) {
            processedKeys.remove(key);
            return false;
        }
        return true;
    }

    @Override
    public boolean checkAndMarkProcessed(K key) {
        maybeCleanup();
        Instant now = Instant.now();

        // putIfAbsent returns null if the key wasn't present, or the existing value if it was
        Instant existing = processedKeys.putIfAbsent(key, now);

        if (existing == null) {
            // Key was not present, we've marked it as processed
            return true;
        }

        // Key exists - check if it has expired
        if (now.isAfter(existing.plus(ttl))) {
            // Entry has expired, update it and treat as newly processed
            processedKeys.put(key, now);
            return true;
        }

        // Key exists and hasn't expired - this is a duplicate
        return false;
    }

    @Override
    public void markProcessed(K key) {
        maybeCleanup();
        processedKeys.put(key, Instant.now());
    }

    @Override
    public boolean remove(K key) {
        return processedKeys.remove(key) != null;
    }

    @Override
    public void clear() {
        processedKeys.clear();
        operationsSinceLastCleanup = 0;
    }

    @Override
    public long size() {
        return processedKeys.size();
    }

    @Override
    public void cleanup() {
        Instant cutoff = Instant.now().minus(ttl);
        processedKeys.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        operationsSinceLastCleanup = 0;
    }

    /**
     * Performs cleanup if the operation threshold has been reached.
     * This is called automatically on each operation to prevent unbounded growth.
     */
    private void maybeCleanup() {
        operationsSinceLastCleanup++;
        if (operationsSinceLastCleanup >= cleanupThreshold) {
            cleanup();
        }
    }

    /**
     * Gets the TTL duration for this tracker.
     *
     * @return The TTL duration.
     */
    public Duration getTtl() {
        return ttl;
    }

    /**
     * Gets statistics about the idempotency tracker for monitoring.
     *
     * @return A formatted string with tracker statistics.
     */
    public String getStats() {
        return String.format("InMemoryIdempotencyTracker{size=%d, ttl=%s, operations_since_cleanup=%d}",
                size(), ttl, operationsSinceLastCleanup);
    }
}