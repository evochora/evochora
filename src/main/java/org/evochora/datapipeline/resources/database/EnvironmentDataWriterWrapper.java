package org.evochora.datapipeline.resources.database;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.*;
import org.evochora.datapipeline.api.resources.database.IEnvironmentDataWriter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowPercentiles;
import org.evochora.runtime.model.EnvironmentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Database-agnostic wrapper for environment data writing operations.
 * <p>
 * Extends {@link AbstractDatabaseWrapper} to inherit common functionality:
 * connection management, schema setting, error tracking, metrics infrastructure.
 * <p>
 * <strong>Performance:</strong> All metrics are O(1) recording operations using:
 * <ul>
 *   <li>{@link AtomicLong} for counters (cells_written, batches_written, write_errors)</li>
 *   <li>{@link SlidingWindowCounter} for throughput (cells_per_second, batches_per_second)</li>
 *   <li>{@link SlidingWindowPercentiles} for latency (write_latency_p50/p95/p99/avg_ms)</li>
 * </ul>
 */
public class EnvironmentDataWriterWrapper extends AbstractDatabaseWrapper implements IEnvironmentDataWriter {
    private static final Logger log = LoggerFactory.getLogger(EnvironmentDataWriterWrapper.class);
    
    // Counters - O(1) atomic operations
    private final AtomicLong cellsWritten = new AtomicLong(0);
    private final AtomicLong batchesWritten = new AtomicLong(0);
    private final AtomicLong writeErrors = new AtomicLong(0);
    
    // Throughput tracking - O(1) recording with sliding window
    private final SlidingWindowCounter cellThroughput;
    private final SlidingWindowCounter batchThroughput;
    
    // Latency tracking - O(1) recording with sliding window
    private final SlidingWindowPercentiles writeLatency;
    
    // Track whether environment table has been created
    private volatile boolean environmentTableCreated = false;

    /**
     * Creates environment data writer wrapper.
     * <p>
     * Inherits connection management and schema handling from {@link AbstractDatabaseWrapper}.
     * 
     * @param db Underlying database resource
     * @param context Resource context (service name, usage type)
     */
    EnvironmentDataWriterWrapper(AbstractDatabaseResource db, ResourceContext context) {
        super(db, context);  // Parent handles connection, error tracking, metrics window, schema creation
        
        // Initialize throughput trackers with sliding window
        this.cellThroughput = new SlidingWindowCounter(metricsWindowSeconds);
        this.batchThroughput = new SlidingWindowCounter(metricsWindowSeconds);
        
        // Initialize latency tracker with sliding window
        this.writeLatency = new SlidingWindowPercentiles(metricsWindowSeconds);
    }

    @Override
    public void createEnvironmentDataTable(int dimensions) throws java.sql.SQLException {
        // This method is part of IEnvironmentDataWriter interface but handled internally
        // by ensureEnvironmentDataTable() during writeEnvironmentCells().
        // Exposed for explicit schema creation if needed before first write.
        try {
            ensureEnvironmentDataTable(dimensions);
        } catch (RuntimeException e) {
            // Unwrap if it's a SQL exception
            if (e.getCause() instanceof java.sql.SQLException) {
                throw (java.sql.SQLException) e.getCause();
            }
            throw new java.sql.SQLException("Failed to create environment data table", e);
        }
    }

    @Override
    public void writeEnvironmentCells(List<TickData> ticks, EnvironmentProperties envProps) {
        if (ticks.isEmpty()) {
            return;  // Nothing to write
        }
        
        long startNanos = System.nanoTime();
        try {
            // Ensure environment table exists (idempotent, thread-safe)
            ensureEnvironmentDataTable(envProps.getWorldShape().length);
            
            // Write cells via database strategy
            database.doWriteEnvironmentCells(ensureConnection(), ticks, envProps);
            
            // Update metrics - O(1) operations
            int totalCells = ticks.stream().mapToInt(t -> t.getCellsList().size()).sum();
            cellsWritten.addAndGet(totalCells);
            batchesWritten.incrementAndGet();
            
            cellThroughput.recordSum(totalCells);
            batchThroughput.recordCount();
            writeLatency.record(System.nanoTime() - startNanos);
            
        } catch (Exception e) {
            writeErrors.incrementAndGet();
            log.warn("Failed to write {} ticks with {} total cells: {}", 
                    ticks.size(), 
                    ticks.stream().mapToInt(t -> t.getCellsList().size()).sum(),
                    e.getMessage());
            recordError("WRITE_ENV_CELLS_FAILED", "Failed to write environment cells",
                       "Ticks: " + ticks.size() + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to write environment cells for " + ticks.size() + " ticks", e);
        }
    }

    /**
     * Ensures environment_ticks table exists.
     * <p>
     * <strong>Idempotency:</strong> Safe to call multiple times (CREATE TABLE IF NOT EXISTS).
     * <strong>Thread Safety:</strong> Uses volatile boolean for double-checked locking optimization.
     * 
     * @param dimensions Number of spatial dimensions
     */
    private void ensureEnvironmentDataTable(int dimensions) {
        // Fast path: table already created
        if (environmentTableCreated) {
            return;
        }
        
        // Slow path: create table (synchronized to prevent duplicate attempts)
        synchronized (this) {
            if (environmentTableCreated) {
                return;  // Another thread created it
            }
            
            try {
                database.doCreateEnvironmentDataTable(ensureConnection(), dimensions);
                environmentTableCreated = true;
                log.debug("Environment data table created for {} dimensions", dimensions);
            } catch (Exception e) {
                log.warn("Failed to create environment data table for {} dimensions", dimensions);
                recordError("CREATE_ENV_TABLE_FAILED", "Failed to create environment table",
                           "Dimensions: " + dimensions + ", Error: " + e.getMessage());
                throw new RuntimeException("Failed to create environment data table", e);
            }
        }
    }

    /**
     * Adds environment writer-specific metrics to the metrics map.
     * <p>
     * <strong>Performance:</strong> All operations are O(1).
     */
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Include parent metrics (connection_cached, error_count)
        
        // Counters - O(1)
        metrics.put("cells_written", cellsWritten.get());
        metrics.put("batches_written", batchesWritten.get());
        metrics.put("write_errors", writeErrors.get());
        
        // Throughput - O(windowSeconds) = O(constant)
        metrics.put("cells_per_second", cellThroughput.getRate());
        metrics.put("batches_per_second", batchThroughput.getRate());
        
        // Latency percentiles in milliseconds - O(windowSeconds × buckets) = O(constant)
        metrics.put("write_latency_p50_ms", writeLatency.getPercentile(50) / 1_000_000.0);
        metrics.put("write_latency_p95_ms", writeLatency.getPercentile(95) / 1_000_000.0);
        metrics.put("write_latency_p99_ms", writeLatency.getPercentile(99) / 1_000_000.0);
        metrics.put("write_latency_avg_ms", writeLatency.getAverage() / 1_000_000.0);
    }
}

