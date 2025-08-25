package org.evochora.runtime.model;

/**
 * Represents the properties of a simulation environment without the full grid data.
 * This class provides coordinate calculations and world properties that can be shared
 * between the Runtime and DebugIndexer without duplicating the coordinate logic.
 */
public class EnvironmentProperties {
    private final int[] worldShape;
    private final boolean isToroidal;
    
    /**
     * Creates new environment properties.
     * 
     * @param worldShape The dimensions of the world (e.g., [100, 100] for 2D)
     * @param isToroidal Whether the world wraps around at edges
     */
    public EnvironmentProperties(int[] worldShape, boolean isToroidal) {
        this.worldShape = worldShape.clone();
        this.isToroidal = isToroidal;
    }
    
    /**
     * Gets the world shape/dimensions.
     * 
     * @return A copy of the world shape array
     */
    public int[] getWorldShape() {
        return worldShape.clone();
    }
    
    /**
     * Checks if the world is toroidal (wraps around at edges).
     * 
     * @return true if toroidal, false otherwise
     */
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
}
