package org.evochora.datapipeline.resources.database;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IMetadataReader;
import org.evochora.datapipeline.api.resources.database.MetadataNotFoundException;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowPercentiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Database-agnostic wrapper for metadata reading operations.
 * <p>
 * Extends {@link AbstractDatabaseWrapper} to inherit common functionality:
 * connection management, schema setting, error tracking, metrics infrastructure.
 * <p>
 * <strong>Performance Contract:</strong> All metrics use O(1) recording.
 */
class MetadataReaderWrapper extends AbstractDatabaseWrapper implements IMetadataReader {
    private static final Logger log = LoggerFactory.getLogger(MetadataReaderWrapper.class);
    
    // Metrics (O(1) atomic operations)
    private final AtomicLong metadataReads = new AtomicLong(0);
    private final AtomicLong metadataNotFound = new AtomicLong(0);
    private final AtomicLong readErrors = new AtomicLong(0);
    
    // Latency tracking (O(1) recording)
    private final SlidingWindowPercentiles getMetadataLatency;
    private final SlidingWindowPercentiles hasMetadataLatency;
    
    MetadataReaderWrapper(AbstractDatabaseResource db, ResourceContext context) {
        super(db, context);  // Parent handles connection, error tracking, metrics window
        
        this.getMetadataLatency = new SlidingWindowPercentiles(metricsWindowSeconds);
        this.hasMetadataLatency = new SlidingWindowPercentiles(metricsWindowSeconds);
    }
    
    @Override
    public SimulationMetadata getMetadata(String simulationRunId) throws MetadataNotFoundException {
        long startNanos = System.nanoTime();
        
        try {
            SimulationMetadata metadata = database.doGetMetadata(ensureConnection(), simulationRunId);
            metadataReads.incrementAndGet();
            getMetadataLatency.record(System.nanoTime() - startNanos);
            return metadata;
            
        } catch (MetadataNotFoundException e) {
            metadataNotFound.incrementAndGet();
            getMetadataLatency.record(System.nanoTime() - startNanos);
            throw e;
            
        } catch (Exception e) {
            readErrors.incrementAndGet();
            log.warn("Failed to read metadata for run: {}", simulationRunId);
            recordError("GET_METADATA_FAILED", "Failed to read metadata",
                       "RunId: " + simulationRunId + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to read metadata: " + simulationRunId, e);
        }
    }
    
    @Override
    public boolean hasMetadata(String simulationRunId) {
        long startNanos = System.nanoTime();
        
        try {
            boolean exists = database.doHasMetadata(ensureConnection(), simulationRunId);
            hasMetadataLatency.record(System.nanoTime() - startNanos);
            return exists;
            
        } catch (Exception e) {
            readErrors.incrementAndGet();
            log.warn("Failed to check metadata existence for run: {}", simulationRunId);
            recordError("HAS_METADATA_FAILED", "Failed to check metadata existence",
                       "RunId: " + simulationRunId + ", Error: " + e.getMessage());
            return false; // Assume not present on error
        }
    }
    
    @Override
    public String getRunIdInCurrentSchema() throws MetadataNotFoundException {
        try {
            return database.doGetRunIdInCurrentSchema(ensureConnection());
            
        } catch (MetadataNotFoundException e) {
            metadataNotFound.incrementAndGet();
            throw e;
            
        } catch (Exception e) {
            readErrors.incrementAndGet();
            log.warn("Failed to get run-id from current schema");
            recordError("GET_RUNID_FAILED", "Failed to get run-id from schema",
                       "Error: " + e.getMessage());
            throw new RuntimeException("Failed to get run-id from current schema", e);
        }
    }
    
    // Note: close(), isHealthy(), getErrors(), clearErrors(), getResourceName(), 
    // getUsageState(), releaseConnection() are inherited from AbstractDatabaseWrapper
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Include parent metrics from AbstractDatabaseWrapper
        
        // Counters - O(1)
        metrics.put("metadata_reads", metadataReads.get());
        metrics.put("metadata_not_found", metadataNotFound.get());
        metrics.put("read_errors", readErrors.get());
        
        // Latency percentiles in milliseconds - O(windowSeconds × buckets) = O(constant)
        metrics.put("get_metadata_latency_p50_ms", getMetadataLatency.getPercentile(50) / 1_000_000.0);
        metrics.put("get_metadata_latency_p95_ms", getMetadataLatency.getPercentile(95) / 1_000_000.0);
        metrics.put("get_metadata_latency_p99_ms", getMetadataLatency.getPercentile(99) / 1_000_000.0);
        metrics.put("get_metadata_latency_avg_ms", getMetadataLatency.getAverage() / 1_000_000.0);
        
        metrics.put("has_metadata_latency_p95_ms", hasMetadataLatency.getPercentile(95) / 1_000_000.0);
    }
}

