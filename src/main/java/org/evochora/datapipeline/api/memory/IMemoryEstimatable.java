package org.evochora.datapipeline.api.memory;

import java.util.List;

/**
 * Interface for pipeline components that can estimate their worst-case memory usage.
 * <p>
 * Implemented by services and resources that hold significant data in memory:
 * <ul>
 *   <li>Queues (InMemoryBlockingQueue, InMemoryDeadLetterQueue)</li>
 *   <li>Trackers (InMemoryIdempotencyTracker, InMemoryRetryTracker)</li>
 *   <li>Batching services (PersistenceService, EnvironmentIndexer, OrganismIndexer)</li>
 *   <li>Databases with caches (H2Database, H2TopicResource)</li>
 * </ul>
 * <p>
 * <strong>Worst-Case Estimation:</strong> All estimates should assume:
 * <ul>
 *   <li>100% environment occupancy (all cells filled)</li>
 *   <li>Maximum configured organisms alive</li>
 *   <li>All buffers/queues at maximum capacity</li>
 * </ul>
 * <p>
 * <strong>Usage by ServiceManager:</strong>
 * <pre>
 * // At startup, ServiceManager collects estimates from all IMemoryEstimatable components
 * long totalEstimate = 0;
 * for (IResource resource : resources.values()) {
 *     if (resource instanceof IMemoryEstimatable) {
 *         List&lt;MemoryEstimate&gt; estimates = ((IMemoryEstimatable) resource)
 *             .estimateWorstCaseMemory(simulationParams);
 *         totalEstimate += estimates.stream().mapToLong(MemoryEstimate::estimatedBytes).sum();
 *     }
 * }
 * 
 * // Compare with available heap
 * long maxHeap = Runtime.getRuntime().maxMemory();
 * if (totalEstimate &gt; maxHeap) {
 *     log.warn("MEMORY WARNING: Estimated peak {} exceeds -Xmx {}", 
 *         formatBytes(totalEstimate), formatBytes(maxHeap));
 * }
 * </pre>
 *
 * @see SimulationParameters
 * @see MemoryEstimate
 */
public interface IMemoryEstimatable {
    
    /**
     * Estimates the worst-case memory usage of this component.
     * <p>
     * The estimate should be conservative (assume maximum load) to prevent
     * OOM errors during long-running simulations.
     * <p>
     * <strong>Implementation Guidelines:</strong>
     * <ul>
     *   <li>For queues: capacity × estimated item size at 100% occupancy</li>
     *   <li>For trackers: maxKeys × bytes per entry</li>
     *   <li>For services with batching: batchSize × tick size at 100% occupancy</li>
     *   <li>For databases: configured cache size</li>
     * </ul>
     *
     * @param params Simulation parameters including environment size and max organisms.
     *               Used to calculate item sizes (cells per tick, organisms per tick).
     * @return List of memory estimates for this component. Most components return
     *         a single estimate, but complex components may return multiple
     *         (e.g., a service with multiple internal buffers).
     */
    List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params);
}

