package org.evochora.server.contracts;

import java.util.List;

/**
 * Snapshot of a single non-empty cell at a given tick.
 */
public record CellState(
        List<Integer> position,
        int type,
        int value,
        int ownerId
) {
}


