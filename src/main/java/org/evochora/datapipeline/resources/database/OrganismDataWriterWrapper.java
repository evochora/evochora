package org.evochora.datapipeline.resources.database;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareOrganismDataWriter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowPercentiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
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

    @Override
    public void writeOrganismStates(List<TickData> ticks) {
        if (ticks.isEmpty()) {
            return; // Nothing to write
        }

        long startNanos = System.nanoTime();
        int totalOrganisms = ticks.stream().mapToInt(TickData::getOrganismsCount).sum();

        try {
            // Ensure tables exist (idempotent, thread-safe)
            ensureOrganismTables();

            // Delegate to database for actual write
            database.doWriteOrganismStates(ensureConnection(), ticks);

            // Metrics on success
            organismsWritten.addAndGet(totalOrganisms);
            batchesWritten.incrementAndGet();

            organismThroughput.recordSum(totalOrganisms);
            batchThroughput.recordCount();
            writeLatency.record(System.nanoTime() - startNanos);

        } catch (Exception e) {
            writeErrors.incrementAndGet();
            log.warn("Failed to write {} ticks with {} organisms: {}",
                    ticks.size(), totalOrganisms, e.getMessage());
            recordError("WRITE_ORGANISM_STATES_FAILED", "Failed to write organism states",
                    "Ticks: " + ticks.size() + ", Organisms: " + totalOrganisms + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to write organism states for " + ticks.size() + " ticks", e);
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


