package org.evochora.runtime.model;

/**
 * One cell of the environment as handed to a visitor: its coordinate, its content and its owner,
 * with write access to the content.
 * <p>
 * The environment hands the same view object to a visitor for every cell of one visit and moves
 * it from cell to cell, so a view is valid only inside the visitor's call and must not be retained.
 * The coordinate array is shared between cells for the same reason and must be copied if it is to
 * outlive the call. Nothing about where the cell lies in memory is visible through the view.
 */
public interface CellView {

    /**
     * The cell's position in the world, one entry per dimension, already inside the world's
     * bounds. The array is the visit's shared buffer: reading a component is free, but a visitor
     * that needs the position after the call must copy it.
     *
     * @return the cell's coordinate; a buffer shared between the cells of one visit
     */
    int[] coordinate();

    /**
     * The cell's content as the packed integer the grid stores, for visitors that inspect type
     * or value bits directly. Reading it allocates nothing, unlike {@link #molecule()}.
     *
     * @return the cell's packed molecule value, {@code 0} for an empty cell
     */
    int moleculeInt();

    /**
     * The cell's content as a record, for visitors that want to work with type, value and marker
     * as fields rather than bit masks. Each call creates a new record from the packed value, so a
     * visitor on a hot path should prefer {@link #moleculeInt()}.
     *
     * @return the cell's molecule
     */
    Molecule molecule();

    /**
     * The organism the cell belongs to. In an owned-cell visit every cell carries the visited
     * organism's id; the accessor exists so that a visitor written against this interface does not
     * have to know how the visit was selected.
     *
     * @return the id of the organism owning the cell, {@code 0} for an unowned cell
     */
    int ownerId();

    /**
     * Replaces the cell's molecule, keeping its owner. Tracked like every other write.
     *
     * @param molecule the new content
     */
    void setMolecule(Molecule molecule);
}
