package org.evochora.datapipeline.resources.monitoring;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Monitoring resource that exposes JVM-wide memory and runtime metrics with optional historical tracking.
 * <p>
 * This resource provides both live JVM statistics and optional historical aggregates (max/avg) over a
 * configurable time window using {@link SlidingWindowCounter}. Historical tracking can be enabled
 * via background sampling or on-demand during metrics collection.
 * <p>
 * <strong>Usage in evochora.conf:</strong>
 * <pre>
 * pipeline {
 *   resources {
 *     jvm-memory {
 *       className = "org.evochora.datapipeline.resources.monitoring.JvmMemoryMonitor"
 *       options {
 *         # Time window for historical metrics (default: 60 seconds)
 *         # Set to 0 to disable historical tracking (live metrics only)
 *         windowSeconds = 60
 *         
 *         # Sampling interval for background thread (default: 0 = disabled)
 *         # Set to 1+ to enable background sampling for accurate historical data
 *         # Set to 0 to use on-demand sampling (during getMetrics() calls only)
 *         sampleIntervalSeconds = 0
 *       }
 *     }
 *   }
 * }
 * </pre>
 * <p>
 * <strong>Sampling Modes:</strong>
 * <ul>
 *   <li><strong>Background Sampling (sampleIntervalSeconds > 0):</strong> Dedicated thread samples
 *       metrics at regular intervals. Provides accurate historical data independent of API polling.</li>
 *   <li><strong>On-Demand Sampling (sampleIntervalSeconds = 0):</strong> Metrics are sampled only
 *       when {@link #getMetrics()} is called. Historical data reflects API polling frequency.</li>
 *   <li><strong>Live Only (windowSeconds = 0):</strong> No historical tracking, only live metrics.</li>
 * </ul>
 * <p>
 * <strong>Exposed Metrics:</strong>
 * <ul>
 *   <li><strong>Live Heap:</strong> jvm_heap_used_mb, jvm_heap_used_percent, jvm_heap_committed_mb</li>
 *   <li><strong>Historical Heap:</strong> jvm_heap_used_mb_max, jvm_heap_used_mb_avg, 
 *       jvm_heap_used_percent_max, jvm_heap_used_percent_avg (if windowSeconds > 0)</li>
 *   <li><strong>Threads:</strong> jvm_thread_count, jvm_thread_count_max, jvm_thread_count_avg</li>
 *   <li><strong>File Descriptors:</strong> os_open_file_descriptors, os_fd_usage_percent, 
 *       os_fd_usage_percent_max, os_fd_usage_percent_avg</li>
 * </ul>
 * <p>
 * <strong>Performance:</strong> O(1) recording. Metrics retrieval is O(1) for live metrics,
 * O(windowSeconds) for historical aggregates (typically fast, O(60) for 60-second window).
 * <p>
 * <strong>Thread Safety:</strong> All operations are thread-safe. Background sampler thread
 * (if enabled) runs at low priority and is automatically stopped on {@link #close()}.
 * <p>
 * <strong>Health Check:</strong> Always healthy (never records errors). Monitor thread failures
 * are logged but don't affect resource health.
 */
public class JvmMemoryMonitor extends AbstractResource implements AutoCloseable {
    
    private static final Logger log = LoggerFactory.getLogger(JvmMemoryMonitor.class);
    
    private final Runtime runtime;
    private final MemoryMXBean memoryBean;
    private final long startTime;
    private final int windowSeconds;
    private final int sampleIntervalSeconds;
    
    // Historical tracking for key metrics (O(1) recording, O(windowSeconds) retrieval)
    private final SlidingWindowCounter heapUsedMbTracker;
    private final SlidingWindowCounter heapUsedPercentTracker;
    private final SlidingWindowCounter threadCountTracker;
    private final SlidingWindowCounter fdUsagePercentTracker;
    
    // Background sampling thread (optional)
    private final Thread samplerThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    
    /**
     * Creates a new JVM memory monitor with optional historical tracking.
     *
     * @param name The resource name (from configuration).
     * @param options Configuration options:
     *                <ul>
     *                  <li>windowSeconds: Time window for historical metrics (default: 60, 0 = disabled)</li>
     *                  <li>sampleIntervalSeconds: Background sampling interval (default: 0 = on-demand only)</li>
     *                </ul>
     */
    public JvmMemoryMonitor(String name, Config options) {
        super(name, options);
        
        // Set defaults
        Config defaults = ConfigFactory.parseMap(Map.of(
            "windowSeconds", 60,  // Default: track last 60 seconds
            "sampleIntervalSeconds", 0  // Default: on-demand sampling (no background thread)
        ));
        Config finalConfig = options.withFallback(defaults);
        
        this.windowSeconds = finalConfig.getInt("windowSeconds");
        this.sampleIntervalSeconds = finalConfig.getInt("sampleIntervalSeconds");
        this.runtime = Runtime.getRuntime();
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.startTime = ManagementFactory.getRuntimeMXBean().getStartTime();
        
        // Initialize sliding window trackers (only if historical tracking enabled)
        if (windowSeconds > 0) {
            this.heapUsedMbTracker = new SlidingWindowCounter(windowSeconds);
            this.heapUsedPercentTracker = new SlidingWindowCounter(windowSeconds);
            this.threadCountTracker = new SlidingWindowCounter(windowSeconds);
            this.fdUsagePercentTracker = new SlidingWindowCounter(windowSeconds);
        } else {
            this.heapUsedMbTracker = null;
            this.heapUsedPercentTracker = null;
            this.threadCountTracker = null;
            this.fdUsagePercentTracker = null;
        }
        
        // Start background sampler thread (only if configured)
        if (sampleIntervalSeconds > 0 && windowSeconds > 0) {
            this.samplerThread = new Thread(this::samplerLoop, "JvmMemoryMonitor-Sampler");
            this.samplerThread.setDaemon(true);
            this.samplerThread.setPriority(Thread.MIN_PRIORITY);  // Low priority, don't interfere with main work
            this.samplerThread.start();
            log.info("JVM memory monitor '{}' started with background sampling (window={}s, sampleInterval={}s)", 
                name, windowSeconds, sampleIntervalSeconds);
        } else if (windowSeconds > 0) {
            this.samplerThread = null;
            log.info("JVM memory monitor '{}' started with on-demand sampling (window={}s)", 
                name, windowSeconds);
        } else {
            this.samplerThread = null;
            log.info("JVM memory monitor '{}' started (live metrics only)", name);
        }
    }
    
    /**
     * Background sampler loop that records JVM metrics at regular intervals.
     * <p>
     * This method runs in a separate daemon thread and samples heap, thread, and FD metrics
     * every {@code sampleIntervalSeconds}. The loop terminates gracefully when {@link #close()}
     * is called or the thread is interrupted.
     */
    private void samplerLoop() {
        log.debug("JVM memory monitor '{}' sampler thread started", getResourceName());
        
        while (running.get()) {
            try {
                // Sample current JVM state
                sampleJvmMetrics();
                
                // Sleep until next sample
                Thread.sleep(sampleIntervalSeconds * 1000L);
                
            } catch (InterruptedException e) {
                log.debug("JVM memory monitor '{}' sampler thread interrupted", getResourceName());
                break;
            } catch (Exception e) {
                log.warn("JVM memory monitor '{}' failed to sample metrics: {}", 
                    getResourceName(), e.getMessage());
                // Continue sampling despite errors
            }
        }
        
        log.debug("JVM memory monitor '{}' sampler thread stopped", getResourceName());
    }
    
    /**
     * Samples current JVM metrics and records them in sliding window trackers.
     * <p>
     * This method is called either by the background sampler thread (if enabled) or
     * during {@link #addCustomMetrics(Map)} for on-demand sampling.
     * All operations are O(1) thanks to {@link SlidingWindowCounter#recordValue(double)}.
     */
    private void sampleJvmMetrics() {
        if (windowSeconds == 0) {
            return;  // Historical tracking disabled
        }
        
        // Sample heap metrics
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        double usedMemoryMb = usedMemory / 1_048_576.0;
        double usedPercent = (usedMemory * 100.0) / maxMemory;
        
        heapUsedMbTracker.recordValue(usedMemoryMb);
        heapUsedPercentTracker.recordValue(usedPercent);
        
        // Sample thread metrics
        int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
        threadCountTracker.recordValue(threadCount);
        
        // Sample file descriptor metrics (Unix only)
        try {
            java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
                long openFDs = unix.getOpenFileDescriptorCount();
                long maxFDs = unix.getMaxFileDescriptorCount();
                double fdUsagePercent = maxFDs > 0 ? (openFDs * 100.0) / maxFDs : 0.0;
                
                fdUsagePercentTracker.recordValue(fdUsagePercent);
            }
        } catch (Exception e) {
            // Ignore - not available on all platforms
        }
    }
    
    /**
     * Adds JVM memory and runtime metrics with optional historical tracking.
     * <p>
     * This method retrieves live metrics and computes historical aggregates (max/avg)
     * from the sliding window trackers. If on-demand sampling is enabled (no background thread),
     * it also records the current sample.
     *
     * @param metrics Mutable map to add metrics to (already contains base error_count from AbstractResource).
     */
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        // On-demand sampling (if no background thread)
        if (samplerThread == null && windowSeconds > 0) {
            sampleJvmMetrics();
        }
        
        // Live heap metrics from Runtime
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        // Detailed heap usage via MemoryMXBean
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long committedMemory = heapUsage.getCommitted();
        
        // Calculate derived metrics
        double usedMemoryMb = usedMemory / 1_048_576.0;
        double usedPercent = (usedMemory * 100.0) / maxMemory;
        
        // --- LIVE HEAP METRICS ---
        metrics.put("jvm_heap_max_bytes", maxMemory);
        metrics.put("jvm_heap_committed_bytes", committedMemory);
        metrics.put("jvm_heap_used_bytes", usedMemory);
        metrics.put("jvm_heap_free_bytes", maxMemory - usedMemory);
        
        metrics.put("jvm_heap_max_mb", maxMemory / 1_048_576.0);
        metrics.put("jvm_heap_committed_mb", committedMemory / 1_048_576.0);
        metrics.put("jvm_heap_used_mb", usedMemoryMb);
        metrics.put("jvm_heap_free_mb", (maxMemory - usedMemory) / 1_048_576.0);
        
        metrics.put("jvm_heap_used_percent", usedPercent);
        metrics.put("jvm_heap_committed_percent", (committedMemory * 100.0) / maxMemory);
        
        // --- HISTORICAL HEAP METRICS (only if tracking enabled) ---
        if (windowSeconds > 0) {
            metrics.put("jvm_heap_used_mb_max", heapUsedMbTracker.getWindowMax());
            metrics.put("jvm_heap_used_mb_avg", heapUsedMbTracker.getWindowAverage());
            metrics.put("jvm_heap_used_percent_max", heapUsedPercentTracker.getWindowMax());
            metrics.put("jvm_heap_used_percent_avg", heapUsedPercentTracker.getWindowAverage());
        }
        
        // --- THREAD METRICS ---
        int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
        int peakThreadCount = ManagementFactory.getThreadMXBean().getPeakThreadCount();
        int daemonThreadCount = ManagementFactory.getThreadMXBean().getDaemonThreadCount();
        
        metrics.put("jvm_thread_count", threadCount);
        metrics.put("jvm_thread_peak_count", peakThreadCount);
        metrics.put("jvm_daemon_thread_count", daemonThreadCount);
        
        if (windowSeconds > 0) {
            metrics.put("jvm_thread_count_max", threadCountTracker.getWindowMax());
            metrics.put("jvm_thread_count_avg", threadCountTracker.getWindowAverage());
        }
        
        // --- FILE DESCRIPTOR METRICS (Unix-like systems only) ---
        try {
            java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
                long openFDs = unix.getOpenFileDescriptorCount();
                long maxFDs = unix.getMaxFileDescriptorCount();
                double fdUsagePercent = maxFDs > 0 ? (openFDs * 100.0) / maxFDs : 0.0;
                
                metrics.put("os_open_file_descriptors", openFDs);
                metrics.put("os_max_file_descriptors", maxFDs);
                metrics.put("os_fd_usage_percent", fdUsagePercent);
                
                if (windowSeconds > 0) {
                    metrics.put("os_fd_usage_percent_max", fdUsagePercentTracker.getWindowMax());
                    metrics.put("os_fd_usage_percent_avg", fdUsagePercentTracker.getWindowAverage());
                }
            }
        } catch (Exception e) {
            // Ignore - not available on all platforms (Windows)
        }
        
        // --- RUNTIME METRICS ---
        long uptimeMs = System.currentTimeMillis() - startTime;
        metrics.put("jvm_uptime_seconds", uptimeMs / 1000.0);
        
        // Configuration metadata
        metrics.put("metrics_window_seconds", windowSeconds);
        if (windowSeconds > 0) {
            metrics.put("metrics_sample_interval_seconds", sampleIntervalSeconds);
            metrics.put("metrics_background_sampling", sampleIntervalSeconds > 0 ? 1 : 0);
        }
    }
    
    /**
     * Returns the usage state for this monitoring resource.
     * <p>
     * JvmMemoryMonitor is always active (live queries always work, background sampler
     * continuously runs if enabled).
     *
     * @param usageType The usage type (ignored for this resource).
     * @return Always {@link UsageState#ACTIVE}.
     */
    @Override
    public IResource.UsageState getUsageState(String usageType) {
        return IResource.UsageState.ACTIVE;
    }
    
    /**
     * Stops the background sampler thread (if running) and releases resources.
     * <p>
     * This method is called automatically by the resource lifecycle manager
     * when the pipeline is shut down.
     *
     * @throws Exception if thread interruption fails (unlikely).
     */
    @Override
    public void close() throws Exception {
        if (samplerThread != null) {
            log.debug("Stopping JVM memory monitor '{}'", getResourceName());
            running.set(false);
            samplerThread.interrupt();
            
            try {
                samplerThread.join(2000);  // Wait up to 2 seconds for clean shutdown
                if (samplerThread.isAlive()) {
                    log.warn("JVM memory monitor '{}' sampler thread did not stop within timeout", getResourceName());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("Interrupted while waiting for JVM memory monitor '{}' to stop", getResourceName());
            }
        }
    }
}
