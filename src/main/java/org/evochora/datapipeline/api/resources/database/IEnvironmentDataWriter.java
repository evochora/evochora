package org.evochora.datapipeline.api.resources.database;

import java.sql.SQLException;
import java.util.List;

import org.evochora.datapipeline.api.contracts.TickDataChunk;

/**
 * Database capability for writing environment data as chunks.
 * <p>
 * Provides write operations for environment chunks with delta compression support.
 * Used by EnvironmentIndexer to persist entire chunks (snapshot + deltas) for
 * maximum storage efficiency.
 * <p>
 * <strong>Delta Compression:</strong> Chunks are stored as-is without decompression.
 * Decompression happens later in the EnvironmentController when data is queried.
 * This approach:
 * <ul>
 *   <li>Minimizes database storage (deltas are much smaller than full snapshots)</li>
 *   <li>Reduces write I/O (fewer bytes to write)</li>
 *   <li>Defers decompression to query time (only decompress what's needed)</li>
 * </ul>
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
     * Creates the environment_chunks table idempotently.
     * <p>
     * Table schema supports chunk storage:
     * <pre>
     * CREATE TABLE environment_chunks (
     *   first_tick BIGINT PRIMARY KEY,
     *   last_tick BIGINT NOT NULL,
     *   chunk_blob BYTEA NOT NULL
     * )
     * </pre>
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
     * Writes environment chunks using MERGE for idempotency.
     * <p>
     * All chunks are written in one JDBC batch with one commit for maximum performance.
     * Each chunk is stored as a serialized BLOB without decompression.
     * <p>
     * Each chunk is identified by first_tick and written with MERGE:
     * <ul>
     *   <li>If chunk exists: UPDATE (overwrite with new data)</li>
     *   <li>If chunk missing: INSERT</li>
     * </ul>
     * <p>
     * This ensures 100% idempotency even with topic redeliveries.
     * <p>
     * <strong>Performance:</strong> All chunks written in one JDBC batch with one commit.
     * This reduces commit overhead by ~1000× compared to per-chunk commits.
     *
     * @param chunks List of chunks to write
     * @throws SQLException if database write fails
     */
    void writeEnvironmentChunks(List<TickDataChunk> chunks) throws SQLException;
    
    /**
     * Writes a single raw environment chunk (uncompressed protobuf bytes) to storage.
     * <p>
     * Part of the streaming raw-byte write path: chunks are passed through without
     * parsing or re-serialization. The storage strategy compresses and writes the
     * bytes directly.
     * <p>
     * Multiple calls accumulate a batch. Call {@link #commitRawChunks()} to persist
     * the accumulated batch atomically.
     *
     * @param firstTick First tick number in the chunk
     * @param lastTick Last tick number in the chunk
     * @param tickCount Number of sampled ticks in the chunk
     * @param rawProtobufData Uncompressed protobuf bytes of one TickDataChunk message
     * @throws Exception if write fails
     */
    void writeRawChunk(long firstTick, long lastTick, int tickCount,
                       byte[] rawProtobufData) throws Exception;

    /**
     * Commits all raw chunks accumulated via {@link #writeRawChunk} calls.
     * <p>
     * Executes the JDBC batch and commits the transaction atomically.
     * After this call, the write session is ready for the next batch.
     *
     * @throws Exception if commit fails (transaction is rolled back)
     */
    void commitRawChunks() throws Exception;

    /**
     * Closes the database wrapper and releases its dedicated connection back to the pool.
     * <p>
     * This method is automatically called when used with try-with-resources.
     * Implementations must ensure the connection is properly closed even if errors occur.
     */
    @Override
    void close();
}
