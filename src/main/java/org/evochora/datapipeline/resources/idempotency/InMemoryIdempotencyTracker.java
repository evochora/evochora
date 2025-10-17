package org.evochora.datapipeline.resources.idempotency;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.resources.AbstractResource;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-size in-memory idempotency tracker with GUARANTEED memory bounds.
 * <p>
 * This implementation uses a synchronized {@link LinkedHashSet} with manual FIFO eviction
 * to provide predictable memory usage without cleanup threads or TTL management.
 * <p>
 * <strong>Characteristics:</strong>
 * <ul>
 *   <li>Thread-safe via synchronization</li>
 *   <li>Manual FIFO eviction when maxKeys reached</li>
 *   <li>O(1) operations for insert/lookup/evict</li>
 *   <li>GUARANTEED memory limit (no surprises!)</li>
 *   <li>No cleanup threads, no periodic scanning</li>
 *   <li>Memory-optimized: Set-based (no value objects)</li>
 * </ul>
 * <p>
 * <strong>Window Size:</strong><br>
 * At 100k TPS with maxKeys=5M: window = 50 seconds<br>
 * At 163k TPS with maxKeys=5M: window = 30 seconds<br>
 * At 200k TPS with maxKeys=5M: window = 25 seconds
 * <p>
 * <strong>Memory Usage (Long keys):</strong><br>
 * 5M keys ≈ 360 MB RAM (~72 bytes per entry, default)<br>
 * 10M keys ≈ 720 MB RAM (~72 bytes per entry)
 * <p>
 * <strong>Configuration Options:</strong>
 * <ul>
 *   <li><b>maxKeys</b>: Maximum number of keys to track (default: 5000000)</li>
 *   <li><b>initialCapacity</b>: Initial set capacity (default: maxKeys)</li>
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

    // Fixed-size FIFO set with manual eviction
    private final LinkedHashSet<K> trackedKeys;
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
            "maxKeys", 5_000_000,  // 5M keys ≈ 360 MB RAM (Long keys)
            "initialCapacity", 5_000_000
        ));
        Config config = options.withFallback(defaults);

        // Parse configuration
        this.maxKeys = config.getInt("maxKeys");
        int initialCapacity = config.getInt("initialCapacity");

        // Create fixed-size set (manual FIFO eviction in checkAndMarkProcessed)
        this.trackedKeys = new LinkedHashSet<>(initialCapacity);
    }

    /**
     * Test constructor for unit tests.
     *
     * @param maxKeys Maximum number of keys to track before FIFO eviction.
     */
    public InMemoryIdempotencyTracker(int maxKeys) {
        super("test-tracker", ConfigFactory.empty());
        this.maxKeys = maxKeys;

        // Create fixed-size set (manual FIFO eviction in checkAndMarkProcessed)
        this.trackedKeys = new LinkedHashSet<>(maxKeys);
    }

    @Override
    public synchronized boolean isProcessed(K key) {
        totalChecks.incrementAndGet();
        return trackedKeys.contains(key);
    }

    @Override
    public synchronized boolean checkAndMarkProcessed(K key) {
        totalChecks.incrementAndGet();

        // Check if already processed
        if (trackedKeys.contains(key)) {
            // Key already exists - duplicate detected
            totalDuplicates.incrementAndGet();
            return false;
        }

        // Manual FIFO eviction: remove oldest entry if at capacity
        if (trackedKeys.size() >= maxKeys) {
            Iterator<K> iterator = trackedKeys.iterator();
            if (iterator.hasNext()) {
                iterator.next();      // Get first (oldest) element
                iterator.remove();    // Remove it
                totalEvictions.incrementAndGet();
            }
        }

        // Add new key
        trackedKeys.add(key);
        return true;
    }

    @Override
    public synchronized void markProcessed(K key) {
        // Manual FIFO eviction: remove oldest entry if at capacity
        if (trackedKeys.size() >= maxKeys) {
            Iterator<K> iterator = trackedKeys.iterator();
            if (iterator.hasNext()) {
                iterator.next();      // Get first (oldest) element
                iterator.remove();    // Remove it
                totalEvictions.incrementAndGet();
            }
        }
        trackedKeys.add(key);
    }

    @Override
    public synchronized boolean remove(K key) {
        return trackedKeys.remove(key);
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
