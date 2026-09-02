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
     * @param canonicalIndex the cell's canonical index
     * @param moleculeInt    the cell's packed molecule value
     * @param ownerId        the id of the organism owning the cell, {@code 0} if none
     */
    void visit(int canonicalIndex, int moleculeInt, int ownerId);
}
