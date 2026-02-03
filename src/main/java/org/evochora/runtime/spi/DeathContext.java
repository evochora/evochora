package org.evochora.runtime.spi;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;

/**
 * Context object passed to {@link IDeathHandler#onDeath(DeathContext)}.
 * <p>
 * Provides restricted read-write access to only the dying organism's cells.
 * This restriction ensures thread-safety for future parallel execution.
 * </p>
 * <p>
 * This class is reused across organism deaths to avoid allocation.
 * Cell access methods ({@link #getMolecule()}, {@link #setMolecule(Molecule)})
 * can only be called within the {@link #forEachOwnedCell(Runnable)} callback.
 * </p>
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * public void onDeath(DeathContext ctx) {
 *     ctx.forEachOwnedCell(() -> {
 *         Molecule mol = ctx.getMolecule();
 *         if (!mol.isEmpty()) {
 *             ctx.setMolecule(new Molecule(TYPE_ENERGY, 1000));
 *         }
 *     });
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * Access is restricted to only the dying organism's cells. Different organisms
 * have disjoint cell sets, making parallel death handling safe.
 *
 * @see IDeathHandler
 */
public class DeathContext {

    private Environment environment;
    private IntOpenHashSet ownedCells;
    private boolean initialized = false;
    private int currentFlatIndex = -1;

    /**
     * Resets context for reuse - zero allocation.
     * <p>
     * <b>Internal use only:</b> Called by Simulation when an organism dies.
     * Death handlers should not call this method.
     *
     * @param environment The simulation environment
     * @param organismId The ID of the dying organism
     */
    public void reset(Environment environment, int organismId) {
        this.environment = environment;
        this.ownedCells = environment.getCellsOwnedBy(organismId);
        this.initialized = true;
        this.currentFlatIndex = -1;
    }

    /**
     * Iterates over all cells owned by the dying organism.
     * <p>
     * Within the callback, {@link #getMolecule()} and {@link #setMolecule(Molecule)}
     * can be used to read/modify the current cell.
     * </p>
     * <p>
     * If the organism owned no cells, the callback is never invoked.
     * </p>
     *
     * @param action The action to perform for each owned cell
     * @throws IllegalStateException if context was not initialized via {@link #reset}
     */
    public void forEachOwnedCell(Runnable action) {
        if (!initialized) {
            throw new IllegalStateException("DeathContext not initialized - reset() must be called first");
        }
        if (ownedCells == null) {
            return; // Organism had no cells - valid state
        }
        ownedCells.forEach(flatIndex -> {
            currentFlatIndex = flatIndex;
            action.run();
        });
        currentFlatIndex = -1;
    }

    /**
     * Returns the flat index of the current cell being iterated.
     * <p>
     * Can only be called within {@link #forEachOwnedCell(Runnable)}.
     * </p>
     *
     * @return The flat index of the current cell
     * @throws IllegalStateException if called outside of forEachOwnedCell
     */
    public int getFlatIndex() {
        checkCurrentCell();
        return currentFlatIndex;
    }

    /**
     * Returns the molecule at the current cell being iterated.
     * <p>
     * Can only be called within {@link #forEachOwnedCell(Runnable)}.
     * </p>
     *
     * @return The molecule at the current cell
     * @throws IllegalStateException if called outside of forEachOwnedCell
     */
    public Molecule getMolecule() {
        checkCurrentCell();
        return environment.getMoleculeByIndex(currentFlatIndex);
    }

    /**
     * Sets the molecule at the current cell being iterated.
     * <p>
     * Can only be called within {@link #forEachOwnedCell(Runnable)}.
     * This is the only way to modify cells - direct environment access is not provided.
     * </p>
     *
     * @param molecule The molecule to set
     * @throws IllegalStateException if called outside of forEachOwnedCell
     */
    public void setMolecule(Molecule molecule) {
        checkCurrentCell();
        environment.setMoleculeByIndex(currentFlatIndex, molecule);
    }

    private void checkCurrentCell() {
        if (currentFlatIndex == -1) {
            throw new IllegalStateException("Can only be called within forEachOwnedCell callback");
        }
    }
}
