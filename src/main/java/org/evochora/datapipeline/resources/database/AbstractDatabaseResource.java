package org.evochora.datapipeline.resources.database;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.*;
import org.evochora.datapipeline.api.resources.database.IMetadataWriter;
import org.evochora.datapipeline.api.resources.database.MetadataNotFoundException;
import org.evochora.datapipeline.resources.AbstractResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for database resources.
 * <p>
 * Inherits IMonitorable infrastructure from {@link AbstractResource} including
 * error tracking, health checks, and metrics collection.
 * <p>
 * <strong>Wrapper Management:</strong> Tracks all created wrappers and ensures they
 * are properly closed when the base resource is closed.
 * <p>
 * <strong>AutoCloseable:</strong> Implements {@link AutoCloseable} to ensure proper
 * cleanup during shutdown. The {@link #close()} method closes all wrappers first,
 * then calls {@link #closeConnectionPool()} which subclasses must implement.
 */
public abstract class AbstractDatabaseResource extends AbstractResource
        implements IMetadataWriter, IContextualResource, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AbstractDatabaseResource.class);

    protected final AtomicLong queriesExecuted = new AtomicLong(0);
    protected final AtomicLong rowsInserted = new AtomicLong(0);
    protected final AtomicLong writeErrors = new AtomicLong(0);
    protected final AtomicLong readErrors = new AtomicLong(0);
    
    // Track all active wrappers for proper cleanup during shutdown
    private final List<AutoCloseable> activeWrappers = Collections.synchronizedList(new ArrayList<>());

    protected AbstractDatabaseResource(String name, Config options) {
        super(name, options);
    }

    @Override
    public final IWrappedResource getWrappedResource(ResourceContext context) {
        String usageType = context.usageType();
        IWrappedResource wrapper = switch (usageType) {
            case "db-meta-write" -> new MetadataWriterWrapper(this, context);
            case "db-meta-read" -> new MetadataReaderWrapper(this, context);
            default -> throw new IllegalArgumentException(
                    "Unknown database usage type: " + usageType + ". Supported: db-meta-write, db-meta-read");
        };
        
        // Track wrapper for cleanup
        if (wrapper instanceof AutoCloseable) {
            activeWrappers.add((AutoCloseable) wrapper);
        }
        
        return wrapper;
    }
    
    /**
     * Closes all active wrappers, releasing connections back to the pool.
     * <p>
     * This method should be called by subclasses in their {@link #close()} implementation
     * before shutting down the connection pool.
     */
    protected void closeAllWrappers() {
        if (!activeWrappers.isEmpty()) {
            log.debug("Closing {} wrappers for database '{}'", activeWrappers.size(), getResourceName());
        }
        for (AutoCloseable wrapper : activeWrappers) {
            try {
                wrapper.close();
            } catch (Exception e) {
                // Log but don't fail - best effort cleanup
                log.warn("Failed to close wrapper for database '{}'", getResourceName());
                recordError("WRAPPER_CLOSE_FAILED", "Failed to close wrapper", "Database: " + getResourceName());
            }
        }
        activeWrappers.clear();
    }

    /**
     * Acquires a dedicated database connection for capability-specific operations.
     * <p>
     * <strong>Transaction Contract:</strong>
     * <ul>
     *   <li>Connection MUST be configured with {@code autoCommit=false}</li>
     *   <li>Each {@code doWrite*()} method is responsible for its own {@code commit()}</li>
     *   <li>Each {@code doWrite*()} method MUST {@code rollback()} on Exception (in try-catch)</li>
     *   <li>Connection must be returned to pool in clean state (committed or rolled back)</li>
     * </ul>
     * <p>
     * This contract ensures ACID properties and prevents connection pool pollution
     * with uncommitted transactions.
     * <p>
     * <strong>Error Handling in doWrite* methods:</strong>
     * <ul>
     *   <li>NO logging in doWrite* - wrapper handles logging and error tracking</li>
     *   <li>Rollback on SQLException, then re-throw for wrapper</li>
     *   <li>Wrapper will log.warn() + recordError() for transient errors</li>
     * </ul>
     *
     * @return Database connection with autoCommit=false
     * @throws Exception if connection acquisition fails
     */
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

    /**
     * Closes the database resource and all its wrappers.
     * <p>
     * Shutdown order:
     * <ol>
     *   <li>Close all wrappers (releases connections back to pool)</li>
     *   <li>Close connection pool via {@link #closeConnectionPool()}</li>
     * </ol>
     * <p>
     * <strong>Note:</strong> This method does not declare {@code throws Exception} because
     * it implements both {@link AutoCloseable} and {@link IMetadataWriter}. All exceptions
     * are handled internally to comply with the stricter {@link IMetadataWriter#close()} signature.
     */
    @Override
    public void close() {
        // Step 1: Close all wrappers
        closeAllWrappers();
        
        // Step 2: Close connection pool (subclass-specific)
        try {
            closeConnectionPool();
        } catch (Exception e) {
            // Log error but don't throw - best effort cleanup
            recordError("POOL_CLOSE_FAILED", "Failed to close connection pool", 
                "Database: " + getResourceName() + ", Error: " + e.getMessage());
        }
    }
    
    /**
     * Closes the database connection pool.
     * <p>
     * Subclasses must implement this to properly shut down their connection pools
     * (e.g., HikariCP for H2Database).
     * <p>
     * Any exceptions thrown will be caught by {@link #close()} and logged as errors.
     * 
     * @throws Exception if pool shutdown fails
     */
    protected abstract void closeConnectionPool() throws Exception;

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