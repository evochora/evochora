package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.resources.database.dto.OrganismTickDetails;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;

import java.sql.SQLException;
import java.util.List;

/**
 * Capability interface for reading indexed organism data.
 */
public interface IOrganismDataReader {

    /**
     * Reads all organisms that have state in {@code organism_states} for the given tick.
     *
     * @param tickNumber Tick number to query (must be &gt;= 0).
     * @return List of organism summaries for this tick (may be empty if no organisms exist).
     * @throws SQLException if database read fails.
     */
    List<OrganismTickSummary> readOrganismsAtTick(long tickNumber) throws SQLException;

    /**
     * Reads static and dynamic state of a single organism at the given tick.
     *
     * @param tickNumber Tick number to query.
     * @param organismId Organism identifier (must be &gt;= 0).
     * @return Detailed view of the organism at the given tick.
     * @throws SQLException if database read fails.
     * @throws OrganismNotFoundException if no state exists for the given organism at the given tick.
     */
    OrganismTickDetails readOrganismDetails(long tickNumber, int organismId)
            throws SQLException, OrganismNotFoundException;

    /**
     * Reads the total number of organisms created up to (and including) the given tick.
     *
     * @param tickNumber Tick number (inclusive upper bound).
     * @return Total organisms created by this tick, or 0 if none exist.
     * @throws SQLException if database read fails.
     */
    int readTotalOrganismsCreated(long tickNumber) throws SQLException;
}


