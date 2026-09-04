package org.evochora.runtime.model;

/**
 * Receives cells for serialization: each under its flat index, the row-major numbering of
 * {@link EnvironmentProperties} in which cells are persisted. The environment calls the visitor in
 * ascending flat-index order, so the cells of one visit arrive the same way whatever the
 * environment's memory layout.
 */
@FunctionalInterface
public interface FlatIndexCellVisitor {

    /**
     * Receives one occupied cell, that is a cell holding a molecule, an owner, or both. The three
     * values are the cell's complete persisted state, so a visitor that only counts calls counts
     * occupied cells, and one that records all three can rebuild the world.
     *
     * @param flatIndex      the cell's flat index
     * @param moleculeInt    the cell's packed molecule value
     * @param ownerId        the id of the organism owning the cell, {@code 0} if none
     */
    void visit(int flatIndex, int moleculeInt, int ownerId);
}
