package org.evochora.runtime.label;

import org.evochora.runtime.model.Environment;

import java.util.Collection;

/**
 * Strategy interface for label matching algorithms.
 * <p>
 * This interface enables different label matching strategies to be used interchangeably,
 * following the Strategy pattern. The default implementation uses pre-expanded Hamming
 * distance for O(1) lookup.
 * <p>
 * Thread Safety: Implementations must be thread-safe for read operations.
 * Write operations (add/remove) are called from the main simulation thread only.
 */
public interface ILabelMatchingStrategy {

    /**
     * Finds the best matching label for a given search value.
     * <p>
     * The matching algorithm:
     * <ol>
     *   <li>Finds all candidates within the Hamming distance tolerance</li>
     *   <li>Groups candidates by Hamming distance (lowest group wins)</li>
     *   <li>Within same Hamming group, scores: {@code score = physicalDistance + (foreign ? foreignPenalty : 0)}</li>
     *   <li>Returns the candidate with the lowest score</li>
     *   <li>Uses owner ID as tie-breaker for determinism</li>
     * </ol>
     *
     * @param searchValue The label value to search for (from jump operand)
     * @param codeOwner The owner ID of the executing code
     * @param callerCoords The coordinates of the calling instruction (for distance calculation)
     * @param environment The environment (for coordinate conversion and toroidal distance)
     * @return The flat index of the best matching label, or -1 if no match found
     */
    int findTarget(int searchValue, int codeOwner, int[] callerCoords, Environment environment);

    /**
     * Adds a label entry to the index.
     * <p>
     * For pre-expanded strategies, this also adds entries for all Hamming neighbors.
     *
     * @param labelValue The label's value (20-bit hash)
     * @param entry The label entry containing position and ownership info
     */
    void addLabel(int labelValue, LabelEntry entry);

    /**
     * Removes a label entry from the index.
     * <p>
     * For pre-expanded strategies, this also removes entries from all Hamming neighbors.
     *
     * @param labelValue The label's value (20-bit hash)
     * @param flatIndex The flat index of the label to remove
     */
    void removeLabel(int labelValue, int flatIndex);

    /**
     * Updates the owner of a label entry.
     * <p>
     * Called when ownership is transferred (e.g., during FORK).
     *
     * @param labelValue The label's value (20-bit hash)
     * @param flatIndex The flat index of the label
     * @param newOwner The new owner ID
     */
    void updateOwner(int labelValue, int flatIndex, int newOwner);

    /**
     * Updates the marker of a label entry.
     * <p>
     * Called when the marker changes (e.g., set during copy, cleared after FORK).
     *
     * @param labelValue The label's value (20-bit hash)
     * @param flatIndex The flat index of the label
     * @param newMarker The new marker value
     */
    void updateMarker(int labelValue, int flatIndex, int newMarker);

    /**
     * Gets all labels that match the search value within tolerance.
     * <p>
     * This is primarily for debugging and testing.
     *
     * @param searchValue The label value to search for
     * @return Collection of matching label entries
     */
    Collection<LabelEntry> getCandidates(int searchValue);

    /**
     * Gets the Hamming distance tolerance for this strategy.
     *
     * @return The maximum Hamming distance for a match (typically 2)
     */
    int getTolerance();

    /**
     * Gets the foreign penalty for this strategy.
     *
     * @return The score penalty added for foreign labels (typically 20)
     */
    int getForeignPenalty();
}
