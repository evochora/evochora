package org.evochora.datapipeline.api.delta;

/**
 * Read access to a reconstructed environment state, one occupied cell at a time.
 * <p>
 * Hands out only what a consumer needs to look at every occupied cell, not how the state stores
 * them: there is no random access and no index arithmetic, so an implementation is free to keep
 * its cells in dense arrays, in a sparse structure, or anywhere else.
 * <p>
 * Consumers that would otherwise receive a
 * {@link org.evochora.datapipeline.api.contracts.CellDataColumns} use this instead to avoid
 * building that representation for data they only read once.
 * <p>
 * <strong>Related, but deliberately separate:</strong> a live environment answers the same
 * question through {@code Environment.forEachOccupiedCellInFlatIndexOrder}, whose visitor
 * receives the same triple of flat index, molecule and owner. Both walk the occupied cells
 * of a grid in the same order, so the two could be brought under one abstraction - it would have
 * to live in {@code runtime}, since that package may not depend on this one, and today no
 * consumer reads from both a live and a reconstructed state. Worth revisiting once one does.
 */
public interface ICellStateSource {

    /**
     * Receives one occupied cell.
     * <p>
     * A cell counts as occupied when it holds molecule data, an owner, or both - a cell whose
     * molecule data is zero can still belong to an organism.
     */
    @FunctionalInterface
    interface CellVisitor {
        /**
         * Handles one occupied cell. Called synchronously from
         * {@link ICellStateSource#forEachOccupiedCell(CellVisitor)} and never for an empty cell,
         * so a visitor that only counts calls counts occupied cells.
         *
         * @param flatIndex    position of the cell in the environment, in row-major order
         * @param moleculeData the packed molecule integer, zero if the cell holds no molecule
         * @param ownerId      id of the owning organism, zero if the cell belongs to nobody
         */
        void visit(int flatIndex, int moleculeData, int ownerId);
    }

    /**
     * Passes every occupied cell to the visitor, in ascending order of flat index.
     * <p>
     * The order is a property of the content, so two states holding the same cells - a live one
     * and one rebuilt from a snapshot - hand them out identically.
     *
     * @param visitor called once per occupied cell
     */
    void forEachOccupiedCell(CellVisitor visitor);

    /**
     * Total number of cells in the environment, occupied or not.
     *
     * @return the cell count the environment was created with
     */
    int getTotalCells();
}
