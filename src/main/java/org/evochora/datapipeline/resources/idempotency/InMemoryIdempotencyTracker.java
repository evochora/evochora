package org.evochora.datapipeline.resources.idempotency;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.resources.AbstractResource;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-size in-memory idempotency tracker with GUARANTEED memory bounds.
 * <p>
 * This implementation uses a ring buffer (Object array) for FIFO eviction combined with
 * a {@link HashSet} for O(1) duplicate detection. This hybrid approach provides:
 * <ul>
 *   <li>50% memory savings compared to LinkedHashSet (no linked-list overhead)</li>
 *   <li>Clear FIFO semantics via explicit ring buffer</li>
 *   <li>O(1) operations for insert/lookup/evict</li>
 *   <li>GUARANTEED memory limit (no surprises!)</li>
 * </ul>
 * <p>
 * <strong>Characteristics:</strong>
 * <ul>
 *   <li>Thread-safe via synchronization</li>
 *   <li>Automatic FIFO eviction via ring buffer</li>
 *   <li>O(1) operations for all methods</li>
 *   <li>No cleanup threads, no periodic scanning</li>
 *   <li>Memory-optimized: Ring buffer + HashSet</li>
 * </ul>
 * <p>
 * <strong>Window Size:</strong><br>
 * At 100k TPS with maxKeys=5M: window = 50 seconds<br>
 * At 163k TPS with maxKeys=5M: window = 30 seconds<br>
 * At 200k TPS with maxKeys=5M: window = 25 seconds<br>
 * At 100k TPS with maxKeys=50M: window = 500 seconds (8.3 minutes)
 * <p>
 * <strong>Memory Usage (Long keys):</strong><br>
 * 5M keys ≈ 160 MB RAM (~32 bytes per entry, 50% less than LinkedHashSet)<br>
 * 10M keys ≈ 320 MB RAM (~32 bytes per entry)<br>
 * 50M keys ≈ 1.6 GB RAM (~32 bytes per entry)
 * <p>
 * <strong>Configuration Options:</strong>
 * <ul>
 *   <li><b>maxKeys</b>: Maximum number of keys to track (default: 5000000)</li>
 *   <li><b>initialCapacity</b>: Initial HashSet capacity (default: maxKeys)</li>
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
public class InMemoryIdempotencyTracker<K> extends AbstractResource implements IIdempotencyTracker<K>, IMemoryEstimatable {

    // Ring buffer for FIFO eviction (explicit circular array)
    private final Object[] ringBuffer;
    
    // HashSet for O(1) duplicate detection
    private final HashSet<K> seen;
    
    // Current write position in ring buffer (wraps around at maxKeys)
    private int writeIndex = 0;
    
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
            "maxKeys", 5_000_000,  // 5M keys ≈ 160 MB RAM (Long keys with ring buffer)
            "initialCapacity", 5_000_000
        ));
        Config config = options.withFallback(defaults);

        // Parse configuration
        this.maxKeys = config.getInt("maxKeys");
        int initialCapacity = config.getInt("initialCapacity");

        // Create ring buffer for FIFO eviction
        this.ringBuffer = new Object[maxKeys];
        
        // Create HashSet for O(1) duplicate detection
        this.seen = new HashSet<>(initialCapacity);
    }

    /**
     * Test constructor for unit tests.
     *
     * @param maxKeys Maximum number of keys to track before FIFO eviction.
     */
    public InMemoryIdempotencyTracker(int maxKeys) {
        super("test-tracker", ConfigFactory.empty());
        this.maxKeys = maxKeys;

        // Create ring buffer for FIFO eviction
        this.ringBuffer = new Object[maxKeys];
        
        // Create HashSet for O(1) duplicate detection
        this.seen = new HashSet<>(maxKeys);
    }

    @Override
    public synchronized boolean isProcessed(K key) {
        totalChecks.incrementAndGet();
        return seen.contains(key);
    }

    @Override
    public synchronized boolean checkAndMarkProcessed(K key) {
        totalChecks.incrementAndGet();

        // Check if already processed
        if (seen.contains(key)) {
            // Key already exists - duplicate detected
            totalDuplicates.incrementAndGet();
            return false;
        }

        // Evict oldest key if ring buffer slot is occupied
        @SuppressWarnings("unchecked")
        K oldKey = (K) ringBuffer[writeIndex];
        if (oldKey != null) {
            seen.remove(oldKey);
            totalEvictions.incrementAndGet();
        }

        // Write new key to ring buffer and mark as seen
        ringBuffer[writeIndex] = key;
        seen.add(key);
        
        // Advance write index (wrap around at maxKeys)
        writeIndex = (writeIndex + 1) % maxKeys;
        
        return true;
    }

    @Override
    public synchronized void markProcessed(K key) {
        // Skip if already tracked
        if (seen.contains(key)) {
            return;
        }
        
        // Evict oldest key if ring buffer slot is occupied
        @SuppressWarnings("unchecked")
        K oldKey = (K) ringBuffer[writeIndex];
        if (oldKey != null) {
            seen.remove(oldKey);
            totalEvictions.incrementAndGet();
        }

        // Write new key to ring buffer and mark as seen
        ringBuffer[writeIndex] = key;
        seen.add(key);
        
        // Advance write index (wrap around at maxKeys)
        writeIndex = (writeIndex + 1) % maxKeys;
    }

    @Override
    public synchronized boolean remove(K key) {
        // Note: This doesn't remove from ring buffer, only from seen set
        // The key will eventually be overwritten by new entries
        return seen.remove(key);
    }

    @Override
    public synchronized void clear() {
        seen.clear();
        // Clear ring buffer references for GC
        for (int i = 0; i < maxKeys; i++) {
            ringBuffer[i] = null;
        }
        writeIndex = 0;
        totalEvictions.set(0);
    }

    @Override
    public synchronized long size() {
        return seen.size();
    }

    @Override
    public void cleanup() {
        // No-op: Cleanup happens automatically via FIFO eviction in ring buffer
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
    
    // ==================== IMemoryEstimatable ====================
    
    /**
     * {@inheritDoc}
     * <p>
     * Estimates memory for the idempotency tracker at full capacity.
     * <p>
     * <strong>Memory structure:</strong>
     * <ul>
     *   <li>Ring buffer: Object[] with maxKeys entries (8 bytes per reference)</li>
     *   <li>HashSet: Keys + linked entries (~24 bytes per entry)</li>
     *   <li>Total: ~32 bytes per key</li>
     * </ul>
     * <p>
     * This estimate is independent of simulation parameters since the tracker
     * has a fixed maximum size regardless of environment size.
     */
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // ~32 bytes per key: 8B ring buffer reference + 24B HashSet entry
        long bytesPerKey = 32;
        long totalBytes = (long) maxKeys * bytesPerKey;
        
        String explanation = String.format("%d maxKeys × ~32 bytes/key (ring buffer + HashSet)",
            maxKeys);
        
        return List.of(new MemoryEstimate(
            getResourceName(),
            totalBytes,
            explanation,
            MemoryEstimate.Category.TRACKER
        ));
    }
}
