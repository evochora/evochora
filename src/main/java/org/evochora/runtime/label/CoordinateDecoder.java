package org.evochora.runtime.label;

import org.evochora.runtime.model.EnvironmentProperties;

/**
 * Turns flat indexes of one world into coordinates and distances without dividing.
 * <p>
 * A coordinate is the flat index divided by the stride of its dimension. The division is replaced
 * by a multiplication with the rounded-up reciprocal of the stride and a shift (Granlund and
 * Montgomery, "Division by Invariant Integers using Multiplication"): with {@code l} the number of
 * bits of {@code stride - 1}, {@code floor(n / stride)} equals
 * {@code (n * ceil(2^(31+l) / stride)) >>> (31+l)} for every {@code n} below 2^31. The product stays
 * below 2^64, so an unsigned 64-bit multiplication holds it.
 * <p>
 * Thread Safety: immutable.
 */
final class CoordinateDecoder {

    private final boolean toroidal;
    private final int dimensions;
    private final int[] strides;
    private final int[] sizes;
    private final long[] reciprocals;
    private final int[] shifts;

    /**
     * Creates a decoder for a world.
     *
     * @param properties The properties of the world
     */
    CoordinateDecoder(EnvironmentProperties properties) {
        toroidal = properties.isToroidal();
        dimensions = properties.getDimensions();
        strides = new int[dimensions];
        sizes = new int[dimensions];
        reciprocals = new long[dimensions];
        shifts = new int[dimensions];
        for (int i = 0; i < dimensions; i++) {
            int stride = properties.getStride(i);
            strides[i] = stride;
            sizes[i] = properties.getDimensionSize(i);
            if (stride > 1) {
                shifts[i] = 31 + Integer.SIZE - Integer.numberOfLeadingZeros(stride - 1);
                reciprocals[i] = ((1L << shifts[i]) + stride - 1) / stride;
            }
        }
    }

    /**
     * Tells whether distances wrap around the edges of the world.
     *
     * @return {@code true} in a toroidal world
     */
    boolean isToroidal() {
        return toroidal;
    }

    /**
     * Gets the number of dimensions of the world.
     *
     * @return The number of dimensions
     */
    int dimensions() {
        return dimensions;
    }

    /**
     * Gets the extent of the world along a dimension.
     *
     * @param dimension The dimension
     * @return The number of cells along it
     */
    int size(int dimension) {
        return sizes[dimension];
    }

    private int quotient(int dimension, int remaining) {
        return strides[dimension] == 1 ? remaining : (int) ((remaining * reciprocals[dimension]) >>> shifts[dimension]);
    }

    /**
     * Decodes the coordinate of a cell along one of the first two dimensions.
     *
     * @param dimension 0 or 1
     * @param flatIndex The flat index of the cell
     * @return The coordinate; 0 for dimension 1 of a world with one dimension
     */
    int coordinate(int dimension, int flatIndex) {
        int first = quotient(0, flatIndex);
        if (dimension == 0) {
            return first;
        }
        return dimensions < 2 ? 0 : quotient(1, flatIndex - first * strides[0]);
    }

    /**
     * Measures the Manhattan distance from a position to a cell. In a toroidal world the difference
     * along each dimension takes the shorter way around; in a bounded world it does not wrap.
     *
     * @param from The coordinates to measure from
     * @param flatIndex The flat index of the cell
     * @return The distance in cells
     */
    int distance(int[] from, int flatIndex) {
        int distance = 0;
        int remaining = flatIndex;
        for (int i = 0; i < dimensions; i++) {
            int coordinate = quotient(i, remaining);
            remaining -= coordinate * strides[i];
            int difference = Math.abs(from[i] - coordinate);
            distance += toroidal ? Math.min(difference, sizes[i] - difference) : difference;
        }
        return distance;
    }
}
