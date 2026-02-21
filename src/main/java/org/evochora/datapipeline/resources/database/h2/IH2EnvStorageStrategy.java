package org.evochora.datapipeline.resources.database.h2;

import java.sql.Connection;
import java.sql.SQLException;

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
 *   <li>Fewer database rows (50 ticks/chunk = 50× fewer rows)</li>
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
     * Writes a single raw chunk (uncompressed protobuf bytes) to the filesystem and
     * adds the tick-range entry to the JDBC batch.
     * <p>
     * This method is part of a stateful write session: the first call lazily initializes
     * a {@link PreparedStatement} and schema directory. Subsequent calls reuse these.
     * The statement is kept open across calls for batch efficiency.
     * <p>
     * <strong>Transaction Management:</strong> This method does NOT commit or close
     * the statement. Call {@link #commitRawChunks(Connection)} to execute the batch,
     * and the caller (H2Database) handles the commit.
     *
     * @param conn Database connection (with autoCommit=false, schema already set)
     * @param firstTick First tick number in the chunk
     * @param lastTick Last tick number in the chunk
     * @param tickCount Number of sampled ticks in the chunk
     * @param rawProtobufData Uncompressed protobuf bytes of one TickDataChunk message
     * @throws SQLException if file I/O or statement preparation fails
     */
    void writeRawChunk(Connection conn, long firstTick, long lastTick,
                       int tickCount, byte[] rawProtobufData) throws SQLException;

    /**
     * Executes the accumulated JDBC batch from preceding {@link #writeRawChunk} calls.
     * <p>
     * The {@link java.sql.PreparedStatement} is kept open for reuse by subsequent
     * write calls. Call {@link #resetStreamingState(Connection)} to close the statement
     * and release session resources (e.g., after a commit failure).
     * Does NOT commit the transaction — the caller handles that.
     *
     * @param conn Database connection (same connection used in writeRawChunk calls)
     * @throws SQLException if batch execution fails
     */
    void commitRawChunks(Connection conn) throws SQLException;

    /**
     * Resets streaming state for the given connection, closing any cached
     * {@link java.sql.PreparedStatement} and releasing session resources.
     * <p>
     * Called by H2Database after a commit failure to prevent stale batch state
     * from contaminating the next write session. The next {@link #writeRawChunk}
     * call will create a fresh statement.
     *
     * @param conn The connection whose session state should be cleared
     */
    void resetStreamingState(Connection conn);

    /**
     * Reads the chunk containing the specified tick number.
     * <p>
     * This method returns the raw TickDataChunk without decompression. The caller
     * (typically EnvironmentController) is responsible for:
     * <ol>
     *   <li>Caching the chunk (optional, for sequential access optimization)</li>
     *   <li>Decompressing using {@code DeltaCodec.Decoder.decompressTick(chunk, tickNumber)}</li>
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
