package org.evochora.datapipeline.resources.database.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
import org.evochora.datapipeline.api.resources.database.dto.SpatialRegion;
import org.evochora.runtime.model.EnvironmentProperties;

/**
 * H2-specific strategy interface for storing and reading environment data.
 * <p>
 * Different strategies trade off between storage size, query performance,
 * and write performance. This interface is H2-specific and cannot be used
 * with other database backends.
 * <p>
 * <strong>Rationale:</strong> Storage requirements for environment data can vary
 * dramatically based on environment size and tick count:
 * <ul>
 *   <li>Small runs (1000×1000, 10^6 ticks): ~15 GB with row-per-cell</li>
 *   <li>Large runs (10000×10000, 10^8 ticks): ~500 TB with row-per-cell ❌</li>
 * </ul>
 * Different storage strategies enable different trade-offs without code changes.
 */
public interface IH2EnvStorageStrategy {
    
    /**
     * Creates the necessary tables and indexes for this storage strategy.
     * <p>
     * <strong>Note:</strong> This creates TABLE schema (columns, indexes), not database schema
     * (namespace). The database schema (SIM_xxx) is already created and set by AbstractIndexer
     * before this method is called.
     * <p>
     * <strong>Idempotency:</strong> Must use CREATE TABLE IF NOT EXISTS and CREATE INDEX IF NOT EXISTS.
     * Multiple indexer instances may call this concurrently. Use {@link org.evochora.datapipeline.utils.H2SchemaUtil#executeDdlIfNotExists(java.sql.Statement, String, String)}
     * for race-safe DDL execution.
     *
     * @param conn Database connection (schema already set to SIM_xxx, autoCommit=false)
     * @param dimensions Number of spatial dimensions (for validation or metadata)
     * @throws SQLException if table creation fails
     */
    void createTables(Connection conn, int dimensions) throws SQLException;
    
    /**
     * Returns the SQL string for the MERGE statement.
     * <p>
     * This SQL is used by H2Database to create a cached PreparedStatement for performance.
     * The statement is cached per connection to avoid repeated SQL parsing overhead.
     *
     * @return SQL string for MERGE operation
     */
    String getMergeSql();
    
    /**
     * Writes environment data for multiple ticks using this storage strategy.
     * <p>
     * <strong>Transaction Management:</strong> This method is executed within a transaction
     * managed by the caller (H2Database). Implementations should <strong>NOT</strong> call
     * {@code commit()} or {@code rollback()} themselves. If an exception is thrown, the
     * caller is responsible for rolling back the transaction.
     * <p>
     * <strong>PreparedStatement Caching:</strong> The {@code stmt} parameter is a cached
     * PreparedStatement created from {@link #getMergeSql()}. This eliminates repeated SQL
     * parsing overhead and improves write performance by ~30-50%.
     * <p>
     * <strong>Rationale:</strong> Keeps strategy focused on SQL operations only, while
     * H2Database manages transaction lifecycle and statement caching consistently.
     *
     * @param conn Database connection (with autoCommit=false, transaction managed by caller)
     * @param stmt Cached PreparedStatement for MERGE operation (from getMergeSql())
     * @param ticks List of ticks with cell data to write
     * @param envProps Environment properties for coordinate conversion
     * @throws SQLException if write fails (caller will rollback)
     */
    void writeTicks(Connection conn, PreparedStatement stmt, List<TickData> ticks, 
                    EnvironmentProperties envProps) throws SQLException;

    /**
     * Reads environment cells for a specific tick with optional region filtering.
     * <p>
     * This method enables HTTP API controllers to query environment data with
     * spatial filtering. The strategy handles database-specific optimizations
     * (e.g., BLOB decompression, spatial indexes) transparently.
     * 
     * @param conn Database connection (schema already set)
     * @param tickNumber Tick to read
     * @param region Spatial bounds (null = all cells)
     * @param envProps Environment properties for coordinate conversion
     * @return List of cells with flatIndex (coordinates converted by caller)
     * @throws SQLException if database read fails
     * @throws TickNotFoundException if the tick itself does not exist in the database
     */
    List<org.evochora.datapipeline.api.contracts.CellState> readTick(Connection conn, long tickNumber, 
                                                                      SpatialRegion region, 
                                                                      EnvironmentProperties envProps) throws SQLException, TickNotFoundException;
}

