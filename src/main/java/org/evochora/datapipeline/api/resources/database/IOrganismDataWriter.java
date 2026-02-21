package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.contracts.TickData;

import java.sql.SQLException;

/**
 * Database capability for writing indexed organism data for a single simulation run.
 * <p>
 * Implementations operate on a schema-per-run database. The active schema is set
 * via {@link ISchemaAwareDatabase#setSimulationRun(String)} by the indexer after
 * run discovery. No run identifier column is stored in the tables themselves.
 * <p>
 * <strong>Schema contract</strong>
 * <ul>
 *   <li>Static organism table (one row per organism in a run):</li>
 * </ul>
 * <pre>
 * CREATE TABLE IF NOT EXISTS organisms (
 *   organism_id      INT      PRIMARY KEY,
 *   parent_id        INT      NULL,
 *   birth_tick       BIGINT   NOT NULL,
 *   program_id       TEXT     NOT NULL,
 *   initial_position BYTEA    NOT NULL
 * );
 * </pre>
 * <ul>
 *   <li>Per-tick organism state table (one row per living organism and tick):</li>
 * </ul>
 * <pre>
 * CREATE TABLE IF NOT EXISTS organism_states (
 *   tick_number        BIGINT   NOT NULL,
 *   organism_id        INT      NOT NULL,
 *   energy             INT      NOT NULL,
 *   ip                 BYTEA    NOT NULL,
 *   dv                 BYTEA    NOT NULL,
 *   data_pointers      BYTEA    NOT NULL,
 *   active_dp_index    INT      NOT NULL,
 *   runtime_state_blob BYTEA    NOT NULL,
 *   PRIMARY KEY (tick_number, organism_id)
 * );
 * </pre>
 * <p>
 * The {@code organisms} table stores static metadata for each organism (ID, parent,
 * birth tick, program identifier, initial position). The {@code organism_states}
 * table stores per-tick dynamic state. Fields that are required for grid/dropdown
 * views (tick_number, organism_id, energy, ip, dv, data_pointers, active_dp_index)
 * are exposed as dedicated columns. All remaining runtime state (registers, stacks,
 * call stacks, failure details) is grouped into a single Protobuf message
 * {@code OrganismRuntimeState} and stored in {@code runtime_state_blob}.
 * <p>
 * <strong>Idempotency:</strong> All writes MUST use MERGE semantics on the primary keys
 * ({@code organism_id} for {@code organisms}, {@code (tick_number, organism_id)} for
 * {@code organism_states}) so that re-processing the same TickData batches yields a
 * stable final state without duplicates.
 */
public interface IOrganismDataWriter extends ISchemaAwareDatabase, AutoCloseable {

    /**
     * Creates the {@code organisms} and {@code organism_states} tables in the current
     * schema if they do not yet exist.
     * <p>
     * Implementations must:
     * <ul>
     *   <li>Use {@code CREATE TABLE IF NOT EXISTS} and the shared H2 DDL helper
     *       (see indexer foundation and environment indexer specs) to avoid
     *       concurrent DDL race conditions.</li>
     *   <li>Ensure the table definitions match the contract documented in
     *       {@link IOrganismDataWriter}.</li>
     * </ul>
     *
     * @throws SQLException if schema creation fails
     */
    void createOrganismTables() throws SQLException;

    // ========================================================================
    // Streaming write methods (per-tick addBatch / commit)
    // ========================================================================

    /**
     * Adds organism data from a single tick to the write session.
     * <p>
     * Implementations extract static organism metadata (for the {@code organisms} table)
     * and per-tick state (for the strategy-specific state table), adding both to internal
     * JDBC batches via {@code addBatch()}. Must NOT call {@code executeBatch()} or
     * {@code commit()} — those are handled by {@link #commitOrganismWrites()}.
     * <p>
     * Called once per tick during streaming chunk processing. Each parsed tick is
     * GC-eligible immediately after this method returns.
     *
     * <strong>Thread Safety:</strong> Not thread-safe. Callers must ensure sequential access
     * per write session (one connection = one thread).
     *
     * @param tick Tick data containing organism states (must not be null)
     * @throws SQLException if batch addition fails
     */
    void writeOrganismTick(TickData tick) throws SQLException;

    /**
     * Executes all accumulated batches from previous {@link #writeOrganismTick} calls
     * and commits the transaction.
     * <p>
     * After this call, all organism data added since the last commit (or session start)
     * is durably persisted. Internal deduplication state is reset for the next commit window,
     * but prepared statements remain open for reuse.
     *
     * @throws SQLException if batch execution or commit fails
     */
    void commitOrganismWrites() throws SQLException;

    /**
     * Releases any dedicated database resources (e.g. connections) held by this
     * writer. Implementations must ensure that resources are always released,
     * even if previous operations have failed.
     *
     * @throws SQLException if closing the underlying database resources fails
     */
    @Override
    void close() throws SQLException;
}


