package org.evochora.datapipeline.resources.database.h2;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.evochora.datapipeline.api.resources.database.PendingChunkRead;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
import org.evochora.datapipeline.api.resources.database.dto.ChunkIndexSummary;
import org.evochora.datapipeline.api.resources.database.dto.SampledTickRange;
import org.evochora.datapipeline.api.resources.database.dto.TickRangeExtension;

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
     * a {@link java.sql.PreparedStatement} and schema directory. Subsequent calls reuse these.
     * The statement is kept open across calls for batch efficiency.
     * <p>
     * <strong>Transaction Management:</strong> This method does NOT commit or close
     * the statement. Call {@link #commitRawChunks(Connection)} to execute the batch,
     * and the caller (H2Database) handles the commit.
     *
     * <strong>Thread Safety:</strong> Not thread-safe for the same connection. Each connection
     * has an isolated session; concurrent calls with different connections are safe.
     *
     * @param conn Database connection (with autoCommit=false, schema already set)
     * @param firstTick First tick number in the chunk
     * @param lastTick Last tick number in the chunk
     * @param tickCount Number of sampled ticks in the chunk
     * @param samplingInterval Simulation ticks between two recorded ticks of the chunk, as the
     *                         chunk states it
     * @param rawProtobufData Uncompressed protobuf bytes of one TickDataChunk message
     * @throws SQLException if file I/O or statement preparation fails
     * @throws IllegalStateException if the chunk states no sampling interval, which a build that
     *                               did not yet record it wrote and only that build can read
     */
    void writeRawChunk(Connection conn, long firstTick, long lastTick,
                       int tickCount, int samplingInterval, byte[] rawProtobufData) throws SQLException;

    /**
     * Executes the accumulated JDBC batch from preceding {@link #writeRawChunk} calls.
     * <p>
     * The {@link java.sql.PreparedStatement} is kept open for reuse by subsequent
     * write calls. Call {@link #resetStreamingState(Connection)} to close the statement
     * and release session resources (e.g., after a commit failure).
     * Does NOT commit the transaction — the caller handles that.
     *
     * <strong>Thread Safety:</strong> Not thread-safe for the same connection. Each connection
     * has an isolated session; concurrent calls with different connections are safe.
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
     * <strong>Thread Safety:</strong> Thread-safe across different connections.
     *
     * @param conn The connection whose session state should be cleared
     */
    void resetStreamingState(Connection conn);

    /**
     * Takes from the connection whatever locating the chunk requires, and hands back a read that
     * needs none.
     * <p>
     * A strategy keeping chunks beside the database answers where the file lies; one keeping them
     * inside it reads here and hands back what it already holds.
     *
     * @param conn Database connection (schema already set)
     * @param tickNumber Tick number to find (chunk containing this tick will be read)
     * @return A read that needs no connection
     * @throws SQLException if the database read fails
     * @throws TickNotFoundException if no chunk contains the requested tick
     */
    PendingChunkRead prepareChunkRead(Connection conn, long tickNumber)
            throws SQLException, TickNotFoundException;

    /**
     * Reads how much of the run is indexed: the number of chunks and the highest tick they reach.
     * <p>
     * One aggregate query over the index, cheap enough to answer on every request. A caller that
     * derived something from the whole index earlier can compare this summary with the one it saw
     * then and read the index again only when it has changed.
     *
     * @param conn Database connection (schema already set)
     * @return The chunk count and the highest last tick; a count of zero when nothing is indexed
     * @throws SQLException if the database read fails
     */
    ChunkIndexSummary readChunkIndexSummary(Connection conn) throws SQLException;

    /**
     * Reads the stretches of ticks the run has recorded, ordered by their first tick.
     * <p>
     * Every chunk states the step it was recorded at, so nothing is inferred: a chunk continues
     * the stretch before it when it carries the same step and starts exactly one step past it,
     * and opens a new one otherwise. What comes back is therefore the coarsest description of
     * where the run's ticks are: ranges a viewer can step along, with nothing recorded between
     * them.
     *
     * @param conn Database connection (schema already set)
     * @param runId Simulation run the connection points at, named in error messages
     * @return The ranges ordered by first tick and what the read took in; the ranges are empty
     *         when nothing is indexed
     * @throws SQLException if the database read fails
     * @throws IllegalStateException if two chunks overlap, or a chunk's span does not match the
     *                               step and the number of ticks it states
     */
    TickRangeExtension readTickRanges(Connection conn, String runId) throws SQLException;

    /**
     * Continues ranges an earlier read left behind with the chunks that were indexed since.
     * <p>
     * The read starts at the chunk the known ranges end on, so that chunk is seen again and held
     * against them: where it is gone, or no longer ends where the ranges say, nothing is appended
     * and the answer is empty - the caller then reads the index in full. Otherwise the last of the
     * known ranges is treated as still open, and a chunk that carries its step and begins one step
     * past it lengthens it. Everything else follows {@link #readTickRanges(Connection, String)},
     * whose result this reproduces as long as the chunks before {@code afterFirstTick} are
     * unchanged - which the caller establishes by checking the growth against
     * {@link #readChunkIndexSummary(Connection)}.
     *
     * @param conn Database connection (schema already set)
     * @param runId Simulation run the connection points at, named in error messages
     * @param known The ranges of the earlier read, ordered by first tick
     * @param afterFirstTick First tick of the last chunk the earlier read saw
     * @return The extended ranges and what this read took in, which counts the boundary chunk
     *         neither as a chunk nor as ticks; empty where the index no longer continues what the
     *         caller knows
     * @throws SQLException if the database read fails
     * @throws IllegalStateException on the same contradictions as {@link #readTickRanges(Connection, String)}
     */
    Optional<TickRangeExtension> extendTickRanges(Connection conn, String runId,
                                                  List<SampledTickRange> known, long afterFirstTick) throws SQLException;
}
