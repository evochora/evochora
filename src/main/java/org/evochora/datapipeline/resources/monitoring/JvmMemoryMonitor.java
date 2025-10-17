package org.evochora.datapipeline.resources.monitoring;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.resources.AbstractResource;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Map;

/**
 * Monitoring resource that exposes JVM-wide memory and runtime metrics.
 * <p>
 * This is a pseudo-resource that provides runtime statistics for monitoring
 * and debugging. It does not allocate any resources or require cleanup.
 * All metrics are queried live from the JVM on each {@link #getMetrics()} call.
 * <p>
 * <strong>Usage in evochora.conf:</strong>
 * <pre>
 * pipeline {
 *   resources {
 *     jvm-memory {
 *       className = "org.evochora.datapipeline.resources.monitoring.JvmMemoryMonitor"
 *       options { }
 *     }
 *   }
 * }
 * </pre>
 * <p>
 * <strong>Exposed Metrics:</strong>
 * <ul>
 *   <li><strong>Heap (bytes):</strong> jvm_heap_max_bytes, jvm_heap_committed_bytes, 
 *       jvm_heap_used_bytes, jvm_heap_free_bytes</li>
 *   <li><strong>Heap (MB):</strong> jvm_heap_max_mb, jvm_heap_committed_mb, 
 *       jvm_heap_used_mb, jvm_heap_free_mb</li>
 *   <li><strong>Heap (%):</strong> jvm_heap_used_percent (used/max), 
 *       jvm_heap_committed_percent (committed/max)</li>
 *   <li><strong>Threads:</strong> jvm_thread_count (total), jvm_thread_peak_count (high water mark), 
 *       jvm_daemon_thread_count (background threads)</li>
 *   <li><strong>File Descriptors (Unix only):</strong> os_open_file_descriptors, 
 *       os_max_file_descriptors, os_fd_usage_percent</li>
 *   <li><strong>Runtime:</strong> jvm_uptime_seconds</li>
 * </ul>
 * <p>
 * <strong>Performance:</strong> All metrics are O(1) live queries with negligible overhead.
 * No state tracking, no recording, no memory allocation during metrics collection.
 * <p>
 * <strong>Health Check:</strong> Always healthy (never records errors). Heap usage warnings
 * should be implemented client-side based on jvm_heap_used_percent metric.
 */
public class JvmMemoryMonitor extends AbstractResource {
    
    private final Runtime runtime;
    private final MemoryMXBean memoryBean;
    private final long startTime;
    
    /**
     * Creates a new JVM memory monitor.
     * <p>
     * This resource requires no configuration and has no options.
     *
     * @param name The resource name (from configuration).
     * @param options Configuration options (unused, can be empty).
     */
    public JvmMemoryMonitor(String name, Config options) {
        super(name, options);
        this.runtime = Runtime.getRuntime();
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.startTime = ManagementFactory.getRuntimeMXBean().getStartTime();
    }
    
    /**
     * Adds JVM memory and runtime metrics.
     * <p>
     * All metrics are queried live from the JVM - no state tracking or recording.
     * This method is O(1) with negligible overhead.
     *
     * @param metrics Mutable map to add metrics to (already contains base error_count from AbstractResource).
     */
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        // Live heap metrics from Runtime
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        // Detailed heap usage via MemoryMXBean
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long committedMemory = heapUsage.getCommitted();
        
        // Heap metrics (bytes)
        metrics.put("jvm_heap_max_bytes", maxMemory);
        metrics.put("jvm_heap_committed_bytes", committedMemory);
        metrics.put("jvm_heap_used_bytes", usedMemory);
        metrics.put("jvm_heap_free_bytes", maxMemory - usedMemory);
        
        // Heap metrics (MB for readability)
        metrics.put("jvm_heap_max_mb", maxMemory / 1_048_576.0);
        metrics.put("jvm_heap_committed_mb", committedMemory / 1_048_576.0);
        metrics.put("jvm_heap_used_mb", usedMemory / 1_048_576.0);
        metrics.put("jvm_heap_free_mb", (maxMemory - usedMemory) / 1_048_576.0);
        
        // Heap metrics (percentages)
        metrics.put("jvm_heap_used_percent", (usedMemory * 100.0) / maxMemory);
        metrics.put("jvm_heap_committed_percent", (committedMemory * 100.0) / maxMemory);
        
        // Thread metrics
        int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
        int peakThreadCount = ManagementFactory.getThreadMXBean().getPeakThreadCount();
        int daemonThreadCount = ManagementFactory.getThreadMXBean().getDaemonThreadCount();
        metrics.put("jvm_thread_count", threadCount);
        metrics.put("jvm_thread_peak_count", peakThreadCount);
        metrics.put("jvm_daemon_thread_count", daemonThreadCount);
        
        // File descriptor metrics (Unix-like systems only)
        try {
            java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
                long openFDs = unix.getOpenFileDescriptorCount();
                long maxFDs = unix.getMaxFileDescriptorCount();
                
                metrics.put("os_open_file_descriptors", openFDs);
                metrics.put("os_max_file_descriptors", maxFDs);
                metrics.put("os_fd_usage_percent", maxFDs > 0 ? (openFDs * 100.0) / maxFDs : 0.0);
            }
        } catch (Exception e) {
            // Ignore - not available on all platforms (Windows)
        }
        
        // Runtime uptime
        long uptimeMs = System.currentTimeMillis() - startTime;
        metrics.put("jvm_uptime_seconds", uptimeMs / 1000.0);
    }
    
    /**
     * Returns the usage state for this monitoring resource.
     * <p>
     * JvmMemoryMonitor is always active as it performs live queries.
     *
     * @param usageType The usage type (ignored for this resource).
     * @return Always {@link UsageState#ACTIVE}.
     */
    @Override
    public IResource.UsageState getUsageState(String usageType) {
        return IResource.UsageState.ACTIVE;
    }
}

