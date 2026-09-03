package org.evochora.runtime.model;

/**
 * Receives cells for serialization: each under its canonical index, the row-major numbering of
 * {@link EnvironmentProperties} in which cells are persisted. The environment calls the visitor in
 * ascending canonical order, so the cells of one visit arrive the same way whatever the
 * environment's memory layout.
 */
@FunctionalInterface
public interface CanonicalCellVisitor {

    /**
     * Receives one occupied cell, that is a cell holding a molecule, an owner, or both. The three
     * values are the cell's complete persisted state, so a visitor that only counts calls counts
     * occupied cells, and one that records all three can rebuild the world.
     *
     * @param canonicalIndex the cell's canonical index
     * @param moleculeInt    the cell's packed molecule value
     * @param ownerId        the id of the organism owning the cell, {@code 0} if none
     */
    void visit(int canonicalIndex, int moleculeInt, int ownerId);
}
