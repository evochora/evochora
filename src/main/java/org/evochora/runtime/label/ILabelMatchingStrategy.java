package org.evochora.runtime.label;

import org.evochora.runtime.model.Environment;
import org.evochora.runtime.spi.IRandomProvider;

import java.util.Collection;

/**
 * Strategy interface for label matching algorithms.
 * <p>
 * This interface enables different label matching strategies to be used interchangeably,
 * following the Strategy pattern. The default implementation uses query-expansion with
 * staged Hamming distance search and pruning.
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
     *   <li><b>Early exit:</b> If an own label with Hamming=0 exists, return the closest one
     *       (own exact match always wins)</li>
     *   <li>Otherwise, compute combined score for each candidate:
     *       {@code score = (hamming × hammingWeight) + distance + (foreign ? foreignPenalty : 0)}</li>
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
     *
     * @param labelValue The label's value (20-bit hash)
     * @param entry The label entry containing position and ownership info
     */
    void addLabel(int labelValue, LabelEntry entry);

    /**
     * Removes a label entry from the index.
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
     * @return The score penalty added for foreign labels (typically 100)
     */
    int getForeignPenalty();

    /**
     * Gets the Hamming weight for this strategy.
     * <p>
     * The Hamming weight determines how much each bit of Hamming distance
     * contributes to the score. Higher values make Hamming distance more
     * important relative to physical distance and ownership.
     *
     * @return The score weight per Hamming distance (typically 50)
     */
    int getHammingWeight();

    /**
     * Sets the random provider for stochastic label selection.
     * <p>
     * When a strategy supports stochastic selection (e.g., {@code selectionSpread > 0}),
     * this provider supplies the randomness. The provider should be a derived
     * sub-stream obtained via {@code IRandomProvider.deriveFor("labelMatching", 0)}.
     * <p>
     * The default implementation is a no-op, so strategies that do not support
     * stochastic selection need not override this method.
     *
     * @param randomProvider The random provider for label selection
     */
    default void setRandomProvider(IRandomProvider randomProvider) {
        // No-op by default; strategies that support stochastic selection override this.
    }
}
