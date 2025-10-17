package org.evochora.datapipeline.resources.idempotency;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.resources.AbstractResource;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-size in-memory idempotency tracker with GUARANTEED memory bounds.
 * <p>
 * This implementation uses a synchronized {@link LinkedHashMap} with FIFO eviction
 * to provide predictable memory usage without cleanup threads or TTL management.
 * <p>
 * <strong>Characteristics:</strong>
 * <ul>
 *   <li>Thread-safe via synchronization</li>
 *   <li>Automatic FIFO eviction when maxKeys reached</li>
 *   <li>O(1) operations for insert/lookup/evict</li>
 *   <li>GUARANTEED memory limit (no surprises!)</li>
 *   <li>No cleanup threads, no periodic scanning</li>
 * </ul>
 * <p>
 * <strong>Window Size:</strong><br>
 * At 100k TPS with maxKeys=10M: window = 100 seconds<br>
 * At 163k TPS with maxKeys=10M: window = 61 seconds<br>
 * At 200k TPS with maxKeys=10M: window = 50 seconds
 * <p>
 * <strong>Memory Usage:</strong><br>
 * 10M keys ≈ 1.3 GB RAM (String keys ~130 bytes each)
 * <p>
 * <strong>Configuration Options:</strong>
 * <ul>
 *   <li><b>maxKeys</b>: Maximum number of keys to track (default: 10000000)</li>
 *   <li><b>initialCapacity</b>: Initial map capacity (default: maxKeys)</li>
 * </ul>
 * <p>
 * <strong>Limitations:</strong>
 * <ul>
 *   <li>Not suitable for distributed/multi-instance deployments</li>
 *   <li>Data lost on process restart</li>
 *   <li>False negatives possible if same key appears after &gt;maxKeys messages</li>
 * </ul>
 * <p>
 * For distributed deployments, use a Redis-based or DynamoDB-based implementation.
 *
 * @param <K> The type of the idempotency key.
 */
public class InMemoryIdempotencyTracker<K> extends AbstractResource implements IIdempotencyTracker<K> {

    // Fixed-size FIFO map with automatic eviction
    private final LinkedHashMap<K, Boolean> trackedKeys;
    private final int maxKeys;

    // Metrics - zero overhead counters for monitoring
    private final AtomicLong totalChecks = new AtomicLong(0);
    private final AtomicLong totalDuplicates = new AtomicLong(0);
    private final AtomicLong totalEvictions = new AtomicLong(0);

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
            "maxKeys", 10_000_000,  // 10M keys ≈ 1.3 GB RAM
            "initialCapacity", 10_000_000
        ));
        Config config = options.withFallback(defaults);

        // Parse configuration
        this.maxKeys = config.getInt("maxKeys");
        int initialCapacity = config.getInt("initialCapacity");
        float loadFactor = 0.75f;

        // Create fixed-size map with FIFO eviction
        this.trackedKeys = new LinkedHashMap<K, Boolean>(initialCapacity, loadFactor, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Boolean> eldest) {
                boolean shouldRemove = size() > maxKeys;
                if (shouldRemove) {
                    totalEvictions.incrementAndGet();
                }
                return shouldRemove;
            }
        };
    }

    /**
     * Test constructor for unit tests.
     *
     * @param maxKeys Maximum number of keys to track before FIFO eviction.
     */
    public InMemoryIdempotencyTracker(int maxKeys) {
        super("test-tracker", ConfigFactory.empty());
        this.maxKeys = maxKeys;
        float loadFactor = 0.75f;

        this.trackedKeys = new LinkedHashMap<K, Boolean>(maxKeys, loadFactor, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Boolean> eldest) {
                boolean shouldRemove = size() > maxKeys;
                if (shouldRemove) {
                    totalEvictions.incrementAndGet();
                }
                return shouldRemove;
            }
        };
    }

    /**
     * Legacy constructor for backward compatibility with tests using Duration TTL.
     * <p>
     * <strong>NOTE:</strong> TTL is no longer used. This constructor calculates an equivalent
     * maxKeys based on an assumed throughput of 100k TPS.
     *
     * @param ttl The time-to-live (converted to maxKeys assuming 100k TPS).
     * @deprecated Use {@link #InMemoryIdempotencyTracker(int)} instead.
     */
    @Deprecated
    public InMemoryIdempotencyTracker(Duration ttl) {
        this((int) (ttl.toSeconds() * 100_000));  // Assume 100k TPS for backward compat
    }

    @Override
    public synchronized boolean isProcessed(K key) {
        totalChecks.incrementAndGet();
        return trackedKeys.containsKey(key);
    }

    @Override
    public synchronized boolean checkAndMarkProcessed(K key) {
        totalChecks.incrementAndGet();

        if (trackedKeys.containsKey(key)) {
            // Key already exists - duplicate detected
            totalDuplicates.incrementAndGet();
            return false;
        }

        // Key not present - mark as processed
        trackedKeys.put(key, Boolean.TRUE);
        return true;
    }

    @Override
    public synchronized void markProcessed(K key) {
        trackedKeys.put(key, Boolean.TRUE);
    }

    @Override
    public synchronized boolean remove(K key) {
        return trackedKeys.remove(key) != null;
    }

    @Override
    public synchronized void clear() {
        trackedKeys.clear();
        totalEvictions.set(0);
    }

    @Override
    public synchronized long size() {
        return trackedKeys.size();
    }

    @Override
    public void cleanup() {
        // No-op: Cleanup happens automatically via removeEldestEntry()
    }

    /**
     * Gets the maximum capacity of this tracker.
     *
     * @return The maximum number of keys that can be tracked.
     */
    public int getMaxKeys() {
        return maxKeys;
    }

    /**
     * Gets the total number of evictions that have occurred.
     *
     * @return The total eviction count.
     */
    public long getTotalEvictions() {
        return totalEvictions.get();
    }

    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        metrics.put("total_checks", totalChecks.get());
        metrics.put("total_duplicates_detected", totalDuplicates.get());
        metrics.put("tracked_keys", size());
        metrics.put("max_keys", maxKeys);
        metrics.put("total_evictions", totalEvictions.get());
        
        // Calculate capacity utilization
        double utilization = (size() * 100.0) / maxKeys;
        metrics.put("capacity_utilization_percent", utilization);
    }

    @Override
    public UsageState getUsageState(String usageType) {
        // Always active (no state transitions)
        return UsageState.ACTIVE;
    }
}
