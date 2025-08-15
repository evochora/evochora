package org.evochora.server.contracts;

import java.util.List;

/**
 * Message containing the complete world state for a single tick.
 *
 * @param tickNumber           current tick number (long)
 * @param timestampMicroseconds wall-clock timestamp in microseconds
 * @param organismStates       all organism snapshots
 * @param cellStates           all non-empty cell snapshots
 */
public record WorldStateMessage(
        long tickNumber,
        long timestampMicroseconds,
        List<OrganismState> organismStates,
        List<CellState> cellStates
) implements IQueueMessage {
}


