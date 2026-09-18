package org.evochora.runtime.label;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.OrganismRandom;

import java.util.Collection;

/**
 * Index for efficient fuzzy label lookup in the simulation environment.
 * <p>
 * The LabelIndex maintains an index of the LABEL molecules in the environment that are jump
 * targets: those whose marker is 0. A label written with a non-zero marker belongs to a body an
 * organism is still building for a child; it is invisible to every lookup, its writer's included,
 * until a fork or the owner's death resets the marker. The rule sits here, ahead of the matching
 * strategy, so that it holds for every strategy.
 * <p>
 * This class delegates to an {@link ILabelMatchingStrategy} for the actual
 * matching logic, allowing different strategies to be used (e.g., pre-expanded
 * Hamming, linear search, etc.).
 * <p>
 * Usage:
 * <pre>
 * // Create index with default strategy
 * LabelIndex index = new LabelIndex();
 *
 * // Called by Environment.setMolecule() when a LABEL is placed
 * index.onMoleculeSet(flatIndex, oldMolecule, newMolecule, owner);
 *
 * // Called by ControlFlowInstruction to find jump target
 * int targetIndex = index.findTarget(labelValue, codeOwner, callerCoords, environment, organism.getRandom());
 * </pre>
 * <p>
 * Thread Safety: lookups ({@link #findTarget}) are safe for concurrent callers and are issued
 * from every thread of the parallel wave; mutations are issued only from the simulation thread
 * outside the wave, through the environment's own mutators.
 */
public class LabelIndex {

    private final ILabelMatchingStrategy strategy;

    /**
     * Creates a new LabelIndex with the default pre-expanded Hamming strategy.
     */
    public LabelIndex() {
        this(new PreExpandedHammingStrategy());
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
     * Finds the best matching label for a jump instruction.
     * <p>
     * The matching algorithm considers Hamming distance, physical distance and ownership to
     * find the most appropriate target among the unmarked labels.
     *
     * @param searchValue The label value to search for (from jump operand)
     * @param codeOwner The owner ID of the executing code
     * @param callerCoords The coordinates of the calling instruction (for distance calculation)
     * @param environment The environment (for coordinate conversion and toroidal distance)
     * @param random The random source of the organism executing the lookup (see
     *               {@link ILabelMatchingStrategy#findTarget})
     * @return The flat index of the best matching label, or -1 if no match found
     */
    public int findTarget(int searchValue, int codeOwner, int[] callerCoords, Environment environment,
                          OrganismRandom random) {
        return strategy.findTarget(searchValue, codeOwner, callerCoords, environment, random);
    }

    /**
     * Called when a molecule is set in the environment.
     * <p>
     * Only a LABEL molecule whose marker is 0 is a jump target. A marked label belongs to a body
     * that is still under construction, so it is neither added nor — never having been added —
     * removed:
     * <ul>
     *   <li>If the old molecule was an unmarked LABEL: remove it from the index</li>
     *   <li>If the new molecule is an unmarked LABEL: add it to the index</li>
     * </ul>
     *
     * @param flatIndex The flat index of the cell
     * @param oldMoleculeInt The old molecule's packed integer value (0 if cell was empty)
     * @param newMoleculeInt The new molecule's packed integer value
     * @param owner The owner ID of the cell
     */
    public void onMoleculeSet(int flatIndex, int oldMoleculeInt, int newMoleculeInt, int owner) {
        if (isUnmarkedLabel(oldMoleculeInt)) {
            strategy.removeLabel(oldMoleculeInt & Config.VALUE_MASK, flatIndex);
        }
        if (isUnmarkedLabel(newMoleculeInt)) {
            strategy.addLabel(newMoleculeInt & Config.VALUE_MASK, new LabelEntry(flatIndex, owner));
        }
    }

    /**
     * Called when ownership of a cell changes while its molecule stays as it is.
     * <p>
     * If the cell contains an unmarked LABEL molecule, updates the index entry. A marked label has
     * no entry.
     *
     * @param flatIndex The flat index of the cell
     * @param moleculeInt The molecule's packed integer value
     * @param newOwner The new owner ID
     */
    public void onOwnerChange(int flatIndex, int moleculeInt, int newOwner) {
        if (isUnmarkedLabel(moleculeInt)) {
            strategy.updateOwner(moleculeInt & Config.VALUE_MASK, flatIndex, newOwner);
        }
    }

    /**
     * Called when a cell is released: it passes to a new owner — a child at a fork, nobody at a
     * death — and its marker is reset to 0 in the same step.
     * <p>
     * A LABEL that was marked becomes a jump target at this moment and enters the index under its
     * new owner. A LABEL that was unmarked already has an entry, which takes the new owner.
     *
     * @param flatIndex The flat index of the cell
     * @param oldMoleculeInt The molecule's packed integer value before the release, with the
     *                       marker it carried until then
     * @param newOwner The owner ID the cell passes to; {@code 0} for nobody
     */
    public void onCellReleased(int flatIndex, int oldMoleculeInt, int newOwner) {
        if ((oldMoleculeInt & Config.TYPE_MASK) != Config.TYPE_LABEL) {
            return;
        }
        int value = oldMoleculeInt & Config.VALUE_MASK;
        if ((oldMoleculeInt & Config.MARKER_MASK) == 0) {
            strategy.updateOwner(value, flatIndex, newOwner);
        } else {
            strategy.addLabel(value, new LabelEntry(flatIndex, newOwner));
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
     * Gets all candidates matching a search value (for debugging/testing).
     *
     * @param searchValue The label value to search for
     * @return Collection of matching label entries
     */
    public Collection<LabelEntry> getCandidates(int searchValue) {
        return strategy.getCandidates(searchValue);
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
