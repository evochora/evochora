package org.evochora.datapipeline.resources.database;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareEnvironmentDataWriter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowPercentiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Database-agnostic wrapper for environment data writing operations.
 * <p>
 * Extends {@link AbstractDatabaseWrapper} to inherit common functionality:
 * connection management, schema setting, error tracking, metrics infrastructure.
 * <p>
 * <strong>Delta Compression:</strong> This wrapper writes TickDataChunks directly
 * to the database without decompression. Each chunk contains a snapshot plus deltas
 * for a range of ticks. Decompression is deferred to query time (EnvironmentController).
 * <p>
 * <strong>Performance:</strong> All metrics are O(1) recording operations using:
 * <ul>
 *   <li>{@link AtomicLong} for counters (chunks_written, batches_written, write_errors)</li>
 *   <li>{@link SlidingWindowCounter} for throughput (chunks_per_second, batches_per_second)</li>
 *   <li>{@link SlidingWindowPercentiles} for latency (write_latency_p50/p95/p99/avg_ms)</li>
 * </ul>
 */
public class EnvironmentDataWriterWrapper extends AbstractDatabaseWrapper implements IResourceSchemaAwareEnvironmentDataWriter {
    private static final Logger log = LoggerFactory.getLogger(EnvironmentDataWriterWrapper.class);
    
    // Counters - O(1) atomic operations
    private final AtomicLong chunksWritten = new AtomicLong(0);
    private final AtomicLong batchesWritten = new AtomicLong(0);
    private final AtomicLong writeErrors = new AtomicLong(0);
    
    // Throughput tracking - O(1) recording with sliding window
    private final SlidingWindowCounter chunkThroughput;
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
        this.chunkThroughput = new SlidingWindowCounter(metricsWindowSeconds);
        this.batchThroughput = new SlidingWindowCounter(metricsWindowSeconds);
        
        // Initialize latency tracker with sliding window
        this.writeLatency = new SlidingWindowPercentiles(metricsWindowSeconds);
    }

    @Override
    public void createEnvironmentDataTable(int dimensions) throws java.sql.SQLException {
        // This method is part of IEnvironmentDataWriter interface but handled internally
        // by ensureEnvironmentDataTable() during writeEnvironmentChunks().
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
    public void writeRawChunk(long firstTick, long lastTick, int tickCount,
                              byte[] rawProtobufData) throws SQLException {
        long startNanos = System.nanoTime();
        try {
            database.doWriteRawEnvironmentChunk(ensureConnection(), firstTick, lastTick, tickCount, rawProtobufData);
            chunksWritten.incrementAndGet();
            chunkThroughput.recordCount();
            writeLatency.record(System.nanoTime() - startNanos);
        } catch (SQLException e) {
            writeErrors.incrementAndGet();
            log.warn("Failed to write raw chunk [{}-{}]: {}", firstTick, lastTick, e.getMessage());
            recordError("WRITE_RAW_CHUNK_FAILED", "Failed to write raw environment chunk",
                       "Ticks: " + firstTick + "-" + lastTick + ", Error: " + e.getMessage());
            throw e;
        }
    }

    @Override
    public void commitRawChunks() throws SQLException {
        long startNanos = System.nanoTime();
        try {
            database.doCommitRawEnvironmentChunks(ensureConnection());
            batchesWritten.incrementAndGet();
            batchThroughput.recordCount();
            writeLatency.record(System.nanoTime() - startNanos);
        } catch (SQLException e) {
            writeErrors.incrementAndGet();
            log.warn("Failed to commit raw chunks: {}", e.getMessage());
            recordError("COMMIT_RAW_CHUNKS_FAILED", "Failed to commit raw environment chunks",
                       "Error: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Ensures environment_chunks table exists.
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
        metrics.put("chunks_written", chunksWritten.get());
        metrics.put("batches_written", batchesWritten.get());
        metrics.put("write_errors", writeErrors.get());
        
        // Throughput - O(windowSeconds) = O(constant)
        metrics.put("chunks_per_second", chunkThroughput.getRate());
        metrics.put("batches_per_second", batchThroughput.getRate());
        
        // Latency percentiles in milliseconds - O(windowSeconds × buckets) = O(constant)
        metrics.put("write_latency_p50_ms", writeLatency.getPercentile(50) / 1_000_000.0);
        metrics.put("write_latency_p95_ms", writeLatency.getPercentile(95) / 1_000_000.0);
        metrics.put("write_latency_p99_ms", writeLatency.getPercentile(99) / 1_000_000.0);
        metrics.put("write_latency_avg_ms", writeLatency.getAverage() / 1_000_000.0);
    }
}
