package org.evochora.datapipeline.resources.database;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.*;
import org.evochora.datapipeline.api.resources.database.IMetadataWriter;
import org.evochora.datapipeline.api.resources.database.MetadataNotFoundException;
import org.evochora.datapipeline.resources.AbstractResource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for database resources.
 * <p>
 * Inherits IMonitorable infrastructure from {@link AbstractResource} including
 * error tracking, health checks, and metrics collection.
 */
public abstract class AbstractDatabaseResource extends AbstractResource
        implements IMetadataWriter, IContextualResource {

    protected final AtomicLong queriesExecuted = new AtomicLong(0);
    protected final AtomicLong rowsInserted = new AtomicLong(0);
    protected final AtomicLong writeErrors = new AtomicLong(0);
    protected final AtomicLong readErrors = new AtomicLong(0);

    protected AbstractDatabaseResource(String name, Config options) {
        super(name, options);
    }

    @Override
    public IWrappedResource getWrappedResource(ResourceContext context) {
        String usageType = context.usageType();
        return switch (usageType) {
            case "db-meta-write" -> new MetadataWriterWrapper(this, context);
            case "db-meta-read" -> new MetadataReaderWrapper(this, context);
            default -> throw new IllegalArgumentException(
                    "Unknown database usage type: " + usageType + ". Supported: db-meta-write, db-meta-read");
        };
    }

    protected abstract Object acquireDedicatedConnection() throws Exception;

    protected abstract void doInsertMetadata(Object connection, SimulationMetadata metadata) throws Exception;

    protected abstract void doSetSchema(Object connection, String runId) throws Exception;

    protected abstract void doCreateSchema(Object connection, String runId) throws Exception;

    // ========================================================================
    // IMetadataReader Capability
    // ========================================================================

    /**
     * Retrieves simulation metadata from the database.
     * <p>
     * <strong>Capability:</strong> {@link org.evochora.datapipeline.api.resources.database.IMetadataReader#getMetadata(String)}
     * <p>
     * Implementation reads from metadata table in current schema.
     * Used by indexers to access simulation configuration (e.g., samplingInterval).
     *
     * @param connection Database connection (with schema already set)
     * @param simulationRunId Simulation run ID (for validation)
     * @return Parsed SimulationMetadata protobuf
     * @throws MetadataNotFoundException if metadata doesn't exist
     * @throws Exception for other database errors
     */
    protected abstract SimulationMetadata doGetMetadata(Object connection, String simulationRunId) 
            throws Exception;

    /**
     * Checks if metadata exists in the database.
     * <p>
     * <strong>Capability:</strong> {@link org.evochora.datapipeline.api.resources.database.IMetadataReader#hasMetadata(String)}
     * <p>
     * Non-blocking check used for polling scenarios.
     *
     * @param connection Database connection (with schema already set)
     * @param simulationRunId Simulation run ID
     * @return true if metadata exists, false otherwise
     * @throws Exception if database query fails
     */
    protected abstract boolean doHasMetadata(Object connection, String simulationRunId) 
            throws Exception;

    @Override
    public void setSimulationRun(String simulationRunId) {
        throw new UnsupportedOperationException("This operation must be called on a wrapped resource.");
    }

    @Override
    public void createSimulationRun(String simulationRunId) {
        throw new UnsupportedOperationException("This operation must be called on a wrapped resource.");
    }

    @Override
    public void insertMetadata(SimulationMetadata metadata) {
        throw new UnsupportedOperationException("This operation must be called on a wrapped resource.");
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("This operation must be called on a wrapped resource.");
    }

    /**
     * Adds database-specific metrics to the provided map.
     * <p>
     * This override adds counters tracked by all database resources.
     * Subclasses should call {@code super.addCustomMetrics(metrics)} to include these.
     *
     * @param metrics Mutable map to add metrics to (already contains base error_count from AbstractResource)
     */
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Include parent metrics
        
        metrics.put("queries_executed", queriesExecuted.get());
        metrics.put("rows_inserted", rowsInserted.get());
        metrics.put("write_errors", writeErrors.get());
        metrics.put("read_errors", readErrors.get());
    }

    @Override
    public UsageState getUsageState(String usageType) {
        return isHealthy() ? UsageState.ACTIVE : UsageState.FAILED;
    }
}