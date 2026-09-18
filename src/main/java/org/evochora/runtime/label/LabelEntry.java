package org.evochora.runtime.label;

/**
 * Represents a label entry in the LabelIndex.
 * <p>
 * A label entry contains all information needed for fuzzy jump matching:
 * <ul>
 *   <li>{@code flatIndex}: The flat index of the cell holding the label, its persisted row-major
 *       index from {@code EnvironmentProperties} and a pure function of its coordinate; candidate
 *       lists are ordered by it, so that candidate order does not depend on the grid's memory
 *       layout</li>
 *   <li>{@code owner}: The owner ID of the cell containing the label</li>
 * </ul>
 * <p>
 * An entry exists only for a label whose marker is 0: a marked label belongs to a body that is
 * still under construction and is kept out of the index by {@link LabelIndex}.
 * <p>
 * The position can be reconstructed from the flat index with
 * {@code EnvironmentProperties.flatIndexToCoordinates()}.
 *
 * @param flatIndex The flat index of the cell holding the label
 * @param owner The owner ID of the cell
 */
public record LabelEntry(int flatIndex, int owner) {

    /**
     * Checks if this label is considered "foreign" relative to a given code owner.
     *
     * @param codeOwner The owner ID of the executing code
     * @return true if the label's cell is owned by anyone but {@code codeOwner}, unowned included
     */
    public boolean isForeign(int codeOwner) {
        return owner != codeOwner;
    }
}
