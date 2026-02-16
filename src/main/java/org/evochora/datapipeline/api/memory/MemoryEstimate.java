package org.evochora.datapipeline.api.memory;

/**
 * Represents a memory estimate for a single pipeline component.
 * <p>
 * Each component that implements {@link IMemoryEstimatable} returns one or more
 * MemoryEstimate objects describing its worst-case memory requirements.
 * <p>
 * <strong>Example:</strong>
 * <pre>
 * MemoryEstimate estimate = new MemoryEstimate(
 *     "persistence-service-1",
 *     367_001_600L,  // ~350 MB
 *     "50 ticks × (480000 cells × 20B + 50000 organisms × 500B)",
 *     Category.SERVICE_BATCH
 * );
 * </pre>
 *
 * @param componentName The name of the component (e.g., "persistence-service-1", "tick-queue").
 * @param estimatedBytes The worst-case memory requirement in bytes.
 * @param explanation Human-readable explanation of how the estimate was calculated.
 * @param category The category of memory usage for grouping in reports.
 */
public record MemoryEstimate(
    String componentName,
    long estimatedBytes,
    String explanation,
    Category category
) {
    
    /**
     * Category of memory usage for grouping and reporting.
     */
    public enum Category {
        /**
         * Queue resources (InMemoryBlockingQueue, DLQ).
         * Peak: queue capacity × item size.
         */
        QUEUE("Queues"),
        
        /**
         * Tracking resources (IdempotencyTracker, RetryTracker).
         * Peak: maxKeys × bytes per entry.
         */
        TRACKER("Trackers"),
        
        /**
         * Database resources (H2 cache, connection pools).
         * Peak: configured cache size.
         */
        DATABASE("Databases"),
        
        /**
         * Service batch buffers (PersistenceService, Indexers).
         * Peak: batchSize × item size.
         */
        SERVICE_BATCH("Service Batches"),
        
        /**
         * Topic resources (H2TopicResource).
         * Peak: configured cache size + pending messages.
         */
        TOPIC("Topics"),
        
        /**
         * JVM baseline overhead (thread stacks, class metadata, etc.).
         * Fixed overhead independent of configuration.
         */
        JVM_OVERHEAD("JVM Overhead");
        
        private final String displayName;
        
        Category(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    /**
     * Creates a memory estimate with a simple description.
     *
     * @param componentName The component name.
     * @param estimatedBytes The estimated bytes.
     * @param category The category.
     * @return A MemoryEstimate with auto-generated explanation.
     */
    public static MemoryEstimate of(String componentName, long estimatedBytes, Category category) {
        return new MemoryEstimate(
            componentName, 
            estimatedBytes, 
            SimulationParameters.formatBytes(estimatedBytes),
            category
        );
    }
    
    /**
     * Returns the estimate formatted as human-readable bytes.
     *
     * @return Formatted byte string (e.g., "350.0 MB").
     */
    public String formattedBytes() {
        return SimulationParameters.formatBytes(estimatedBytes);
    }
    
    @Override
    public String toString() {
        return String.format("%s → %s: %s", 
            componentName, 
            formattedBytes(), 
            explanation);
    }
}

