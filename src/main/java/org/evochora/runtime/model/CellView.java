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
     * @return the cell's coordinate; a buffer shared between the cells of one visit
     */
    int[] coordinate();

    /**
     * @return the cell's packed molecule value, {@code 0} for an empty cell
     */
    int moleculeInt();

    /**
     * @return the cell's molecule
     */
    Molecule molecule();

    /**
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
