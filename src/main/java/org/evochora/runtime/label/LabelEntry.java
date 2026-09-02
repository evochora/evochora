package org.evochora.runtime.label;

/**
 * Represents a label entry in the LabelIndex.
 * <p>
 * A label entry contains all information needed for fuzzy jump matching:
 * <ul>
 *   <li>{@code canonicalIndex}: The canonical (persisted, row-major) index of the cell holding
 *       the label, a pure function of its coordinate; candidate lists are ordered by it, so that
 *       candidate order does not depend on the grid's memory layout</li>
 *   <li>{@code owner}: The owner ID of the cell containing the label</li>
 *   <li>{@code marker}: The marker value (non-zero indicates transfer-in-progress)</li>
 * </ul>
 * <p>
 * The position can be reconstructed from the canonical index with
 * {@code EnvironmentProperties.flatIndexToCoordinates()}.
 *
 * @param canonicalIndex The canonical index of the cell holding the label
 * @param owner The owner ID of the cell
 * @param marker The marker value (0 = normal, non-zero = transfer marker)
 */
public record LabelEntry(int canonicalIndex, int owner, int marker) {

    /**
     * Checks if this label is considered "foreign" relative to a given code owner.
     * <p>
     * A label is foreign if:
     * <ul>
     *   <li>The label owner differs from the code owner, OR</li>
     *   <li>The label has a transfer marker (marker != 0)</li>
     * </ul>
     *
     * @param codeOwner The owner ID of the executing code
     * @return true if the label is foreign, false if it's "own"
     */
    public boolean isForeign(int codeOwner) {
        return owner != codeOwner || marker != 0;
    }
}
