package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.database.dto.CellWithCoordinates;
import org.evochora.datapipeline.api.resources.database.dto.SpatialRegion;

import java.sql.SQLException;
import java.util.List;

/**
 * Capability interface for reading environment data.
 * <p>
 * Provides two levels of abstraction:
 * <ul>
 *   <li>{@link #readEnvironmentRegion(long, SpatialRegion)} - High-level: Returns ready-to-use cell data</li>
 *   <li>{@link #readChunkContaining(long)} - Low-level: Returns raw chunk for caching scenarios</li>
 * </ul>
 * <p>
 * <strong>Delta Compression:</strong> Environment data is stored as chunks (snapshot + deltas).
 * Use {@code readChunkContaining()} for efficient caching when reading multiple ticks from
 * the same chunk. Use {@code readEnvironmentRegion()} for simple single-tick queries.
 */
public interface IEnvironmentDataReader {
    
    /**
     * Reads environment cells for a specific tick with optional region filtering.
     * <p>
     * This is a convenience method that internally:
     * <ol>
     *   <li>Loads the chunk containing the tick</li>
     *   <li>Decompresses to reconstruct the tick</li>
     *   <li>Filters by region</li>
     *   <li>Converts to CellWithCoordinates</li>
     * </ol>
     * <p>
     * For repeated reads of ticks in the same chunk, consider using
     * {@link #readChunkContaining(long)} with external caching.
     * 
     * @param tickNumber Tick to read
     * @param region Spatial bounds (null = all cells)
     * @return List of cells with coordinates within region
     * @throws SQLException if database read fails
     * @throws TickNotFoundException if the tick itself does not exist in the database
     */
    List<CellWithCoordinates> readEnvironmentRegion(long tickNumber, SpatialRegion region)
        throws SQLException, TickNotFoundException;
    
    /**
     * Reads the raw chunk containing the specified tick.
     * <p>
     * This method returns the compressed chunk as-is without decompression.
     * The caller is responsible for:
     * <ol>
     *   <li>Caching the chunk (optional, for sequential tick access)</li>
     *   <li>Decompressing using {@code DeltaCodec.decompressTick(chunk, tickNumber, totalCells)}</li>
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
    TickDataChunk readChunkContaining(long tickNumber) throws SQLException, TickNotFoundException;
}
