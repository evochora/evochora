package org.evochora.runtime.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents the properties of a simulation environment without the full grid data.
 * This class provides coordinate calculations and world properties that can be shared
 * between the Runtime and DebugIndexer without duplicating the coordinate logic.
 */
public class EnvironmentProperties {
    private final int[] worldShape;
    private final boolean isToroidal;
    private final int[] strides;
    private final int totalCells;
    
    /**
     * Creates new environment properties.
     * 
     * @param worldShape The dimensions of the world (e.g., [100, 100] for 2D)
     * @param isToroidal Whether the world wraps around at edges
     */
    @JsonCreator
    public EnvironmentProperties(@JsonProperty("worldShape") int[] worldShape, @JsonProperty("isToroidal") boolean isToroidal) {
        this.worldShape = worldShape.clone();
        this.isToroidal = isToroidal;
        this.strides = calculateStrides();
        
        // Calculate total cells (product of all dimensions)
        int cells = 1;
        for (int dim : worldShape) {
            cells *= dim;
        }
        this.totalCells = cells;
    }
    
    /**
     * Gets the world shape/dimensions.
     * 
     * @return A copy of the world shape array
     */
    @JsonProperty("worldShape")
    public int[] getWorldShape() {
        return worldShape.clone();
    }
    
    /**
     * Checks if the world is toroidal (wraps around at edges).
     * 
     * @return true if toroidal, false otherwise
     */
    @JsonProperty("isToroidal")
    public boolean isToroidal() {
        return isToroidal;
    }
    
    /**
     * Calculates the next position based on current position and direction vector.
     * This is the core coordinate calculation logic used by both Runtime and DebugIndexer.
     * 
     * @param currentPos The current coordinate position
     * @param directionVector The direction vector to apply
     * @return The next coordinate position
     */
    public int[] getNextPosition(int[] currentPos, int[] directionVector) {
        int[] nextPos = new int[currentPos.length];
        for (int i = 0; i < currentPos.length; i++) {
            nextPos[i] = currentPos[i] + directionVector[i];
        }
        
        if (isToroidal) {
            return normalizePosition(nextPos);
        }
        return nextPos;
    }
    
    /**
     * Normalizes a position to handle toroidal wrapping.
     * 
     * @param pos The position to normalize
     * @return The normalized position
     */
    private int[] normalizePosition(int[] pos) {
        int[] normalized = new int[pos.length];
        for (int i = 0; i < pos.length; i++) {
            normalized[i] = pos[i] % worldShape[i];
            if (normalized[i] < 0) {
                normalized[i] += worldShape[i];
            }
        }
        return normalized;
    }
    
    /**
     * Calculates a target coordinate by adding a vector to a starting position.
     * 
     * @param startPos The starting coordinate
     * @param vector The vector to add
     * @return The target coordinate
     */
    public int[] getTargetCoordinate(int[] startPos, int[] vector) {
        int[] targetPos = new int[startPos.length];
        for (int i = 0; i < startPos.length; i++) {
            targetPos[i] = startPos[i] + vector[i];
        }
        
        if (isToroidal) {
            return normalizePosition(targetPos);
        }
        return targetPos;
    }
    
    /**
     * Calculates strides for flat index conversion.
     * <p>
     * Row-major order: stride[i] = product of all dimensions to the right of i.
     * Example: [100, 200, 50] → strides = [10000, 50, 1]
     * <p>
     * Called once during construction for eager initialization.
     *
     * @return Strides array
     */
    private int[] calculateStrides() {
        int[] s = new int[worldShape.length];
        int stride = 1;
        for (int i = worldShape.length - 1; i >= 0; i--) {
            s[i] = stride;
            stride *= worldShape[i];
        }
        return s;
    }
    
    /**
     * Converts a flat index to coordinates.
     * <p>
     * This is the inverse operation of the linearization used by Environment:
     * flatIndex = coord[0]*strides[0] + coord[1]*strides[1] + ...
     * <p>
     * Uses row-major order with strides calculated as: stride[i] = product of dimensions[i+1..n]
     * <p>
     * <strong>Performance:</strong> Strides are eagerly initialized in constructor for O(1) conversion.
     * <p>
     * <strong>Thread Safety:</strong> This method is thread-safe because strides is final and immutable.
     *
     * @param flatIndex The flat index to convert (must be non-negative)
     * @return Coordinate array with same length as worldShape
     * @throws IllegalArgumentException if flatIndex is negative
     */
    public int[] flatIndexToCoordinates(int flatIndex) {
        if (flatIndex < 0) {
            throw new IllegalArgumentException("Flat index must be non-negative: " + flatIndex);
        }
        
        int[] coord = new int[worldShape.length];
        int remaining = flatIndex;
        
        for (int i = 0; i < worldShape.length; i++) {
            coord[i] = remaining / strides[i];
            remaining %= strides[i];
        }
        
        return coord;
    }
    
    /**
     * Converts a flat index to N-dimensional coordinates, reusing an existing array.
     * <p>
     * This method is optimized for hot paths where allocations must be minimized.
     * Instead of allocating a new array, it writes coordinates into the provided array.
     * <p>
     * <strong>Use Case:</strong> Filtering large datasets where the same coordinate array
     * can be reused for millions of conversions (e.g., HTTP API viewport filtering).
     * <p>
     * <strong>Thread Safety:</strong> This method is thread-safe for reading, but the
     * caller must ensure exclusive access to the {@code outCoord} array during the call.
     *
     * @param flatIndex The flat index to convert (must be non-negative)
     * @param outCoord Output array to write coordinates into (must have length == worldShape.length)
     * @throws IllegalArgumentException if flatIndex is negative or outCoord has wrong length
     */
    public void flatIndexToCoordinates(int flatIndex, int[] outCoord) {
        if (flatIndex < 0) {
            throw new IllegalArgumentException("Flat index must be non-negative: " + flatIndex);
        }
        if (outCoord.length != worldShape.length) {
            throw new IllegalArgumentException(
                "Output coordinate array length (" + outCoord.length + 
                ") must match worldShape dimensions (" + worldShape.length + ")");
        }
        
        int remaining = flatIndex;
        
        for (int i = 0; i < worldShape.length; i++) {
            outCoord[i] = remaining / strides[i];
            remaining %= strides[i];
        }
    }
    
    /**
     * Gets the number of dimensions.
     *
     * @return Number of dimensions
     */
    public int getDimensions() {
        return worldShape.length;
    }
    
    /**
     * Gets the total number of cells in the environment.
     * <p>
     * This is the product of all dimensions in the world shape.
     * For example, a 100x100 environment has 10,000 total cells.
     * <p>
     * <strong>Use Case:</strong> Pre-allocating buffers for serialization
     * (e.g., FlatBuffers builder initial size estimation).
     *
     * @return Total number of cells
     */
    public int getTotalCells() {
        return totalCells;
    }
}
