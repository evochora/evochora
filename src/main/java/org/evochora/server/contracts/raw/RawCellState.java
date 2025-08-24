package org.evochora.server.contracts.raw;

/**
 * Represents a single, non-empty cell in the world in its raw format.
 * This record is designed for speed and minimal overhead during persistence.
 *
 * @param pos The raw integer array representing the cell's position.
 * @param molecule The raw 32-bit integer value of the molecule in the cell.
 * @param ownerId The ID of the organism that owns this cell.
 */
public record RawCellState(
        int[] pos,
        int molecule,
        int ownerId
) {}
