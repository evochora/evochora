package org.evochora.datapipeline.resources.database;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.*;
import org.evochora.datapipeline.api.resources.database.IMetadataWriter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowPercentiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Database-agnostic wrapper for metadata writing operations.
 * <p>
 * Extends {@link AbstractDatabaseWrapper} to inherit common functionality:
 * connection management, schema setting, error tracking, metrics infrastructure.
 */
public class MetadataWriterWrapper extends AbstractDatabaseWrapper implements IMetadataWriter {
    private static final Logger log = LoggerFactory.getLogger(MetadataWriterWrapper.class);
    
    // Metrics (O(1) atomic operations)
    private final AtomicLong metadataInserts = new AtomicLong(0);
    private final AtomicLong operationErrors = new AtomicLong(0);
    
    // Latency tracking (O(1) recording)
    private final SlidingWindowPercentiles insertMetadataLatency;

    MetadataWriterWrapper(AbstractDatabaseResource db, ResourceContext context) {
        super(db, context);  // Parent handles connection, error tracking, metrics window, schema creation
        
        // Initialize latency trackers with sliding window
        this.insertMetadataLatency = new SlidingWindowPercentiles(metricsWindowSeconds);
    }

    @Override
    public void createSimulationRun(String simulationRunId) {
        // Schema creation now handled transparently by AbstractDatabaseWrapper.setSimulationRun()
        // This method is kept for API compatibility but does nothing
        // Schema is created automatically on first setSimulationRun() call
    }

    @Override
    public void insertMetadata(SimulationMetadata metadata) {
        long startNanos = System.nanoTime();
        try {
            database.doInsertMetadata(ensureConnection(), metadata);
            metadataInserts.incrementAndGet();
            insertMetadataLatency.record(System.nanoTime() - startNanos);
        } catch (Exception e) {
            operationErrors.incrementAndGet();
            log.warn("Failed to insert metadata for run: {}", metadata.getSimulationRunId());
            recordError("INSERT_METADATA_FAILED", "Failed to insert metadata",
                       "RunId: " + metadata.getSimulationRunId() + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to insert metadata: " + metadata.getSimulationRunId(), e);
        }
    }

    // Note: close(), isHealthy(), getErrors(), clearErrors(), getResourceName(), 
    // getUsageState(), setSimulationRun(), releaseConnection() are inherited from AbstractDatabaseWrapper

    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Include parent metrics from AbstractDatabaseWrapper
        
        // Counters - O(1)
        metrics.put("metadata_inserts", metadataInserts.get());
        metrics.put("operation_errors", operationErrors.get());
        
        // Latency percentiles in milliseconds - O(windowSeconds × buckets) = O(constant)
        metrics.put("insert_metadata_latency_p50_ms", insertMetadataLatency.getPercentile(50) / 1_000_000.0);
        metrics.put("insert_metadata_latency_p95_ms", insertMetadataLatency.getPercentile(95) / 1_000_000.0);
        metrics.put("insert_metadata_latency_p99_ms", insertMetadataLatency.getPercentile(99) / 1_000_000.0);
        metrics.put("insert_metadata_latency_avg_ms", insertMetadataLatency.getAverage() / 1_000_000.0);
    }
}
