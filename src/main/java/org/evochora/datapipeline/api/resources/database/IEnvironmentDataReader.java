package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.resources.database.dto.CellWithCoordinates;
import org.evochora.datapipeline.api.resources.database.dto.SpatialRegion;

import java.sql.SQLException;
import java.util.List;

/**
 * Capability interface for reading environment data.
 */
public interface IEnvironmentDataReader {
    /**
     * Reads environment cells for a specific tick with optional region filtering.
     * @param tickNumber Tick to read
     * @param region Spatial bounds (null = all cells)
     * @return List of cells with coordinates within region
     * @throws SQLException if database read fails
     * @throws TickNotFoundException if the tick itself does not exist in the database
     */
    List<CellWithCoordinates> readEnvironmentRegion(long tickNumber, SpatialRegion region)
        throws SQLException, TickNotFoundException;
}
