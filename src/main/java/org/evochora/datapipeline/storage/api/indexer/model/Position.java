package org.evochora.datapipeline.storage.api.indexer.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents a flexible, immutable, and truly dimension-agnostic position.
 * <p>
 * This class stores coordinates in an integer array and provides safe,
 * generic access to its dimensions and coordinate values by index, without
 * making any assumptions about specific dimensions like X, Y, or Z.
 * </p>
 */
public record Position(int[] coordinates) {

    /**
     * Canonical constructor ensuring the internal state is valid.
     * This also creates a defensive copy of the array to ensure immutability.
     *
     * @param coordinates The n-dimensional coordinates. Must not be null.
     * @throws NullPointerException if coordinates is null.
     */
    public Position(int[] coordinates) {
        Objects.requireNonNull(coordinates, "Coordinates array cannot be null");
        // Defensive copy to ensure the array is immutable from the outside
        this.coordinates = coordinates.clone();
    }

    /**
     * Returns the number of dimensions for this position.
     *
     * @return the length of the coordinates array.
     */
    public int getDimensions() {
        return coordinates.length;
    }

    /**
     * Safely retrieves the coordinate value for a specific dimension index.
     *
     * @param dimension The index of the dimension to retrieve (0-based).
     * @return The coordinate value at the given dimension.
     * @throws IndexOutOfBoundsException if the dimension index is out of bounds.
     */
    public int getCoordinate(int dimension) {
        // Bounds checking is implicitly handled by the array access,
        // which will throw ArrayIndexOutOfBoundsException. This is sufficient.
        return coordinates[dimension];
    }

    /**
     * Returns a defensive copy of the coordinates array to maintain immutability.
     *
     * @return A copy of the coordinates array.
     */
    @Override
    public int[] coordinates() {
        // Return a copy to prevent external modification of the internal array
        return coordinates.clone();
    }

    @Override
    public String toString() {
        return Arrays.toString(coordinates);
    }

    /**
     * Provides a correct, content-based equality check for the coordinates array.
     * The default record implementation would perform a reference equality check.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Position position = (Position) o;
        return Arrays.equals(coordinates, position.coordinates);
    }

    /**
     * Provides a correct, content-based hash code for the coordinates array.
     * The default record implementation would use the array's reference hash code.
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(coordinates);
    }
}
