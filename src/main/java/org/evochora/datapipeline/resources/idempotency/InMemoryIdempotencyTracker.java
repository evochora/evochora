package org.evochora.datapipeline.resources.idempotency;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.resources.AbstractResource;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
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
 *   <li><b>ttlSeconds</b>: Time to track each ID in seconds (default: 60)</li>
 *   <li><b>cleanupThresholdMessages</b>: Cleanup after N operations (default: 10000)</li>
 *   <li><b>cleanupIntervalSeconds</b>: OR cleanup every N seconds (default: 60)</li>
 * </ul>
 * </p>
 *
 * <p><strong>Performance Tuning:</strong>
 * Bucket granularity is calculated automatically as TTL/10, creating ~10 time buckets
 * for efficient cleanup. With bucket-based cleanup running at O(buckets) instead of O(keys),
 * the cleanup operation is negligible even at high throughput, so cleanup thresholds
 * optimize for reduced overhead rather than memory usage.
 * </p>
 *
 * <p>For distributed deployments, use a Redis-based or DynamoDB-based implementation.</p>
 *
 * @param <K> The type of the idempotency key.
 */
public class InMemoryIdempotencyTracker<K> extends AbstractResource implements IIdempotencyTracker<K> {

    // Dual-index structure for high-performance idempotency tracking
    private final ConcurrentHashMap<K, Long> keyToTimestamp;  // Key → epoch millis (precise expiration)
    private final ConcurrentHashMap<Long, ConcurrentHashMap.KeySetView<K, Boolean>> bucketToKeys;  // Bucket → keys (efficient cleanup)

    private final long ttlMillis;
    private final long bucketGranularityMillis;
    private final long cleanupThreshold;
    private final long cleanupIntervalSeconds;
    private final AtomicLong operationsSinceLastCleanup;
    private final AtomicReference<Instant> lastCleanupTime;

    // Metrics - zero overhead counters for monitoring
    private final AtomicLong totalChecks = new AtomicLong(0);
    private final AtomicLong totalDuplicates = new AtomicLong(0);

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
            "ttlSeconds", 60,  // Default 1 minute (was 1 hour) - sufficient for retries
            "cleanupThresholdMessages", 10000,  // Cleanup every ~10 seconds at 1k TPS
            "cleanupIntervalSeconds", 60
        ));
        Config config = options.withFallback(defaults);

        // Parse configuration
        long ttlSeconds = config.getLong("ttlSeconds");

        this.ttlMillis = ttlSeconds * 1000;

        // Calculate adaptive bucket granularity based on TTL:
        // - Use TTL/10 to have ~10 buckets across the TTL window (optimal balance)
        // - Minimum 10ms (avoid excessive bucket proliferation for very short TTLs)
        // - Maximum 60 seconds (efficient for production use cases)
        this.bucketGranularityMillis = Math.max(10, Math.min(60_000, ttlMillis / 10));

        this.cleanupThreshold = config.getLong("cleanupThresholdMessages");
        this.cleanupIntervalSeconds = config.getLong("cleanupIntervalSeconds");

        // Initialize dual-index structure
        this.keyToTimestamp = new ConcurrentHashMap<>();
        this.bucketToKeys = new ConcurrentHashMap<>();
        this.operationsSinceLastCleanup = new AtomicLong(0);
        this.lastCleanupTime = new AtomicReference<>(Instant.now());
    }

    /**
     * Legacy constructor for tests. Constructs an InMemoryIdempotencyTracker with the specified TTL.
     * Bucket granularity is automatically calculated based on TTL for optimal cleanup performance.
     *
     * @param ttl The time-to-live for processed keys.
     */
    public InMemoryIdempotencyTracker(Duration ttl) {
        this(ttl, 100, 60);
    }

    /**
     * Legacy constructor for tests. Constructs an InMemoryIdempotencyTracker with specified parameters.
     * Bucket granularity is automatically calculated based on TTL for optimal cleanup performance.
     *
     * @param ttl               The time-to-live for processed keys.
     * @param cleanupThreshold  The number of operations between automatic cleanup runs.
     * @param cleanupIntervalSeconds  The maximum seconds between cleanup runs.
     */
    public InMemoryIdempotencyTracker(Duration ttl, long cleanupThreshold, long cleanupIntervalSeconds) {
        super("test-tracker", ConfigFactory.empty());
        this.keyToTimestamp = new ConcurrentHashMap<>();
        this.bucketToKeys = new ConcurrentHashMap<>();
        this.ttlMillis = ttl.toMillis();

        // Calculate adaptive bucket granularity (same formula as production constructor)
        this.bucketGranularityMillis = Math.max(10, Math.min(60_000, ttlMillis / 10));

        this.cleanupThreshold = cleanupThreshold;
        this.cleanupIntervalSeconds = cleanupIntervalSeconds;
        this.operationsSinceLastCleanup = new AtomicLong(0);
        this.lastCleanupTime = new AtomicReference<>(Instant.now());
    }

    @Override
    public boolean isProcessed(K key) {
        totalChecks.incrementAndGet();
        maybeCleanup();
        Long timestamp = keyToTimestamp.get(key);
        if (timestamp == null) {
            return false;
        }
        // Check if the entry has expired
        long nowMillis = Instant.now().toEpochMilli();
        if (nowMillis - timestamp > ttlMillis) {
            // Expired - remove from both indexes
            keyToTimestamp.remove(key);
            long bucketKey = timestamp / bucketGranularityMillis;
            ConcurrentHashMap.KeySetView<K, Boolean> bucket = bucketToKeys.get(bucketKey);
            if (bucket != null) {
                bucket.remove(key);
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean checkAndMarkProcessed(K key) {
        totalChecks.incrementAndGet();
        maybeCleanup();
        long nowMillis = Instant.now().toEpochMilli();
        long bucketKey = nowMillis / bucketGranularityMillis;

        // putIfAbsent returns null if the key wasn't present, or the existing value if it was
        Long existingTimestamp = keyToTimestamp.putIfAbsent(key, nowMillis);

        if (existingTimestamp == null) {
            // Key was not present - add to bucket and mark as processed
            bucketToKeys.computeIfAbsent(bucketKey, k -> ConcurrentHashMap.newKeySet()).add(key);
            return true;
        }

        // Key exists - check if it has expired
        if (nowMillis - existingTimestamp > ttlMillis) {
            // Entry has expired - update timestamp
            keyToTimestamp.put(key, nowMillis);

            // Remove from old bucket (best effort - old bucket might be cleaned already)
            long oldBucketKey = existingTimestamp / bucketGranularityMillis;
            ConcurrentHashMap.KeySetView<K, Boolean> oldBucket = bucketToKeys.get(oldBucketKey);
            if (oldBucket != null) {
                oldBucket.remove(key);
            }

            // Add to new bucket
            bucketToKeys.computeIfAbsent(bucketKey, k -> ConcurrentHashMap.newKeySet()).add(key);
            return true;
        }

        // Key exists and hasn't expired - this is a duplicate
        totalDuplicates.incrementAndGet();
        return false;
    }

    @Override
    public void markProcessed(K key) {
        maybeCleanup();
        long nowMillis = Instant.now().toEpochMilli();
        long bucketKey = nowMillis / bucketGranularityMillis;

        keyToTimestamp.put(key, nowMillis);
        bucketToKeys.computeIfAbsent(bucketKey, k -> ConcurrentHashMap.newKeySet()).add(key);
    }

    @Override
    public boolean remove(K key) {
        Long timestamp = keyToTimestamp.remove(key);
        if (timestamp != null) {
            // Remove from bucket index
            long bucketKey = timestamp / bucketGranularityMillis;
            ConcurrentHashMap.KeySetView<K, Boolean> bucket = bucketToKeys.get(bucketKey);
            if (bucket != null) {
                bucket.remove(key);
            }
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        keyToTimestamp.clear();
        bucketToKeys.clear();
        operationsSinceLastCleanup.set(0);
        lastCleanupTime.set(Instant.now());
    }

    @Override
    public long size() {
        return keyToTimestamp.size();
    }

    @Override
    public synchronized void cleanup() {
        long nowMillis = Instant.now().toEpochMilli();
        long cutoffMillis = nowMillis - ttlMillis;
        long cutoffBucket = cutoffMillis / bucketGranularityMillis;

        // Remove all buckets older than cutoff - O(buckets) not O(keys)!
        bucketToKeys.keySet().removeIf(bucketKey -> {
            if (bucketKey < cutoffBucket) {
                // Remove all keys in this bucket from primary index
                ConcurrentHashMap.KeySetView<K, Boolean> keys = bucketToKeys.remove(bucketKey);
                if (keys != null) {
                    keys.forEach(keyToTimestamp::remove);
                }
                return true;
            }
            return false;
        });

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
        return Duration.ofMillis(ttlMillis);
    }

    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Include parent metrics
        metrics.put("total_checks", totalChecks.get());
        metrics.put("total_duplicates_detected", totalDuplicates.get());
        metrics.put("tracked_keys", size());
    }

    @Override
    public UsageState getUsageState(String usageType) {
        // Idempotency trackers are always active - they don't have waiting or blocking states
        // like queues do. They're simple lookup structures that are always ready.
        return UsageState.ACTIVE;
    }
}