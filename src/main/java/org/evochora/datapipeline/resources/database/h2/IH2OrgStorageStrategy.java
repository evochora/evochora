package org.evochora.datapipeline.resources.database.h2;

import java.sql.Connection;
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
 * separately in {@link #createTables(Connection)} and {@link #addOrganismTick(Connection, TickData)}.
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
    
    // ========================================================================
    // Streaming write methods (per-tick addBatch / commit)
    // ========================================================================

    /**
     * Adds organism data from a single tick to the write session.
     * <p>
     * The strategy manages its own session state (PreparedStatements, deduplication,
     * compression). Implementations should call {@code addBatch()} on their internal
     * statements but must NOT call {@code executeBatch()} or {@code commit()}.
     * <p>
     * Called once per tick during streaming chunk processing. The connection is stable
     * across calls within a session.
     * <p>
     * <strong>Thread Safety:</strong> Thread-safe across different connections (each connection
     * has an isolated session). Not thread-safe for concurrent calls with the same connection.
     *
     * @param conn Database connection (autoCommit=false, transaction managed by caller)
     * @param tick Tick data containing organism states
     * @throws SQLException if batch addition fails
     */
    void addOrganismTick(Connection conn, TickData tick) throws SQLException;

    /**
     * Executes all accumulated batches from previous {@link #addOrganismTick} calls.
     * <p>
     * Implementations should call {@code executeBatch()} on their internal statements
     * and reset per-commit state (e.g., organism deduplication sets). Statements should
     * remain open for reuse in the next commit window.
     * <p>
     * Must NOT call {@code commit()} — the caller (H2Database) handles transaction commit.
     * <p>
     * <strong>Thread Safety:</strong> Thread-safe across different connections (each connection
     * has an isolated session). Not thread-safe for concurrent calls with the same connection.
     *
     * @param conn Database connection (same connection used in addOrganismTick calls)
     * @throws SQLException if batch execution fails
     */
    void commitOrganismWrites(Connection conn) throws SQLException;

    /**
     * Resets streaming session state for the given connection.
     * <p>
     * Called by the database layer after a failed {@link #commitOrganismWrites} to ensure
     * the strategy does not retain stale batch state from the failed transaction. The next
     * {@link #addOrganismTick} call will lazily re-initialize all session resources.
     * <p>
     * Implementations should close open PreparedStatements (suppressing errors) and
     * clear any accumulated state for the given connection.
     * <p>
     * <strong>Thread Safety:</strong> Thread-safe across different connections.
     *
     * @param conn The connection whose session state should be reset
     */
    void resetStreamingState(Connection conn);

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
     * Reads the total number of organisms created up to (and including) the given tick.
     * <p>
     * Uses the sequential nature of organism IDs: MAX(organism_id) WHERE birth_tick &lt;= tickNumber
     * equals the total count of organisms ever created by that tick.
     *
     * @param conn Database connection (schema already set)
     * @param tickNumber Tick number (inclusive upper bound for birth_tick)
     * @return Total organisms created, or 0 if no organisms exist
     * @throws SQLException if database read fails
     */
    int readTotalOrganismsCreated(Connection conn, long tickNumber) throws SQLException;

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
