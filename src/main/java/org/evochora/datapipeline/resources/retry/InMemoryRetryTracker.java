package org.evochora.datapipeline.resources.retry;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IRetryTracker;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.resources.AbstractResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory implementation of retry tracker with bounded memory guarantee.
 * <p>
 * Suitable for single-instance and multi-instance deployments with in-process coordination.
 * <p>
 * <strong>Memory Management (Defense in Depth):</strong>
 * <ul>
 *   <li><strong>Active Cleanup:</strong> {@code resetRetryCount()} and {@code markMovedToDlq()} 
 *       remove entries immediately (normal case: ~0 MB usage)</li>
 *   <li><strong>FIFO Safety Net:</strong> Ring buffer with maxKeys ensures bounded memory 
 *       even if cleanup is forgotten or fails</li>
 *   <li><strong>Guaranteed maximum:</strong> 100k entries × 232 bytes = ~23 MB worst-case</li>
 * </ul>
 * <p>
 * <strong>Typical Memory Usage:</strong>
 * <ul>
 *   <li>Normal (99% success): ~0 MB (entries removed via cleanup)</li>
 *   <li>1% failure rate, 100k batches/day: ~0.23 MB (transient retries)</li>
 *   <li>10k poison messages/year: ~2.3 MB (movedToDlq tracking)</li>
 *   <li>Worst-case guarantee: ~23 MB maximum (FIFO protection)</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong> All methods are synchronized for thread-safe access
 * from multiple competing consumers.
 * <p>
 * <strong>Limitation:</strong> Retry counts are lost on restart.
 * For production with persistence requirements, use H2RetryTracker.
 */
public class InMemoryRetryTracker extends AbstractResource implements IRetryTracker, IMemoryEstimatable {
    
    private static final Logger log = LoggerFactory.getLogger(InMemoryRetryTracker.class);
    
    // Ring buffer for FIFO eviction (Safety Net - Option B)
    private final Object[] ringBuffer;
    private int writeIndex = 0;
    private final int maxKeys;
    
    // Active tracking (Option A - with active cleanup)
    private final ConcurrentHashMap<String, AtomicInteger> retryCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastRetryAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> movedToDlq = new ConcurrentHashMap<>();
    
    // Metrics - zero overhead counters for monitoring
    private final AtomicLong totalRetries = new AtomicLong(0);
    private final AtomicLong totalEvictions = new AtomicLong(0);
    
    /**
     * Constructs an InMemoryRetryTracker from configuration.
     * This constructor is used by ServiceManager for resource instantiation.
     *
     * @param name    The resource name from configuration.
     * @param options The TypeSafe Config object containing tracker options.
     */
    public InMemoryRetryTracker(String name, Config options) {
        super(name, options);
        
        // Set defaults
        Config defaults = ConfigFactory.parseMap(Map.of(
            "maxKeys", 100_000,  // 100k keys ≈ 23 MB RAM (worst-case with ring buffer)
            "initialCapacity", 100_000
        ));
        Config config = options.withFallback(defaults);
        
        // Parse configuration
        this.maxKeys = config.getInt("maxKeys");
        int initialCapacity = config.getInt("initialCapacity");
        
        // Create ring buffer for FIFO eviction (Safety Net)
        this.ringBuffer = new Object[maxKeys];
        
        // Create HashMaps with configured capacity
        // Note: ConcurrentHashMap capacity is approximate (load factor applies)
    }
    
    /**
     * Test constructor for unit tests.
     *
     * @param maxKeys Maximum number of keys to track before FIFO eviction.
     */
    public InMemoryRetryTracker(int maxKeys) {
        super("test-retry-tracker", ConfigFactory.empty());
        this.maxKeys = maxKeys;
        this.ringBuffer = new Object[maxKeys];
    }
    
    @Override
    public synchronized int incrementAndGetRetryCount(String messageId) throws Exception {
        if (messageId == null) {
            throw new IllegalArgumentException("messageId must not be null");
        }
        
        // Update timestamp
        lastRetryAt.put(messageId, System.currentTimeMillis());
        
        // Increment retry count
        int newCount = retryCounts.computeIfAbsent(messageId, k -> new AtomicInteger(0))
            .incrementAndGet();
        
        // Track in ring buffer for FIFO eviction (Safety Net)
        evictOldestIfNeeded(messageId);
        
        totalRetries.incrementAndGet();
        return newCount;
    }
    
    @Override
    public synchronized int getRetryCount(String messageId) throws Exception {
        if (messageId == null) {
            throw new IllegalArgumentException("messageId must not be null");
        }
        
        AtomicInteger count = retryCounts.get(messageId);
        return count != null ? count.get() : 0;
    }
    
    @Override
    public synchronized void markMovedToDlq(String messageId) throws Exception {
        if (messageId == null) {
            throw new IllegalArgumentException("messageId must not be null");
        }
        
        // Mark as moved (useful for monitoring)
        movedToDlq.put(messageId, true);
        
        // CRITICAL: Active cleanup to prevent memory growth (Option A)
        retryCounts.remove(messageId);
        lastRetryAt.remove(messageId);
    }
    
    @Override
    public synchronized void resetRetryCount(String messageId) throws Exception {
        if (messageId == null) {
            throw new IllegalArgumentException("messageId must not be null");
        }
        
        // Active cleanup (Option A)
        retryCounts.remove(messageId);
        lastRetryAt.remove(messageId);
    }
    
    /**
     * FIFO eviction helper (Safety Net - Option B).
     * <p>
     * Called by incrementAndGetRetryCount to ensure bounded memory.
     * Evicts oldest entry if ring buffer is full.
     *
     * @param messageId Message being added to ring buffer
     */
    private void evictOldestIfNeeded(String messageId) {
        // Check if ring buffer slot is occupied
        String oldKey = (String) ringBuffer[writeIndex];
        if (oldKey != null) {
            // Evict oldest entry from all maps
            retryCounts.remove(oldKey);
            lastRetryAt.remove(oldKey);
            movedToDlq.remove(oldKey);
            totalEvictions.incrementAndGet();
        }
        
        // Write new key to ring buffer
        ringBuffer[writeIndex] = messageId;
        
        // Advance write index (wrap around at maxKeys)
        writeIndex = (writeIndex + 1) % maxKeys;
    }
    
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        metrics.put("tracked_messages", retryCounts.size());
        metrics.put("max_keys", maxKeys);
        metrics.put("dlq_moved_count", movedToDlq.size());
        metrics.put("total_retries", totalRetries.get());
        metrics.put("total_evictions", totalEvictions.get());
        
        // Calculate capacity utilization
        double utilization = (retryCounts.size() * 100.0) / maxKeys;
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
     * Estimates memory for the retry tracker at full capacity.
     * <p>
     * <strong>Memory structure:</strong>
     * <ul>
     *   <li>Ring buffer: Object[] with maxKeys entries (8 bytes per reference)</li>
     *   <li>retryCounts: ConcurrentHashMap with AtomicInteger values (~100 bytes per entry)</li>
     *   <li>lastRetryAt: ConcurrentHashMap with Long values (~80 bytes per entry)</li>
     *   <li>movedToDlq: ConcurrentHashMap with Boolean values (~80 bytes per entry)</li>
     *   <li>Total: ~232 bytes per key (worst-case when all maps have same keys)</li>
     * </ul>
     * <p>
     * This estimate is independent of simulation parameters since the tracker
     * has a fixed maximum size regardless of environment size.
     */
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // ~232 bytes per key: ring buffer + 3x ConcurrentHashMap entries
        long bytesPerKey = 232;
        long totalBytes = (long) maxKeys * bytesPerKey;
        
        String explanation = String.format("%d maxKeys × ~232 bytes/key (ring buffer + 3 ConcurrentHashMaps)",
            maxKeys);
        
        return List.of(new MemoryEstimate(
            getResourceName(),
            totalBytes,
            explanation,
            MemoryEstimate.Category.TRACKER
        ));
    }
}
