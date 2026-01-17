// src/main/java/org/evochora/world/Environment.java
package org.evochora.runtime.model;

import java.util.Arrays;
import java.util.BitSet;
import java.util.function.IntConsumer;

import org.evochora.runtime.Config;
import org.evochora.runtime.isa.IEnvironmentReader;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

/**
 * Represents the simulation environment, managing the grid of molecules and their owners.
 */
public class Environment implements IEnvironmentReader {
    private final int[] shape;
    private final boolean isToroidal;
    private final int[] grid;
    private final int[] ownerGrid;
    private final int[] strides;

    // Sparse cell tracking for performance optimization (using primitive int indices)
    private final IntSet occupiedIndices;
    
    // Ownership index: maps ownerId -> set of flat indices owned by that organism
    // Enables O(1) lookup of all cells owned by a specific organism (for FORK transfer, death cleanup)
    private final Int2ObjectOpenHashMap<IntOpenHashSet> cellsByOwner;
    
    // Delta compression: tracks which cells have changed since last reset
    // Used by SimulationEngine to create incremental/accumulated deltas
    // Memory: 1 bit per cell (e.g., 125KB for 1M cells)
    private final BitSet changedSinceLastReset;
    
    // Total number of cells (cached for performance)
    private final int totalCells;
    
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
        // Calculate size with overflow check (arrays are limited to Integer.MAX_VALUE)
        long sizeLong = 1L;
        for (int dim : shape) { sizeLong *= dim; }
        if (sizeLong > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "World too large: " + sizeLong + " cells exceeds Integer.MAX_VALUE (2.1 billion). " +
                "Reduce environment dimensions. Shape: " + java.util.Arrays.toString(shape));
        }
        int size = (int) sizeLong;
        this.totalCells = size;
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
        
        // Initialize sparse cell tracking if enabled (using primitive int indices for performance)
        this.occupiedIndices = Config.ENABLE_SPARSE_CELL_TRACKING ? new IntOpenHashSet() : null;
        
        // Initialize ownership index
        this.cellsByOwner = new Int2ObjectOpenHashMap<>();
        
        // Initialize change tracking for delta compression
        this.changedSinceLastReset = new BitSet(size);
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
            
            // Track change for delta compression
            changedSinceLastReset.set(index);
            
            // Update sparse cell tracking if enabled
            if (Config.ENABLE_SPARSE_CELL_TRACKING && occupiedIndices != null) {
                updateOccupiedIndices(index);
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
            
            // Track change for delta compression
            changedSinceLastReset.set(index);
            
            // Update ownership index
            int oldOwner = this.ownerGrid[index];
            if (oldOwner != ownerId) {
                updateOwnershipIndex(index, oldOwner, ownerId);
            }
            this.ownerGrid[index] = ownerId;
            
            // Update sparse cell tracking if enabled
            if (Config.ENABLE_SPARSE_CELL_TRACKING && occupiedIndices != null) {
                updateOccupiedIndices(index);
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
            // Track change for delta compression (owner change is also a change)
            changedSinceLastReset.set(index);
            
            // Update ownership index
            int oldOwner = this.ownerGrid[index];
            if (oldOwner != ownerId) {
                updateOwnershipIndex(index, oldOwner, ownerId);
            }
            this.ownerGrid[index] = ownerId;
            
            // Update sparse cell tracking if enabled
            if (Config.ENABLE_SPARSE_CELL_TRACKING && occupiedIndices != null) {
                updateOccupiedIndices(index);
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
     * Updates the occupied indices tracking based on the current state of the cell.
     * @param flatIndex The flat index to check and update.
     */
    private void updateOccupiedIndices(int flatIndex) {
        int value = this.grid[flatIndex];
        int owner = this.ownerGrid[flatIndex];

        if (value != 0 || owner != 0) {
            // Cell is occupied - add to tracking
            occupiedIndices.add(flatIndex);
        } else {
            // Cell is empty - remove from tracking
            occupiedIndices.remove(flatIndex);
        }
    }

    /**
     * Updates the ownership index when a cell's owner changes.
     * @param flatIndex The flat index of the cell.
     * @param oldOwner The previous owner ID.
     * @param newOwner The new owner ID.
     */
    private void updateOwnershipIndex(int flatIndex, int oldOwner, int newOwner) {
        // Remove from old owner's set
        if (oldOwner != 0) {
            IntOpenHashSet oldSet = cellsByOwner.get(oldOwner);
            if (oldSet != null) {
                oldSet.remove(flatIndex);
                if (oldSet.isEmpty()) {
                    cellsByOwner.remove(oldOwner);
                }
            }
        }
        // Add to new owner's set
        if (newOwner != 0) {
            cellsByOwner.computeIfAbsent(newOwner, k -> new IntOpenHashSet()).add(flatIndex);
        }
    }

    /**
     * Iterates all occupied cells using flat indices (OPTIMIZATION #2: Primitive API).
     * This method provides zero-overhead iteration with direct flat index access.
     * Enables JIT inlining when used with non-capturing method references.
     *
     * Performance: ~75% faster than coordinate-based iteration (eliminates both
     * index calculation overhead and callback boxing/unboxing).
     *
     * @param consumer Callback invoked with flat index for each occupied cell
     */
    public void forEachOccupiedIndex(IntConsumer consumer) {
        if (occupiedIndices == null) return;

        // Direct iteration over primitive int indices - zero allocation, maximum JIT optimization
        occupiedIndices.forEach(consumer);
    }

    /**
     * Helper method to convert flat index back to coordinates.
     * Useful for debugging or when coordinate representation is needed.
     *
     * @param flatIndex The flat index to convert
     * @return The coordinate array
     */
    public int[] getCoordinateFromIndex(int flatIndex) {
        int[] coord = new int[shape.length];
        int remaining = flatIndex;
        for (int i = 0; i < shape.length; i++) {
            coord[i] = remaining / strides[i];
            remaining %= strides[i];
        }
        return coord;
    }

    /**
     * Gets the packed molecule integer at the specified flat index.
     * OPTIMIZATION: Direct array access without coordinate conversion.
     *
     * @param flatIndex The flat index
     * @return The packed molecule integer
     */
    public int getMoleculeInt(int flatIndex) {
        return this.grid[flatIndex];
    }

    /**
     * Gets the owner ID at the specified flat index.
     * OPTIMIZATION: Direct array access without coordinate conversion.
     *
     * @param flatIndex The flat index
     * @return The owner ID
     */
    public int getOwnerIdByIndex(int flatIndex) {
        return this.ownerGrid[flatIndex];
    }

    /**
     * Transfers ownership of molecules from one organism to another based on marker matching.
     * <p>
     * This method iterates over all occupied cells and transfers ownership from {@code fromOwnerId}
     * to {@code toOwnerId} for molecules where the marker matches {@code markerToMatch}.
     * After transfer, the marker of each transferred molecule is reset to 0.
     * <p>
     * <strong>Performance:</strong> O(occupied cells) - iterates using sparse cell tracking.
     * For typical simulations with ~5% occupancy, this is much faster than full grid iteration.
     *
     * @param fromOwnerId   The current owner ID whose molecules should be transferred.
     * @param toOwnerId     The new owner ID to assign to matching molecules.
     * @param markerToMatch The marker value that molecules must have to be transferred.
     * @return The number of molecules transferred.
     */
    public int transferOwnership(int fromOwnerId, int toOwnerId, int markerToMatch) {
        IntOpenHashSet fromSet = cellsByOwner.get(fromOwnerId);
        if (fromSet == null || fromSet.isEmpty()) {
            return 0;
        }

        // Collect indices to transfer (can't modify during iteration)
        it.unimi.dsi.fastutil.ints.IntList toTransfer = new it.unimi.dsi.fastutil.ints.IntArrayList();

        fromSet.forEach((int flatIndex) -> {
            int moleculeInt = grid[flatIndex];
            int marker = (moleculeInt & Config.MARKER_MASK) >> Config.MARKER_SHIFT;
            if (marker == markerToMatch) {
                toTransfer.add(flatIndex);
            }
        });

        // Transfer ownership and reset marker
        IntOpenHashSet toSet = cellsByOwner.computeIfAbsent(toOwnerId, k -> new IntOpenHashSet());
        for (int i = 0; i < toTransfer.size(); i++) {
            int flatIndex = toTransfer.getInt(i);
            ownerGrid[flatIndex] = toOwnerId;
            // Reset marker to 0: clear marker bits and keep value/type
            grid[flatIndex] = grid[flatIndex] & ~Config.MARKER_MASK;
            // Track change for delta compression
            changedSinceLastReset.set(flatIndex);
            // Update ownership index
            fromSet.remove(flatIndex);
            toSet.add(flatIndex);
        }
        
        // Clean up empty set
        if (fromSet.isEmpty()) {
            cellsByOwner.remove(fromOwnerId);
        }

        return toTransfer.size();
    }

    /**
     * Clears ownership of all cells owned by the specified organism.
     * Sets owner to 0 and resets marker to 0 for all affected cells.
     * Called when an organism dies to release its molecules.
     * 
     * @param ownerId The ID of the organism whose ownership should be cleared.
     * @return The number of cells that were cleared.
     */
    public int clearOwnershipFor(int ownerId) {
        IntOpenHashSet owned = cellsByOwner.remove(ownerId);
        if (owned == null || owned.isEmpty()) {
            return 0;
        }

        int count = owned.size();
        owned.forEach((int flatIndex) -> {
            ownerGrid[flatIndex] = 0;
            // Reset marker to 0
            grid[flatIndex] = grid[flatIndex] & ~Config.MARKER_MASK;
            // Track change for delta compression
            changedSinceLastReset.set(flatIndex);
        });
        
        return count;
    }
    
    // ========================================================================
    // Delta Compression Support
    // ========================================================================
    
    /**
     * Gets the set of cell indices that have changed since the last reset.
     * <p>
     * Used by SimulationEngine to create incremental deltas (changes since last sample)
     * and accumulated deltas (all changes since last snapshot).
     * <p>
     * <strong>Thread Safety:</strong> Not thread-safe. In future multithreading, each
     * thread will have a thread-local BitSet merged in a 4th phase via {@code or()}.
     *
     * @return BitSet where set bits indicate changed cell indices
     */
    public BitSet getChangedIndices() {
        return changedSinceLastReset;
    }
    
    /**
     * Resets the change tracking, clearing all recorded changes.
     * <p>
     * Called by SimulationEngine after capturing a sample to start tracking
     * changes for the next interval.
     */
    public void resetChangeTracking() {
        changedSinceLastReset.clear();
    }
    
    /**
     * Gets the total number of cells in the environment.
     * <p>
     * This is the product of all dimension sizes.
     *
     * @return total cell count
     */
    public int getTotalCells() {
        return totalCells;
    }
}