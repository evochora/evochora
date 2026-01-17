package org.evochora.datapipeline.resources.database.h2;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;

/**
 * H2-specific strategy interface for storing and reading environment data as chunks.
 * <p>
 * This interface supports delta compression by storing entire TickDataChunks as BLOBs.
 * Each chunk contains a snapshot and deltas for a range of ticks. The caller (typically
 * EnvironmentController) is responsible for decompression using DeltaCodec.
 * <p>
 * <strong>Rationale:</strong> Storing chunks instead of individual ticks provides:
 * <ul>
 *   <li>Massive storage reduction (~30-50% compared to storing every tick as snapshot)</li>
 *   <li>Fewer database rows (100 ticks/chunk = 100× fewer rows)</li>
 *   <li>Faster MERGE operations (smaller B-tree index)</li>
 * </ul>
 * <p>
 * <strong>Decompression Strategy:</strong> Decompression happens in the EnvironmentController,
 * not in this strategy. This allows the controller to implement caching of decompressed chunks
 * for efficient sequential tick access (e.g., scrubbing through ticks).
 * <p>
 * <strong>Future Binary Response Format:</strong> The interface is designed to allow easy
 * migration to binary response formats (MessagePack, Protobuf) by keeping the strategy
 * focused on raw chunk storage without response serialization concerns.
 *
 * @see org.evochora.datapipeline.utils.delta.DeltaCodec
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
     * Writes a batch of chunks to the database.
     * <p>
     * <strong>Transaction Management:</strong> This method is executed within a transaction
     * managed by the caller (H2Database). Implementations should <strong>NOT</strong> call
     * {@code commit()} or {@code rollback()} themselves. If an exception is thrown, the
     * caller is responsible for rolling back the transaction.
     * <p>
     * Each chunk is stored as a single row with the serialized TickDataChunk as BLOB.
     * The chunk contains snapshot + deltas and is NOT decompressed before storage.
     *
     * @param conn Database connection (with autoCommit=false, transaction managed by caller)
     * @param chunks List of chunks to write
     * @throws SQLException if write fails (caller will rollback)
     */
    void writeChunks(Connection conn, List<TickDataChunk> chunks) throws SQLException;

    /**
     * Reads the chunk containing the specified tick number.
     * <p>
     * This method returns the raw TickDataChunk without decompression. The caller
     * (typically EnvironmentController) is responsible for:
     * <ol>
     *   <li>Caching the chunk (optional, for sequential access optimization)</li>
     *   <li>Decompressing using {@code DeltaCodec.decompressTick(chunk, tickNumber, totalCells)}</li>
     *   <li>Filtering by region</li>
     *   <li>Converting to response format (JSON/MessagePack)</li>
     * </ol>
     * <p>
     * <strong>Query Strategy:</strong> Uses {@code first_tick <= ? AND last_tick >= ?} to find
     * the chunk containing the requested tick.
     * 
     * @param conn Database connection (schema already set)
     * @param tickNumber Tick number to find (chunk containing this tick will be returned)
     * @return The TickDataChunk containing the requested tick
     * @throws SQLException if database read fails
     * @throws TickNotFoundException if no chunk contains the requested tick
     */
    TickDataChunk readChunkContaining(Connection conn, long tickNumber) throws SQLException, TickNotFoundException;
}
