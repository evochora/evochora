package org.evochora.datapipeline.resources.database;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareOrganismDataWriter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowPercentiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Database-agnostic wrapper for organism data writing operations.
 * <p>
 * Extends {@link AbstractDatabaseWrapper} to inherit common functionality:
 * connection management, schema setting, error tracking, metrics infrastructure.
 * <p>
 * All metric recording operations are O(1) using {@link AtomicLong},
 * {@link SlidingWindowCounter}, and {@link SlidingWindowPercentiles}.
 */
public class OrganismDataWriterWrapper extends AbstractDatabaseWrapper implements IResourceSchemaAwareOrganismDataWriter {

    private static final Logger log = LoggerFactory.getLogger(OrganismDataWriterWrapper.class);

    // Counters - O(1) atomic operations
    private final AtomicLong organismsWritten = new AtomicLong(0);
    private final AtomicLong batchesWritten = new AtomicLong(0);
    private final AtomicLong writeErrors = new AtomicLong(0);

    // Throughput tracking - O(1) recording with sliding window
    private final SlidingWindowCounter organismThroughput;
    private final SlidingWindowCounter batchThroughput;

    // Latency tracking - O(1) recording with sliding window
    private final SlidingWindowPercentiles writeLatency;

    // Track whether organism tables have been created
    private volatile boolean organismTablesCreated = false;

    /**
     * Creates organism data writer wrapper.
     *
     * @param db      Underlying database resource
     * @param context Resource context (service name, usage type)
     */
    OrganismDataWriterWrapper(AbstractDatabaseResource db, ResourceContext context) {
        super(db, context);

        this.organismThroughput = new SlidingWindowCounter(metricsWindowSeconds);
        this.batchThroughput = new SlidingWindowCounter(metricsWindowSeconds);
        this.writeLatency = new SlidingWindowPercentiles(metricsWindowSeconds);
    }

    @Override
    public void createOrganismTables() throws SQLException {
        try {
            ensureOrganismTables();
        } catch (RuntimeException e) {
            if (e.getCause() instanceof SQLException) {
                throw (SQLException) e.getCause();
            }
            throw new SQLException("Failed to create organism tables", e);
        }
    }

    // ========================================================================
    // Streaming write methods (per-tick addBatch / commit)
    // ========================================================================

    /**
     * Writes organism data for a single tick to the database.
     * <p>
     * Ensures organism tables exist (idempotent), then delegates to the underlying
     * database strategy via {@code doWriteOrganismTick}. Accumulates organisms in
     * JDBC batch buffers without committing.
     *
     * @param tick Tick data containing organism states
     * @throws SQLException if table creation or batch addition fails
     */
    @Override
    public void writeOrganismTick(TickData tick) throws SQLException {
        try {
            ensureOrganismTables();
            database.doWriteOrganismTick(ensureConnection(), tick);

            organismsWritten.addAndGet(tick.getOrganismsCount());
            organismThroughput.recordSum(tick.getOrganismsCount());
        } catch (Exception e) {
            writeErrors.incrementAndGet();
            log.warn("Failed to write organism tick {}: {}", tick.getTickNumber(), e.getMessage());
            recordError("WRITE_ORGANISM_TICK_FAILED", "Failed to write organism tick",
                    "Tick: " + tick.getTickNumber() + ", Organisms: " + tick.getOrganismsCount()
                    + ", Error: " + e.getMessage());
            if (e instanceof SQLException) {
                throw (SQLException) e;
            }
            throw new SQLException("Failed to write organism tick " + tick.getTickNumber(), e);
        }
    }

    /**
     * Commits all organism data accumulated since the last commit.
     * <p>
     * Delegates to {@code doCommitOrganismWrites} which executes JDBC batches,
     * commits the transaction, and resets strategy session state. Records batch
     * count and write latency metrics on success.
     *
     * @throws SQLException if batch execution or commit fails
     */
    @Override
    public void commitOrganismWrites() throws SQLException {
        long startNanos = System.nanoTime();
        try {
            database.doCommitOrganismWrites(ensureConnection());

            batchesWritten.incrementAndGet();
            batchThroughput.recordCount();
            writeLatency.record(System.nanoTime() - startNanos);
        } catch (Exception e) {
            writeErrors.incrementAndGet();
            log.warn("Failed to commit organism writes: {}", e.getMessage());
            recordError("COMMIT_ORGANISM_WRITES_FAILED", "Failed to commit organism writes",
                    "Error: " + e.getMessage());
            if (e instanceof SQLException) {
                throw (SQLException) e;
            }
            throw new SQLException("Failed to commit organism writes", e);
        }
    }

    /**
     * Ensures organism tables exist.
     * <p>
     * Idempotent and thread-safe via double-checked locking.
     */
    private void ensureOrganismTables() {
        if (organismTablesCreated) {
            return;
        }

        synchronized (this) {
            if (organismTablesCreated) {
                return;
            }

            try {
                database.doCreateOrganismTables(ensureConnection());
                organismTablesCreated = true;
                log.debug("Organism tables created");
            } catch (Exception e) {
                log.warn("Failed to create organism tables");
                recordError("CREATE_ORGANISM_TABLES_FAILED", "Failed to create organism tables",
                        "Error: " + e.getMessage());
                throw new RuntimeException("Failed to create organism tables", e);
            }
        }
    }

    /**
     * Adds organism writer-specific metrics to the metrics map.
     */
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Include parent metrics (connection_cached, error_count)

        metrics.put("organisms_written", organismsWritten.get());
        metrics.put("batches_written", batchesWritten.get());
        metrics.put("write_errors", writeErrors.get());

        metrics.put("organisms_per_second", organismThroughput.getRate());
        metrics.put("batches_per_second", batchThroughput.getRate());

        metrics.put("write_latency_p50_ms", writeLatency.getPercentile(50) / 1_000_000.0);
        metrics.put("write_latency_p95_ms", writeLatency.getPercentile(95) / 1_000_000.0);
        metrics.put("write_latency_p99_ms", writeLatency.getPercentile(99) / 1_000_000.0);
        metrics.put("write_latency_avg_ms", writeLatency.getAverage() / 1_000_000.0);
    }
}


