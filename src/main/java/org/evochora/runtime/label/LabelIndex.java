package org.evochora.runtime.label;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;

import java.util.Collection;

/**
 * Index for efficient fuzzy label lookup in the simulation environment.
 * <p>
 * The LabelIndex maintains an index of all LABEL molecules in the environment,
 * enabling O(1) lookup for jump targets using Hamming distance tolerance.
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
 * int targetIndex = index.findTarget(labelValue, codeOwner);
 * </pre>
 * <p>
 * Thread Safety: Not thread-safe. All operations are expected to be called from
 * the main simulation thread.
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
     * The matching algorithm considers Hamming distance, physical distance, ownership,
     * and transfer markers to find the most appropriate target.
     *
     * @param searchValue The label value to search for (from jump operand)
     * @param codeOwner The owner ID of the executing code
     * @param callerCoords The coordinates of the calling instruction (for distance calculation)
     * @param environment The environment (for coordinate conversion and toroidal distance)
     * @return The flat index of the best matching label, or -1 if no match found
     */
    public int findTarget(int searchValue, int codeOwner, int[] callerCoords, Environment environment) {
        return strategy.findTarget(searchValue, codeOwner, callerCoords, environment);
    }

    /**
     * Called when a molecule is set in the environment.
     * <p>
     * This method updates the index based on LABEL molecule changes:
     * <ul>
     *   <li>If old molecule was LABEL: remove from index</li>
     *   <li>If new molecule is LABEL: add to index</li>
     * </ul>
     *
     * @param flatIndex The flat index of the cell
     * @param oldMoleculeInt The old molecule's packed integer value (0 if cell was empty)
     * @param newMoleculeInt The new molecule's packed integer value
     * @param owner The owner ID of the cell
     */
    public void onMoleculeSet(int flatIndex, int oldMoleculeInt, int newMoleculeInt, int owner) {
        int oldType = oldMoleculeInt & Config.TYPE_MASK;
        int newType = newMoleculeInt & Config.TYPE_MASK;

        // Remove old LABEL if present
        if (oldType == Config.TYPE_LABEL) {
            int oldValue = oldMoleculeInt & Config.VALUE_MASK;
            strategy.removeLabel(oldValue, flatIndex);
        }

        // Add new LABEL if present
        if (newType == Config.TYPE_LABEL) {
            int newValue = newMoleculeInt & Config.VALUE_MASK;
            // Use unsigned shift (>>>) to avoid sign-extension when bit 31 is set (marker >= 8)
            int marker = (newMoleculeInt & Config.MARKER_MASK) >>> Config.MARKER_SHIFT;
            LabelEntry entry = new LabelEntry(flatIndex, owner, marker);
            strategy.addLabel(newValue, entry);
        }
    }

    /**
     * Called when ownership of a cell changes.
     * <p>
     * If the cell contains a LABEL molecule, updates the index entry.
     *
     * @param flatIndex The flat index of the cell
     * @param moleculeInt The molecule's packed integer value
     * @param newOwner The new owner ID
     */
    public void onOwnerChange(int flatIndex, int moleculeInt, int newOwner) {
        int type = moleculeInt & Config.TYPE_MASK;
        if (type == Config.TYPE_LABEL) {
            int value = moleculeInt & Config.VALUE_MASK;
            strategy.updateOwner(value, flatIndex, newOwner);
        }
    }

    /**
     * Called when the marker of a cell changes (e.g., after transfer/FORK).
     * <p>
     * If the cell contains a LABEL molecule, updates the index entry.
     *
     * @param flatIndex The flat index of the cell
     * @param moleculeInt The molecule's packed integer value (with new marker already set)
     */
    public void onMarkerChange(int flatIndex, int moleculeInt) {
        int type = moleculeInt & Config.TYPE_MASK;
        if (type == Config.TYPE_LABEL) {
            int value = moleculeInt & Config.VALUE_MASK;
            // Use unsigned shift (>>>) to avoid sign-extension when bit 31 is set (marker >= 8)
            int marker = (moleculeInt & Config.MARKER_MASK) >>> Config.MARKER_SHIFT;
            strategy.updateMarker(value, flatIndex, marker);
        }
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
