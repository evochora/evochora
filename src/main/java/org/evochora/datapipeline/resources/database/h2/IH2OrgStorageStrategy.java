package org.evochora.datapipeline.resources.database.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;
import org.evochora.datapipeline.api.resources.database.dto.TickRange;

/**
 * H2-specific strategy interface for storing and reading organism data.
 * <p>
 * Different strategies trade off between storage size, query performance,
 * and write performance. This interface is H2-specific and mirrors the
 * {@link IH2EnvStorageStrategy} pattern for environment data.
 * <p>
 * <strong>Rationale:</strong> Row-per-organism-per-tick creates millions of rows
 * (100 organisms × 200k ticks = 20M rows), causing slow H2 MERGE operations.
 * BLOB strategies reduce this to one row per tick, improving performance by ~100×.
 * <p>
 * <strong>Static Table:</strong> The {@code organisms} table (static metadata)
 * is NOT affected by this strategy - it remains row-per-organism and is handled
 * separately in {@link #createTables(Connection)} and {@link #writeOrganisms(Connection, PreparedStatement, List)}.
 */
public interface IH2OrgStorageStrategy {
    
    /**
     * Creates the necessary tables and indexes for this storage strategy.
     * <p>
     * <strong>Note:</strong> This creates TABLE schema (columns, indexes), not database schema
     * (namespace). The database schema (SIM_xxx) is already created and set by AbstractIndexer
     * before this method is called.
     * <p>
     * <strong>Tables Created:</strong>
     * <ul>
     *   <li>{@code organisms} - Static organism metadata (always row-per-organism)</li>
     *   <li>{@code organism_ticks} or {@code organism_states} - Per-tick data (strategy-specific)</li>
     * </ul>
     * <p>
     * <strong>Idempotency:</strong> Must use CREATE TABLE IF NOT EXISTS and CREATE INDEX IF NOT EXISTS.
     * Multiple indexer instances may call this concurrently. Use {@link org.evochora.datapipeline.utils.H2SchemaUtil#executeDdlIfNotExists}
     * for race-safe DDL execution.
     *
     * @param conn Database connection (schema already set to SIM_xxx, autoCommit=false)
     * @throws SQLException if table creation fails
     */
    void createTables(Connection conn) throws SQLException;
    
    /**
     * Returns the SQL string for the organisms (static) table MERGE statement.
     * <p>
     * This SQL is used by H2Database to create a cached PreparedStatement for performance.
     *
     * @return SQL string for MERGE operation on organisms table
     */
    String getOrganismsMergeSql();
    
    /**
     * Returns the SQL string for the organism states (per-tick) MERGE statement.
     * <p>
     * This SQL is used by H2Database to create a cached PreparedStatement for performance.
     *
     * @return SQL string for MERGE operation on organism states table
     */
    String getStatesMergeSql();
    
    /**
     * Writes static organism metadata (organisms table) for all ticks.
     * <p>
     * Extracts unique organisms from all ticks and upserts into organisms table.
     * This is always row-per-organism regardless of strategy.
     *
     * @param conn Database connection (with autoCommit=false, transaction managed by caller)
     * @param stmt Cached PreparedStatement for MERGE operation (from getOrganismsMergeSql())
     * @param ticks List of ticks containing organism data
     * @throws SQLException if write fails (caller will rollback)
     */
    void writeOrganisms(Connection conn, PreparedStatement stmt, List<TickData> ticks) 
            throws SQLException;
    
    /**
     * Writes per-tick organism states using this storage strategy.
     * <p>
     * <strong>Transaction Management:</strong> This method is executed within a transaction
     * managed by the caller (H2Database). Implementations should <strong>NOT</strong> call
     * {@code commit()} or {@code rollback()} themselves.
     *
     * @param conn Database connection (with autoCommit=false, transaction managed by caller)
     * @param stmt Cached PreparedStatement for MERGE operation (from getStatesMergeSql())
     * @param ticks List of ticks with organism data to write
     * @throws SQLException if write fails (caller will rollback)
     */
    void writeStates(Connection conn, PreparedStatement stmt, List<TickData> ticks) 
            throws SQLException;
    
    /**
     * Reads all organisms that have state for the given tick.
     *
     * @param conn Database connection (schema already set)
     * @param tickNumber Tick to read
     * @return List of organism summaries for this tick (may be empty)
     * @throws SQLException if database read fails
     */
    List<OrganismTickSummary> readOrganismsAtTick(Connection conn, long tickNumber) 
            throws SQLException;
    
    /**
     * Returns the available tick range for organism data.
     *
     * @param conn Database connection (schema already set)
     * @return TickRange with min/max tick numbers, or null if no data
     * @throws SQLException if database read fails
     */
    TickRange getAvailableTickRange(Connection conn) throws SQLException;
    
    /**
     * Reads a single organism's state at the given tick.
     * <p>
     * Used by H2DatabaseReader for detailed organism queries.
     *
     * @param conn Database connection (schema already set)
     * @param tickNumber Tick to read
     * @param organismId Organism identifier
     * @return OrganismState Protobuf object, or null if not found
     * @throws SQLException if database read fails
     */
    org.evochora.datapipeline.api.contracts.OrganismState readSingleOrganismState(
            Connection conn, long tickNumber, int organismId) throws SQLException;
}
