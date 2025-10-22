package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.runtime.model.EnvironmentProperties;

import java.sql.SQLException;
import java.util.List;

/**
 * Database capability for writing environment cell data.
 * <p>
 * Provides write operations for environment cells with dimension-agnostic schema.
 * Used by EnvironmentIndexer to persist cell states for HTTP API queries.
 * <p>
 * <strong>Pure Capability Interface:</strong> This interface defines only the environment writing
 * operations, without resource management concerns (IMonitorable). Implementations that ARE
 * resources (like wrappers) will get those concerns from their base classes (AbstractResource).
 * <p>
 * Extends {@link ISchemaAwareDatabase} - AbstractIndexer automatically calls
 * {@code setSimulationRun()} after run discovery to set the schema.
 * <p>
 * Implements {@link AutoCloseable} to enable try-with-resources pattern for
 * automatic connection cleanup.
 */
public interface IEnvironmentDataWriter extends AutoCloseable {
    
    /**
     * Creates the environment_ticks table idempotently.
     * <p>
     * Table schema is strategy-specific (BLOB-based for SingleBlobStrategy).
     * <p>
     * Implementations should:
     * <ul>
     *   <li>Use H2SchemaUtil.executeDdlIfNotExists() for CREATE TABLE (concurrent safe)</li>
     *   <li>Create appropriate indexes for query performance</li>
     *   <li>Prepare any cached SQL strings for write operations</li>
     * </ul>
     *
     * @param dimensions Number of dimensions (e.g., 2 for 2D, 3 for 3D)
     * @throws SQLException if table creation fails
     */
    void createEnvironmentDataTable(int dimensions) throws SQLException;
    
    /**
     * Writes environment cells for multiple ticks using MERGE for idempotency.
     * <p>
     * All ticks are written in one JDBC batch with one commit for maximum performance.
     * Delegates to IH2EnvStorageStrategy for actual storage implementation.
     * <p>
     * Each tick is identified by tick_number and written with MERGE:
     * <ul>
     *   <li>If tick exists: UPDATE (overwrite with new data)</li>
     *   <li>If tick missing: INSERT</li>
     * </ul>
     * <p>
     * This ensures 100% idempotency even with topic redeliveries.
     * <p>
     * <strong>Performance:</strong> All ticks written in one JDBC batch with one commit.
     * This reduces commit overhead by ~1000× compared to per-tick commits.
     * <p>
     * <strong>Strategy-Specific:</strong> SingleBlobStrategy serializes cells to BLOB.
     * EnvironmentProperties passed for strategies that need coordinate conversion.
     *
     * @param ticks List of ticks with their cell data to write
     * @param envProps Environment properties for coordinate conversion (if needed by strategy)
     * @throws SQLException if database write fails
     */
    void writeEnvironmentCells(List<TickData> ticks, EnvironmentProperties envProps) 
            throws SQLException;
    
    /**
     * Closes the database wrapper and releases its dedicated connection back to the pool.
     * <p>
     * This method is automatically called when used with try-with-resources.
     * Implementations must ensure the connection is properly closed even if errors occur.
     */
    @Override
    void close();
}


