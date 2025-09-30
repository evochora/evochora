// src/main/java/org/evochora/world/Environment.java
package org.evochora.runtime.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.evochora.runtime.Config;
import org.evochora.runtime.isa.IEnvironmentReader;

/**
 * Represents the simulation environment, managing the grid of molecules and their owners.
 */
public class Environment implements IEnvironmentReader {
    /**
     * Represents a coordinate in the environment as a record for efficient HashSet usage.
     */
    private record Coordinate(int... coords) {
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Coordinate that = (Coordinate) obj;
            return Arrays.equals(this.coords, that.coords);
        }
        
        @Override
        public int hashCode() {
            return Arrays.hashCode(this.coords);
        }
    }
    private final int[] shape;
    private final boolean isToroidal;
    private final int[] grid;
    private final int[] ownerGrid;
    private final int[] strides;
    
    // Sparse cell tracking for performance optimization
    private final Set<Coordinate> occupiedCells;
    
    /**
     * Environment properties that can be shared with other components.
     * This provides coordinate calculations without exposing the full grid data.
     */
    public final EnvironmentProperties properties;

    /**
     * Creates a new environment with the specified shape and toroidal setting.
     * 
     * @param shape The dimensions of the world.
     * @param toroidal Whether the world wraps around at edges.
     */
    public Environment(int[] shape, boolean toroidal) {
        this(new EnvironmentProperties(shape, toroidal));
    }
    
    /**
     * Creates a new environment with the specified properties.
     * 
     * @param properties The environment properties.
     */
    public Environment(EnvironmentProperties properties) {
        this.properties = properties;
        this.shape = properties.getWorldShape();
        this.isToroidal = properties.isToroidal();
        int size = 1;
        for (int dim : shape) { size *= dim; }
        this.grid = new int[size];
        Arrays.fill(this.grid, 0);
        this.ownerGrid = new int[size];
        Arrays.fill(this.ownerGrid, 0);
        this.strides = new int[shape.length];
        int stride = 1;
        for (int i = shape.length - 1; i >= 0; i--) {
            this.strides[i] = stride;
            stride *= shape[i];
        }
        
        // Initialize sparse cell tracking if enabled
        this.occupiedCells = Config.ENABLE_SPARSE_CELL_TRACKING ? new HashSet<>() : null;
    }

    /**
     * Normalizes a coordinate based on the environment's toroidal setting.
     * @param coord The coordinate to normalize.
     * @return The normalized coordinate.
     */
    public int[] getNormalizedCoordinate(int... coord) {
        if (coord.length != this.shape.length) {
            throw new IllegalArgumentException("Coordinate dimensions do not match world dimensions.");
        }
        int[] normalized = new int[coord.length];
        for (int i = 0; i < coord.length; i++) {
            int c = coord[i];
            if (isToroidal) {
                c = Math.floorMod(c, this.shape[i]);
            }
            normalized[i] = c;
        }
        return normalized;
    }

    private int getFlatIndex(int... coord) {
        int[] normalizedCoord = getNormalizedCoordinate(coord);
        if (!isToroidal) {
            for(int i = 0; i < shape.length; i++) {
                if (normalizedCoord[i] < 0 || normalizedCoord[i] >= shape[i]) {
                    return -1;
                }
            }
        }
        int flatIndex = 0;
        for (int i = 0; i < shape.length; i++) {
            flatIndex += normalizedCoord[i] * this.strides[i];
        }
        return flatIndex;
    }

    /**
     * Gets the molecule at the specified coordinate.
     * @param coord The coordinate to get the molecule from.
     * @return The molecule at the specified coordinate.
     */
    public Molecule getMolecule(int... coord) {
        int index = getFlatIndex(coord);
        if (index == -1) {
            return org.evochora.runtime.model.Molecule.fromInt(0);
        }
        return org.evochora.runtime.model.Molecule.fromInt(this.grid[index]);
    }

    /**
     * Sets the molecule at the specified coordinate.
     * @param molecule The molecule to set.
     * @param coord The coordinate to set the molecule at.
     */
    public void setMolecule(Molecule molecule, int... coord) {
        int index = getFlatIndex(coord);
        if (index != -1) {
            this.grid[index] = molecule.toInt();
            
            // Update sparse cell tracking if enabled
            if (Config.ENABLE_SPARSE_CELL_TRACKING && occupiedCells != null) {
                updateOccupiedCells(coord);
            }
        }
    }

    /**
     * Sets the molecule and its owner at the specified coordinate.
     * @param molecule The molecule to set.
     * @param ownerId The ID of the owner.
     * @param coord The coordinate to set the molecule at.
     */
    public void setMolecule(Molecule molecule, int ownerId, int... coord) {
        int index = getFlatIndex(coord);
        if (index != -1) {
            int packed = molecule.toInt();
            this.grid[index] = packed;
            this.ownerGrid[index] = ownerId;
            
            // Update sparse cell tracking if enabled
            if (Config.ENABLE_SPARSE_CELL_TRACKING && occupiedCells != null) {
                updateOccupiedCells(coord);
            }
        }
    }

    /**
     * Gets the owner ID of the cell at the specified coordinate.
     * @param coord The coordinate to get the owner ID from.
     * @return The owner ID.
     */
    public int getOwnerId(int... coord) {
        int index = getFlatIndex(coord);
        if (index == -1) {
            return 0;
        }
        return this.ownerGrid[index];
    }

    /**
     * Sets the owner ID of the cell at the specified coordinate.
     * @param ownerId The owner ID to set.
     * @param coord The coordinate to set the owner ID at.
     */
    public void setOwnerId(int ownerId, int... coord) {
        int index = getFlatIndex(coord);
        if (index != -1) {
            this.ownerGrid[index] = ownerId;
            
            // Update sparse cell tracking if enabled
            if (Config.ENABLE_SPARSE_CELL_TRACKING && occupiedCells != null) {
                updateOccupiedCells(coord);
            }
        }
    }

    /**
     * Clears the owner of the cell at the specified coordinate.
     * @param coord The coordinate to clear the owner of.
     */
    public void clearOwner(int... coord) {
        setOwnerId(0, coord);
    }

    /**
     * Gets the shape of the environment.
     * @return The shape of the environment.
     */
    public int[] getShape() {
        return Arrays.copyOf(this.shape, this.shape.length);
    }
    
    @Override
    public org.evochora.runtime.model.EnvironmentProperties getProperties() {
        return this.properties;
    }

    /**
     * Checks if a square/cubic area around a central coordinate is completely unowned.
     *
     * @param centerCoord The coordinate of the center of the area.
     * @param radius The radius of the check (e.g., radius 2 checks a 5x5 area in 2D).
     * @return {@code true} if no cell in the area has an owner (ownerId == 0), otherwise {@code false}.
     */
    public boolean isAreaUnowned(int[] centerCoord, int radius) {
        if (centerCoord.length != this.shape.length) {
            throw new IllegalArgumentException("Coordinate dimensions do not match world dimensions.");
        }
        
        // Optimized implementation: reuse arrays and direct array access
        int dims = this.shape.length;
        int[] offsets = new int[dims];
        int[] checkCoord = new int[dims]; // Reuse this array instead of creating new ones
        
        // Initialize offsets
        for (int i = 0; i < dims; i++) {
            offsets[i] = -radius;
        }

        while (true) {
            // Calculate check coordinate by reusing the array
            for (int i = 0; i < dims; i++) {
                checkCoord[i] = centerCoord[i] + offsets[i];
            }
            
            // Direct array access instead of getOwnerId() call
            int flatIndex = getFlatIndex(checkCoord);
            if (flatIndex != -1 && this.ownerGrid[flatIndex] != 0) {
                return false;
            }
            
            // Increment the offsets like a counter from -radius to +radius per dimension
            int dim = dims - 1;
            while (dim >= 0 && offsets[dim] == radius) {
                offsets[dim] = -radius;
                dim--;
            }
            if (dim < 0) break; // all combinations have been checked
            offsets[dim]++;
        }
        return true;
    }
    
    /**
     * Updates the occupied cells tracking based on the current state of the cell.
     * @param coord The coordinate to check and update.
     */
    private void updateOccupiedCells(int... coord) {
        int index = getFlatIndex(coord);
        if (index == -1) return;
        
        int value = this.grid[index];
        int owner = this.ownerGrid[index];
        
        Coordinate coordinate = new Coordinate(coord);
        
        if (value != 0 || owner != 0) {
            // Cell is occupied - add to tracking
            occupiedCells.add(coordinate);
        } else {
            // Cell is empty - remove from tracking
            occupiedCells.remove(coordinate);
        }
    }
    
}