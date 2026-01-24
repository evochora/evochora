package org.evochora.runtime.label;

/**
 * Represents a label entry in the LabelIndex.
 * <p>
 * A label entry contains all information needed for fuzzy jump matching:
 * <ul>
 *   <li>{@code flatIndex}: The flat grid index where the label is located</li>
 *   <li>{@code owner}: The owner ID of the cell containing the label</li>
 *   <li>{@code marker}: The marker value (non-zero indicates transfer-in-progress)</li>
 * </ul>
 * <p>
 * The position can be reconstructed from flatIndex using {@code Environment.getCoordinateFromIndex()}.
 *
 * @param flatIndex The flat index in the environment grid
 * @param owner The owner ID of the cell
 * @param marker The marker value (0 = normal, non-zero = transfer marker)
 */
public record LabelEntry(int flatIndex, int owner, int marker) {

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
