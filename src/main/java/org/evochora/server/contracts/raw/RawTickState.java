package org.evochora.server.contracts.raw;

import org.evochora.server.contracts.IQueueMessage;

import java.util.List;

/**
 * Top-level container for all raw state data for a single tick.
 * This is the object that will be passed from the SimulationEngine to the
 * InMemoryTickQueue.
 *
 * @param tickNumber The current tick number.
 * @param organisms A list of all active organisms' raw states.
 * @param cells A list of all non-empty cells' raw states.
 */
public record RawTickState(
        long tickNumber,
        List<RawOrganismState> organisms,
        List<RawCellState> cells
) implements IQueueMessage {}
