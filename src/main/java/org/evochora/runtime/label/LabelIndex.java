package org.evochora.runtime.label;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.OrganismRandom;
import org.evochora.runtime.spi.ILabelMatchingStrategy;

/**
 * Keeps the label matching strategy informed about the labels that are jump targets.
 * <p>
 * A LABEL molecule is a jump target while its marker is 0. A label written with a non-zero marker
 * belongs to a body an organism is still building for a child; it is invisible to every lookup, its
 * writer's included, until a fork or the owner's death resets the marker. The rule sits here, ahead
 * of the {@link ILabelMatchingStrategy}, so that it holds for every strategy: a strategy is told
 * about a label only while the label is a target.
 * <p>
 * The environment reports every change of a cell's molecule or owner; this class turns those into
 * the strategy's additions, removals and owner changes, and passes lookups through.
 * <p>
 * Thread Safety: lookups ({@link #findTarget}) are safe for concurrent callers and are issued
 * from every thread of the parallel wave; mutations are issued only from the simulation thread
 * outside the wave, through the environment's own mutators.
 */
public class LabelIndex {

    private final ILabelMatchingStrategy strategy;

    /**
     * Creates a new LabelIndex with the default strategy, {@link HammingLabelMatchingStrategy}
     * with its default settings.
     */
    public LabelIndex() {
        this(new HammingLabelMatchingStrategy());
    }

    /**
     * Creates a new LabelIndex with the specified matching strategy.
     *
     * @param strategy The matching strategy to use
     */
    public LabelIndex(ILabelMatchingStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * Resolves the label reference of a jump instruction.
     *
     * @param searchValue The label value to search for (from jump operand)
     * @param codeOwner The ID of the organism executing the lookup
     * @param callerCoords The coordinates of the calling instruction (for distance calculation)
     * @param random The random source of the organism executing the lookup (see
     *               {@link ILabelMatchingStrategy#findTarget})
     * @return The flat index of the target label, or -1 if the reference resolves to none
     */
    public int findTarget(int searchValue, int codeOwner, int[] callerCoords, OrganismRandom random) {
        return strategy.findTarget(searchValue, codeOwner, callerCoords, random);
    }

    /**
     * Called when a molecule is set in the environment.
     * <p>
     * Only a LABEL molecule whose marker is 0 is a jump target. A marked label is neither added
     * nor — never having been added — removed:
     * <ul>
     *   <li>If the old molecule was an unmarked LABEL: remove it under the cell's old owner</li>
     *   <li>If the new molecule is an unmarked LABEL: add it under the cell's new owner</li>
     * </ul>
     *
     * @param flatIndex The flat index of the cell
     * @param oldMoleculeInt The old molecule's packed integer value (0 if cell was empty)
     * @param oldOwner The owner ID the cell had before the write
     * @param newMoleculeInt The new molecule's packed integer value
     * @param newOwner The owner ID the cell has after the write
     */
    public void onMoleculeSet(int flatIndex, int oldMoleculeInt, int oldOwner, int newMoleculeInt, int newOwner) {
        if (isUnmarkedLabel(oldMoleculeInt)) {
            strategy.removeLabel(oldMoleculeInt & Config.VALUE_MASK, flatIndex, oldOwner);
        }
        if (isUnmarkedLabel(newMoleculeInt)) {
            strategy.addLabel(newMoleculeInt & Config.VALUE_MASK, flatIndex, newOwner);
        }
    }

    /**
     * Called when ownership of a cell changes while its molecule stays as it is.
     * <p>
     * If the cell contains an unmarked LABEL molecule, the strategy learns of the new owner. A
     * marked label is not a target and the strategy does not know it.
     *
     * @param flatIndex The flat index of the cell
     * @param moleculeInt The molecule's packed integer value
     * @param oldOwner The owner ID the cell had until now
     * @param newOwner The new owner ID
     */
    public void onOwnerChange(int flatIndex, int moleculeInt, int oldOwner, int newOwner) {
        if (isUnmarkedLabel(moleculeInt)) {
            strategy.changeOwner(moleculeInt & Config.VALUE_MASK, flatIndex, oldOwner, newOwner);
        }
    }

    /**
     * Called when a cell is released: it passes to a new owner — a child at a fork, nobody at a
     * death — and its marker is reset to 0 in the same step.
     * <p>
     * A LABEL that was marked becomes a jump target at this moment and is added under its new
     * owner. A LABEL that was unmarked is a target already and changes its owner.
     *
     * @param flatIndex The flat index of the cell
     * @param oldMoleculeInt The molecule's packed integer value before the release, with the
     *                       marker it carried until then
     * @param oldOwner The owner ID the cell had until now
     * @param newOwner The owner ID the cell passes to; {@code 0} for nobody
     */
    public void onCellReleased(int flatIndex, int oldMoleculeInt, int oldOwner, int newOwner) {
        if ((oldMoleculeInt & Config.TYPE_MASK) != Config.TYPE_LABEL) {
            return;
        }
        int value = oldMoleculeInt & Config.VALUE_MASK;
        if ((oldMoleculeInt & Config.MARKER_MASK) == 0) {
            strategy.changeOwner(value, flatIndex, oldOwner, newOwner);
        } else {
            strategy.addLabel(value, flatIndex, newOwner);
        }
    }

    /**
     * Whether a packed molecule is a LABEL that takes part in matching, that is, one with marker 0.
     *
     * @param moleculeInt The molecule's packed integer value
     * @return {@code true} for a LABEL molecule whose marker is 0
     */
    private static boolean isUnmarkedLabel(int moleculeInt) {
        return (moleculeInt & Config.TYPE_MASK) == Config.TYPE_LABEL
                && (moleculeInt & Config.MARKER_MASK) == 0;
    }

    /**
     * Gets the underlying matching strategy.
     *
     * @return The matching strategy
     */
    public ILabelMatchingStrategy getStrategy() {
        return strategy;
    }
}
