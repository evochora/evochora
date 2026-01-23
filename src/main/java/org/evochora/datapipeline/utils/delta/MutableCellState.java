package org.evochora.datapipeline.utils.delta;

import org.evochora.datapipeline.api.contracts.CellDataColumns;

/**
 * Mutable container for incremental environment reconstruction from deltas.
 * <p>
 * This class maintains the current state of all cells and allows applying deltas
 * sequentially to reconstruct the environment at any tick within a chunk.
 * <p>
 * <strong>Implementation:</strong> Uses arrays (not Maps) for optimal performance
 * and memory efficiency at high occupancy. At 100M cells with 100% occupancy:
 * <ul>
 *   <li>Array: 800MB (8 bytes per cell)</li>
 *   <li>Map: 4GB (40 bytes per entry with HashMap overhead)</li>
 * </ul>
 * <p>
 * <strong>Multithreading Consideration:</strong> Array-based storage is preferred
 * for future multithreading support, where thread-local states can be efficiently
 * merged using BitSet-tracked changes.
 * <p>
 * <strong>Thread Safety:</strong> This class is NOT thread-safe. It is designed
 * for single-threaded sequential processing (e.g., video rendering, chunk decompression).
 * <p>
 * <strong>Usage:</strong>
 * <pre>{@code
 * // Initialize from snapshot
 * MutableCellState state = new MutableCellState(totalCells);
 * state.applySnapshot(snapshotCells);
 * 
 * // Apply deltas sequentially
 * for (TickDelta delta : deltas) {
 *     state.applyDelta(delta.getChangedCells());
 * }
 * 
 * // Read current state
 * int moleculeData = state.getMoleculeData(flatIndex);
 * }</pre>
 *
 * @see DeltaCodec
 */
public class MutableCellState {
    
    private final int totalCells;
    private final int[] moleculeData;
    private final int[] ownerIds;
    
    /**
     * Creates a new mutable cell state for the given environment size.
     * <p>
     * All cells are initialized to empty (moleculeData = 0, ownerId = 0).
     *
     * @param totalCells total number of cells in the environment (product of all dimensions)
     * @throws IllegalArgumentException if totalCells is not positive
     */
    public MutableCellState(int totalCells) {
        if (totalCells <= 0) {
            throw new IllegalArgumentException("totalCells must be positive, got: " + totalCells);
        }
        this.totalCells = totalCells;
        this.moleculeData = new int[totalCells];
        this.ownerIds = new int[totalCells];
    }
    
    /**
     * Applies a snapshot (complete state) to this mutable state.
     * <p>
     * This clears any existing state and sets all cells from the snapshot.
     *
     * @param snapshot the complete cell data from a TickData snapshot
     */
    public void applySnapshot(CellDataColumns snapshot) {
        // Clear existing state
        java.util.Arrays.fill(moleculeData, 0);
        java.util.Arrays.fill(ownerIds, 0);
        
        // Apply all cells from snapshot
        int count = snapshot.getFlatIndicesCount();
        for (int i = 0; i < count; i++) {
            int flatIndex = snapshot.getFlatIndices(i);
            if (flatIndex >= 0 && flatIndex < totalCells) {
                moleculeData[flatIndex] = snapshot.getMoleculeData(i);
                ownerIds[flatIndex] = snapshot.getOwnerIds(i);
            }
        }
    }
    
    /**
     * Applies a delta (changes only) to this mutable state.
     * <p>
     * For each cell in the delta:
     * <ul>
     *   <li>If moleculeData is 0: cell is cleared (emptied)</li>
     *   <li>If moleculeData is non-zero: cell is set/updated</li>
     * </ul>
     *
     * @param delta the changed cells from a TickDelta
     */
    public void applyDelta(CellDataColumns delta) {
        int count = delta.getFlatIndicesCount();
        for (int i = 0; i < count; i++) {
            int flatIndex = delta.getFlatIndices(i);
            if (flatIndex >= 0 && flatIndex < totalCells) {
                // Always read both moleculeData and ownerId from the delta.
                // CODE:0 (moleculeData=0) can have a valid owner (e.g., initial world objects).
                moleculeData[flatIndex] = delta.getMoleculeData(i);
                ownerIds[flatIndex] = delta.getOwnerIds(i);
            }
        }
    }
    
    /**
     * Gets the molecule data at the given flat index.
     *
     * @param flatIndex the flat index of the cell
     * @return the packed molecule data (0 if empty)
     * @throws IndexOutOfBoundsException if flatIndex is out of range
     */
    public int getMoleculeData(int flatIndex) {
        return moleculeData[flatIndex];
    }
    
    /**
     * Gets the owner ID at the given flat index.
     *
     * @param flatIndex the flat index of the cell
     * @return the owner ID (0 if unowned or empty)
     * @throws IndexOutOfBoundsException if flatIndex is out of range
     */
    public int getOwnerId(int flatIndex) {
        return ownerIds[flatIndex];
    }
    
    /**
     * Checks if a cell is occupied (has non-zero molecule data).
     *
     * @param flatIndex the flat index of the cell
     * @return true if the cell contains a molecule
     * @throws IndexOutOfBoundsException if flatIndex is out of range
     */
    public boolean isOccupied(int flatIndex) {
        // A cell is "occupied" if it has non-empty molecule data OR has an owner.
        // CODE:0 (moleculeData=0) can have a valid owner (e.g., initial world objects).
        return moleculeData[flatIndex] != 0 || ownerIds[flatIndex] != 0;
    }
    
    /**
     * Returns the total number of cells in this state.
     *
     * @return the total cell count
     */
    public int getTotalCells() {
        return totalCells;
    }
    
    /**
     * Resets all cells to empty state.
     * <p>
     * This allows reusing the same MutableCellState instance for multiple
     * decompression operations, avoiding GC pressure from repeated allocations.
     * <p>
     * After calling reset(), the state is equivalent to a freshly constructed instance.
     */
    public void reset() {
        java.util.Arrays.fill(moleculeData, 0);
        java.util.Arrays.fill(ownerIds, 0);
    }
    
    /**
     * Counts the number of occupied cells.
     * <p>
     * A cell is "occupied" if it has non-empty molecule data OR has an owner.
     * <p>
     * Note: This is O(totalCells) - use sparingly.
     *
     * @return the number of occupied cells
     */
    public int countOccupied() {
        int count = 0;
        for (int i = 0; i < totalCells; i++) {
            if (moleculeData[i] != 0 || ownerIds[i] != 0) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Exports the current state to a CellDataColumns builder.
     * <p>
     * Only occupied cells are included (sparse representation).
     *
     * @return a new CellDataColumns containing all occupied cells
     */
    public CellDataColumns toCellDataColumns() {
        CellDataColumns.Builder builder = CellDataColumns.newBuilder();
        for (int i = 0; i < totalCells; i++) {
            // A cell is "occupied" if it has non-empty molecule data OR has an owner.
            // CODE:0 (moleculeData=0) can have a valid owner (e.g., initial world objects).
            if (moleculeData[i] != 0 || ownerIds[i] != 0) {
                builder.addFlatIndices(i);
                builder.addMoleculeData(moleculeData[i]);
                builder.addOwnerIds(ownerIds[i]);
            }
        }
        return builder.build();
    }
}
