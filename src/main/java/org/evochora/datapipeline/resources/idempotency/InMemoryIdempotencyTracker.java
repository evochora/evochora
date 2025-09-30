package org.evochora.datapipeline.resources.idempotency;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.evochora.datapipeline.resources.AbstractResource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p><strong>Configuration Options:</strong>
 * <ul>
 *   <li><b>ttlSeconds</b>: Time to track each ID in seconds (default: 3600)</li>
 *   <li><b>cleanupThresholdMessages</b>: Cleanup after N operations (default: 100)</li>
 *   <li><b>cleanupIntervalSeconds</b>: OR cleanup every N seconds (default: 60)</li>
 * </ul>
 * </p>
 *
 * <p>For distributed deployments, use a Redis-based or DynamoDB-based implementation.</p>
 *
 * @param <K> The type of the idempotency key.
 */
public class InMemoryIdempotencyTracker<K> extends AbstractResource implements IIdempotencyTracker<K> {

    private final ConcurrentHashMap<K, Instant> processedKeys;
    private final Duration ttl;
    private final long cleanupThreshold;
    private final long cleanupIntervalSeconds;
    private final AtomicLong operationsSinceLastCleanup;
    private final AtomicReference<Instant> lastCleanupTime;

    /**
     * Constructs an InMemoryIdempotencyTracker from configuration.
     * This constructor is used by ServiceManager for resource instantiation.
     *
     * @param name    The resource name from configuration.
     * @param options The TypeSafe Config object containing tracker options.
     */
    public InMemoryIdempotencyTracker(String name, Config options) {
        super(name, options);

        // Set defaults
        Config defaults = ConfigFactory.parseMap(Map.of(
            "ttlSeconds", 3600,
            "cleanupThresholdMessages", 100,
            "cleanupIntervalSeconds", 60
        ));
        Config config = options.withFallback(defaults);

        // Parse configuration
        long ttlSeconds = config.getLong("ttlSeconds");
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.cleanupThreshold = config.getLong("cleanupThresholdMessages");
        this.cleanupIntervalSeconds = config.getLong("cleanupIntervalSeconds");

        // Initialize state
        this.processedKeys = new ConcurrentHashMap<>();
        this.operationsSinceLastCleanup = new AtomicLong(0);
        this.lastCleanupTime = new AtomicReference<>(Instant.now());
    }

    /**
     * Legacy constructor for tests. Constructs an InMemoryIdempotencyTracker with the specified TTL.
     *
     * @param ttl The time-to-live for processed keys.
     */
    public InMemoryIdempotencyTracker(Duration ttl) {
        this(ttl, 100, 60);
    }

    /**
     * Legacy constructor for tests. Constructs an InMemoryIdempotencyTracker with specified parameters.
     *
     * @param ttl               The time-to-live for processed keys.
     * @param cleanupThreshold  The number of operations between automatic cleanup runs.
     * @param cleanupIntervalSeconds  The maximum seconds between cleanup runs.
     */
    public InMemoryIdempotencyTracker(Duration ttl, long cleanupThreshold, long cleanupIntervalSeconds) {
        super("test-tracker", ConfigFactory.empty());
        this.processedKeys = new ConcurrentHashMap<>();
        this.ttl = ttl;
        this.cleanupThreshold = cleanupThreshold;
        this.cleanupIntervalSeconds = cleanupIntervalSeconds;
        this.operationsSinceLastCleanup = new AtomicLong(0);
        this.lastCleanupTime = new AtomicReference<>(Instant.now());
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
        operationsSinceLastCleanup.set(0);
        lastCleanupTime.set(Instant.now());
    }

    @Override
    public long size() {
        return processedKeys.size();
    }

    @Override
    public synchronized void cleanup() {
        Instant cutoff = Instant.now().minus(ttl);
        processedKeys.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        operationsSinceLastCleanup.set(0);
        lastCleanupTime.set(Instant.now());
    }

    /**
     * Performs cleanup if either the operation threshold or time threshold has been reached.
     * This is called automatically on each operation to prevent unbounded growth.
     * Thread-safe: uses atomic operations to ensure only one thread triggers cleanup.
     */
    private void maybeCleanup() {
        long currentOps = operationsSinceLastCleanup.incrementAndGet();

        // Trigger 1: Operation count threshold
        if (currentOps >= cleanupThreshold) {
            cleanup();
            return;
        }

        // Trigger 2: Time-based threshold (check on every operation, but only one thread wins)
        Instant lastCleanup = lastCleanupTime.get();
        long secondsSinceLastCleanup = Duration.between(lastCleanup, Instant.now()).getSeconds();

        if (secondsSinceLastCleanup >= cleanupIntervalSeconds) {
            // Use compareAndSet to ensure only one thread triggers time-based cleanup
            if (lastCleanupTime.compareAndSet(lastCleanup, Instant.now())) {
                cleanup();
            }
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
                size(), ttl, operationsSinceLastCleanup.get());
    }

    @Override
    public UsageState getUsageState(String usageType) {
        // Idempotency trackers are always active - they don't have waiting or blocking states
        // like queues do. They're simple lookup structures that are always ready.
        return UsageState.ACTIVE;
    }
}