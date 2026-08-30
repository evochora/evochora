package org.evochora.datapipeline.api.resources.database;

import java.sql.SQLException;

/**
 * Capability interface for reading environment data.
 * <p>
 * Provides low-level access to raw environment chunks for efficient caching scenarios.
 * <p>
 * <strong>Delta Compression:</strong> Environment data is stored as chunks (snapshot + deltas).
 * Use {@link #prepareChunkRead(long)} for efficient caching when reading multiple ticks from
 * the same chunk.
 */
public interface IEnvironmentDataReader {
    
    /**
     * Reads the raw chunk containing the specified tick.
     * <p>
     * This method returns the compressed chunk as-is without decompression.
     * The caller is responsible for:
     * <ol>
     *   <li>Caching the chunk (optional, for sequential tick access)</li>
     *   <li>Decompressing using {@code DeltaCodec.Decoder.decompressTick(chunk, tickNumber)}</li>
     *   <li>Filtering by region</li>
     *   <li>Converting cell data as needed</li>
     * </ol>
     * <p>
     * <strong>Use Case:</strong> EnvironmentController uses this with an LRU cache
     * to avoid re-loading chunks when scrubbing through ticks.
     * 
     * @param tickNumber Tick number to find (chunk containing this tick will be returned)
     * @return The TickDataChunk containing the requested tick
     * @throws SQLException if database read fails
     * @throws TickNotFoundException if no chunk contains the requested tick
     */
    /**
     * Asks the database where the chunk containing a tick lies, so it can be read without it.
     * <p>
     * The question costs a query over two numbers; the read that follows costs hundreds of
     * megabytes from disk and through the parser. A caller that releases its connection between
     * the two frees the pool for the whole of the second part - which is the difference between
     * one reader and a pool exhausted by slow storage.
     *
     * @param tickNumber Tick number to find (chunk containing this tick will be read)
     * @return A read that needs no connection
     * @throws SQLException if the database read fails
     * @throws TickNotFoundException if no chunk contains the requested tick
     */
    PendingChunkRead prepareChunkRead(long tickNumber) throws SQLException, TickNotFoundException;
}
