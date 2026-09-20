package org.evochora.runtime.label;

import java.util.Arrays;

/**
 * A list of labels ordered by flat index, each with one integer of payload, held in two parallel
 * primitive arrays.
 * <p>
 * The order by flat index makes every walk over the list depend on the labels' coordinates alone —
 * never on the order in which labels were added, which differs between a run and its resume, and
 * never on the grid's memory layout.
 * <p>
 * Thread Safety: not synchronized. Mutated only from the simulation thread outside the parallel
 * wave, read concurrently inside it.
 */
final class LabelList {

    private static final int INITIAL_CAPACITY = 4;

    private int[] flatIndexes = new int[INITIAL_CAPACITY];
    private int[] payloads = new int[INITIAL_CAPACITY];
    private int size;

    /**
     * Gets the number of labels in the list.
     *
     * @return The number of labels
     */
    int size() {
        return size;
    }

    /**
     * Gets the flat index of a label.
     *
     * @param position A position in the list, from 0 to {@link #size()} - 1
     * @return The flat index of the label at that position
     */
    int flatIndexAt(int position) {
        return flatIndexes[position];
    }

    /**
     * Gets the payload of a label.
     *
     * @param position A position in the list, from 0 to {@link #size()} - 1
     * @return The payload of the label at that position
     */
    int payloadAt(int position) {
        return payloads[position];
    }

    /**
     * Finds the first position whose flat index is not below a bound.
     *
     * @param flatIndex The bound
     * @return The position, {@link #size()} if every label lies below the bound
     */
    int lowerBound(int flatIndex) {
        int low = 0;
        int high = size;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (flatIndexes[mid] < flatIndex) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * Finds the label at a flat index.
     *
     * @param flatIndex The flat index of the cell
     * @return Its position, or -1 if the list holds no label there
     */
    int positionOf(int flatIndex) {
        int position = lowerBound(flatIndex);
        return position < size && flatIndexes[position] == flatIndex ? position : -1;
    }

    /**
     * Adds a label, or replaces the payload of the label already at that flat index: a cell holds
     * one label.
     *
     * @param flatIndex The flat index of the cell holding the label
     * @param payload The payload to keep with it
     */
    void put(int flatIndex, int payload) {
        int position = lowerBound(flatIndex);
        if (position < size && flatIndexes[position] == flatIndex) {
            payloads[position] = payload;
            return;
        }
        if (size == flatIndexes.length) {
            flatIndexes = Arrays.copyOf(flatIndexes, size * 2);
            payloads = Arrays.copyOf(payloads, size * 2);
        }
        System.arraycopy(flatIndexes, position, flatIndexes, position + 1, size - position);
        System.arraycopy(payloads, position, payloads, position + 1, size - position);
        flatIndexes[position] = flatIndex;
        payloads[position] = payload;
        size++;
    }

    /**
     * Removes the label at a flat index, if there is one.
     *
     * @param flatIndex The flat index of the cell
     * @return {@code true} if a label was removed
     */
    boolean remove(int flatIndex) {
        int position = positionOf(flatIndex);
        if (position < 0) {
            return false;
        }
        System.arraycopy(flatIndexes, position + 1, flatIndexes, position, size - position - 1);
        System.arraycopy(payloads, position + 1, payloads, position, size - position - 1);
        size--;
        return true;
    }
}
