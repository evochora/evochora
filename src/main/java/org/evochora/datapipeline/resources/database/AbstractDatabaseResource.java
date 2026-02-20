package org.evochora.datapipeline.resources.database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.IContextualResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IMetadataWriter;
import org.evochora.datapipeline.api.resources.database.ISchemaAwareDatabase;
import org.evochora.datapipeline.api.resources.database.MetadataNotFoundException;
import org.evochora.datapipeline.resources.AbstractResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

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
        implements IMetadataWriter, IContextualResource, ISchemaAwareDatabase {

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
            case "db-env-write" -> new EnvironmentDataWriterWrapper(this, context);
            case "db-organism-write" -> new OrganismDataWriterWrapper(this, context);
            default -> throw new IllegalArgumentException(
                    "Unknown database usage type: " + usageType + ". Supported: db-meta-write, db-meta-read, db-env-write, db-organism-write");
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

    /**
     * Checks if a database connection is closed.
     * <p>
     * This method is used by wrappers to determine if a cached connection needs to be re-acquired.
     * Implementations should handle database-specific connection types.
     *
     * @param connection The connection to check (from acquireDedicatedConnection)
     * @return true if connection is closed or invalid, false if open and valid
     */
    protected abstract boolean isConnectionClosed(Object connection);

    /**
     * Closes a database connection and returns it to the pool.
     * <p>
     * This method is used by wrappers to release connections back to the pool.
     * Implementations should handle database-specific connection types and cleanup.
     *
     * @param connection The connection to close (from acquireDedicatedConnection)
     * @throws Exception if connection close fails
     */
    protected abstract void closeConnection(Object connection) throws Exception;

    protected abstract void doInsertMetadata(Object connection, SimulationMetadata metadata) throws Exception;

    protected abstract void doSetSchema(Object connection, String runId) throws Exception;

    protected abstract void doCreateSchema(Object connection, String runId) throws Exception;

    // ========================================================================
    // IEnvironmentDataWriter Capability
    // ========================================================================

    /**
     * Creates environment_chunks table with storage strategy-specific schema.
     * <p>
     * <strong>Capability:</strong> {@link org.evochora.datapipeline.api.resources.database.IEnvironmentDataWriter#createEnvironmentDataTable(int)}
     * <p>
     * <strong>Transaction Handling:</strong> Must commit on success, rollback on failure.
     * <strong>Storage Strategy:</strong> Delegates to IH2EnvStorageStrategy for actual schema creation.
     * <p>
     * Implementation is idempotent - calling multiple times is safe.
     * 
     * @param connection Database connection (from acquireDedicatedConnection)
     * @param dimensions Number of spatial dimensions (for validation/metadata)
     * @throws Exception if table creation fails
     */
    protected abstract void doCreateEnvironmentDataTable(Object connection, int dimensions) 
            throws Exception;

    /**
     * Writes a single raw environment chunk to the database via the storage strategy.
     * <p>
     * <strong>Capability:</strong> {@link org.evochora.datapipeline.api.resources.database.IEnvironmentDataWriter#writeRawChunk(long, long, int, byte[])}
     * <p>
     * <strong>Transaction Handling:</strong> This method does NOT commit. The caller
     * accumulates multiple chunks and calls {@link #doCommitRawEnvironmentChunks(Object)}
     * to commit the batch.
     *
     * @param connection Database connection (from acquireDedicatedConnection)
     * @param firstTick First tick number in the chunk
     * @param lastTick Last tick number in the chunk
     * @param tickCount Number of sampled ticks in the chunk
     * @param rawProtobufData Uncompressed protobuf bytes of one TickDataChunk message
     * @throws SQLException if write fails
     */
    protected abstract void doWriteRawEnvironmentChunk(Object connection,
            long firstTick, long lastTick, int tickCount, byte[] rawProtobufData) throws SQLException;

    /**
     * Commits all raw environment chunks accumulated via {@link #doWriteRawEnvironmentChunk}.
     * <p>
     * <strong>Capability:</strong> {@link org.evochora.datapipeline.api.resources.database.IEnvironmentDataWriter#commitRawChunks()}
     * <p>
     * <strong>Transaction Handling:</strong> Must commit on success, rollback on failure.
     *
     * @param connection Database connection (from acquireDedicatedConnection)
     * @throws SQLException if commit fails
     */
    protected abstract void doCommitRawEnvironmentChunks(Object connection) throws SQLException;

    // ========================================================================
    // IOrganismDataWriter Capability
    // ========================================================================

    /**
     * Creates organism tables ({@code organisms}, {@code organism_states}) in the
     * current schema if they do not already exist.
     * <p>
     * <strong>Capability:</strong> {@link org.evochora.datapipeline.api.resources.database.IOrganismDataWriter#createOrganismTables()}.
     * <p>
     * <strong>Transaction Handling:</strong> Must commit on success, rollback on failure.
     *
     * @param connection Database connection (from {@link #acquireDedicatedConnection()})
     * @throws Exception if table creation fails
     */
    protected abstract void doCreateOrganismTables(Object connection) throws Exception;

    /**
     * Adds organism data from a single tick to the write session via the storage strategy.
     * <p>
     * <strong>Capability:</strong> {@link org.evochora.datapipeline.api.resources.database.IOrganismDataWriter#writeOrganismTick(org.evochora.datapipeline.api.contracts.TickData)}
     * <p>
     * <strong>Transaction Handling:</strong> This method does NOT commit. The caller
     * accumulates multiple ticks and calls {@link #doCommitOrganismWrites(Object)}
     * to commit the batch.
     *
     * @param connection Database connection (from {@link #acquireDedicatedConnection()})
     * @param tick Tick data containing organism states
     * @throws Exception if batch addition fails
     */
    protected abstract void doWriteOrganismTick(Object connection,
            org.evochora.datapipeline.api.contracts.TickData tick) throws Exception;

    /**
     * Commits all organism data accumulated via {@link #doWriteOrganismTick}.
     * <p>
     * <strong>Capability:</strong> {@link org.evochora.datapipeline.api.resources.database.IOrganismDataWriter#commitOrganismWrites()}
     * <p>
     * <strong>Transaction Handling:</strong> Must commit on success, rollback on failure.
     *
     * @param connection Database connection (from {@link #acquireDedicatedConnection()})
     * @throws Exception if commit fails
     */
    protected abstract void doCommitOrganismWrites(Object connection) throws Exception;

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

    /**
     * Retrieves the simulation run ID from metadata in the current schema.
     * <p>
     * <strong>Capability:</strong> {@link org.evochora.datapipeline.api.resources.database.IMetadataReader#getRunIdInCurrentSchema()}
     * <p>
     * This method reads the run-id from the metadata table without requiring
     * the run-id as a parameter. Used for run-id discovery scenarios where
     * the schema is known but the original run-id needs to be retrieved.
     * <p>
     * <strong>Implementation Note:</strong> Must extract simulation_run_id from
     * metadata table in the current schema (set via doSetSchema).
     *
     * @param connection Database connection (with schema already set)
     * @return Simulation run ID stored in current schema's metadata
     * @throws MetadataNotFoundException if metadata doesn't exist in current schema
     * @throws Exception for other database errors
     */
    protected abstract String doGetRunIdInCurrentSchema(Object connection) 
            throws Exception;

    @Override
    public void setSimulationRun(String simulationRunId) {
        throw new UnsupportedOperationException("This operation must be called on a wrapped resource.");
    }

    @Override
    public void insertMetadata(SimulationMetadata metadata) {
        throw new UnsupportedOperationException("This operation must be called on a wrapped resource.");
    }

    @Override
    public boolean hasMetadata(String simulationRunId) {
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