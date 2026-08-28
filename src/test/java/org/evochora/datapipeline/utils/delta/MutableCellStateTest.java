package org.evochora.datapipeline.utils.delta;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MutableCellState}.
 */
@Tag("unit")
class MutableCellStateTest {
    
    private static final int TOTAL_CELLS = 100;
    
    @Test
    void constructor_validSize_createsEmptyState() {
        MutableCellState state = new MutableCellState(TOTAL_CELLS);
        
        assertEquals(TOTAL_CELLS, state.getTotalCells());
        assertEquals(0, state.countOccupied());
        assertFalse(state.isOccupied(0));
    }
    
    @Test
    void constructor_invalidSize_throws() {
        assertThrows(IllegalArgumentException.class, () -> new MutableCellState(0));
        assertThrows(IllegalArgumentException.class, () -> new MutableCellState(-1));
    }
    
    @Test
    void applySnapshot_setsAllCells() {
        MutableCellState state = new MutableCellState(TOTAL_CELLS);
        CellDataColumns snapshot = createCells(
                new int[]{0, 5, 10},
                new int[]{100, 200, 300},
                new int[]{1, 2, 3});
        
        state.applySnapshot(snapshot);
        
        assertEquals(3, state.countOccupied());
        assertTrue(state.isOccupied(0));
        assertTrue(state.isOccupied(5));
        assertTrue(state.isOccupied(10));
        assertFalse(state.isOccupied(1));
        
        assertEquals(100, state.getMoleculeData(0));
        assertEquals(200, state.getMoleculeData(5));
        assertEquals(300, state.getMoleculeData(10));
        
        assertEquals(1, state.getOwnerId(0));
        assertEquals(2, state.getOwnerId(5));
        assertEquals(3, state.getOwnerId(10));
    }
    
    @Test
    void applySnapshot_clearsExistingState() {
        MutableCellState state = new MutableCellState(TOTAL_CELLS);
        
        // First snapshot
        state.applySnapshot(createCells(new int[]{0, 1}, new int[]{10, 20}, new int[]{0, 0}));
        assertEquals(2, state.countOccupied());
        
        // Second snapshot (different cells)
        state.applySnapshot(createCells(new int[]{5, 6}, new int[]{50, 60}, new int[]{0, 0}));
        
        assertEquals(2, state.countOccupied());
        assertFalse(state.isOccupied(0));  // cleared
        assertFalse(state.isOccupied(1));  // cleared
        assertTrue(state.isOccupied(5));   // new
        assertTrue(state.isOccupied(6));   // new
    }
    
    @Test
    void applyDelta_addsNewCells() {
        MutableCellState state = new MutableCellState(TOTAL_CELLS);
        state.applySnapshot(createCells(new int[]{0}, new int[]{10}, new int[]{0}));
        
        // Delta adds cell 1
        state.applyDelta(createCells(new int[]{1}, new int[]{20}, new int[]{1}));
        
        assertEquals(2, state.countOccupied());
        assertTrue(state.isOccupied(0));
        assertTrue(state.isOccupied(1));
        assertEquals(20, state.getMoleculeData(1));
        assertEquals(1, state.getOwnerId(1));
    }
    
    @Test
    void applyDelta_modifiesExistingCells() {
        MutableCellState state = new MutableCellState(TOTAL_CELLS);
        state.applySnapshot(createCells(new int[]{0}, new int[]{10}, new int[]{1}));
        
        // Delta modifies cell 0
        state.applyDelta(createCells(new int[]{0}, new int[]{99}, new int[]{2}));
        
        assertEquals(1, state.countOccupied());
        assertEquals(99, state.getMoleculeData(0));
        assertEquals(2, state.getOwnerId(0));
    }
    
    @Test
    void applyDelta_removesCells_whenMoleculeDataIsZero() {
        MutableCellState state = new MutableCellState(TOTAL_CELLS);
        state.applySnapshot(createCells(new int[]{0, 1}, new int[]{10, 20}, new int[]{0, 0}));

        // Delta removes cell 0 (moleculeData = 0)
        state.applyDelta(createCells(new int[]{0}, new int[]{0}, new int[]{0}));

        assertEquals(1, state.countOccupied());
        assertFalse(state.isOccupied(0));
        assertTrue(state.isOccupied(1));
    }

    @Test
    void applyDelta_preservesOwner_whenMoleculeDataIsZeroButOwnerIsNot() {
        // This tests that delta decompression correctly handles CODE:0 cells with owner!=0.
        // While current semantics ensure CODE:0 always has owner=0, this test verifies
        // backward compatibility and robustness in handling edge case data.
        MutableCellState state = new MutableCellState(TOTAL_CELLS);
        state.applySnapshot(createCells(new int[]{0}, new int[]{10}, new int[]{1}));

        // Delta sets CODE:0 (moleculeData=0) with owner=5
        state.applyDelta(createCells(new int[]{0}, new int[]{0}, new int[]{5}));

        // Cell IS "occupied" because it has an owner, even though moleculeData=0
        assertTrue(state.isOccupied(0));  // Occupied because owner!=0
        assertEquals(0, state.getMoleculeData(0));
        assertEquals(5, state.getOwnerId(0));

        // Verify it's included in toCellDataColumns export
        CellDataColumns exported = state.toCellDataColumns();
        assertTrue(containsCell(exported, 0, 0, 5));
    }
    
    @Test
    void applyDelta_ignoresOutOfBoundsIndices() {
        MutableCellState state = new MutableCellState(TOTAL_CELLS);
        state.applySnapshot(createCells(new int[]{0}, new int[]{10}, new int[]{0}));
        
        // Delta with out-of-bounds index (should be ignored, not throw)
        CellDataColumns delta = createCells(new int[]{999}, new int[]{99}, new int[]{0});
        
        assertDoesNotThrow(() -> state.applyDelta(delta));
        assertEquals(1, state.countOccupied());
    }
    
    @Test
    void toCellDataColumns_exportsSparseRepresentation() {
        MutableCellState state = new MutableCellState(TOTAL_CELLS);
        state.applySnapshot(createCells(
                new int[]{5, 10, 15},
                new int[]{50, 100, 150},
                new int[]{1, 2, 3}));
        
        CellDataColumns exported = state.toCellDataColumns();
        
        assertEquals(3, exported.getFlatIndicesCount());
        assertEquals(3, exported.getMoleculeDataCount());
        assertEquals(3, exported.getOwnerIdsCount());
        
        // Verify contents
        assertTrue(containsCell(exported, 5, 50, 1));
        assertTrue(containsCell(exported, 10, 100, 2));
        assertTrue(containsCell(exported, 15, 150, 3));
    }
    
    @Test
    void getMoleculeData_outOfBounds_throws() {
        MutableCellState state = new MutableCellState(TOTAL_CELLS);
        
        assertThrows(IndexOutOfBoundsException.class, () -> state.getMoleculeData(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> state.getMoleculeData(TOTAL_CELLS));
    }
    
    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    private CellDataColumns createCells(int[] flatIndices, int[] moleculeData, int[] ownerIds) {
        CellDataColumns.Builder builder = CellDataColumns.newBuilder();
        for (int i = 0; i < flatIndices.length; i++) {
            builder.addFlatIndices(flatIndices[i]);
            builder.addMoleculeData(moleculeData[i]);
            builder.addOwnerIds(ownerIds[i]);
        }
        return builder.build();
    }
    
    private boolean containsCell(CellDataColumns cells, int flatIndex, int moleculeData, int ownerId) {
        for (int i = 0; i < cells.getFlatIndicesCount(); i++) {
            if (cells.getFlatIndices(i) == flatIndex &&
                cells.getMoleculeData(i) == moleculeData &&
                cells.getOwnerIds(i) == ownerId) {
                return true;
            }
        }
        return false;
    }

    /**
     * The occupancy set must agree with the cell contents after any sequence of writes.
     * <p>
     * Verified against a scan of {@link MutableCellState#isOccupied(int)}, which reads the arrays
     * directly - had the export been asked instead, the check would only compare the set to itself.
     */
    @Test
    void occupancyStaysConsistentAcrossSnapshotsAndDeltas() {
        int totalCells = 5000;
        MutableCellState state = new MutableCellState(totalCells);
        Random random = new Random(4242);

        for (int round = 0; round < 20; round++) {
            if (round % 5 == 0) {
                state.applySnapshot(randomColumns(random, totalCells, 400, true));
            } else {
                state.applyDelta(randomColumns(random, totalCells, 200, false));
            }

            int expected = 0;
            for (int i = 0; i < totalCells; i++) {
                if (state.isOccupied(i)) {
                    expected++;
                }
            }
            assertEquals(expected, state.countOccupied(),
                    "occupied count after round " + round);

            CellDataColumns exported = state.toCellDataColumns();
            assertEquals(expected, exported.getFlatIndicesCount(),
                    "exported cell count after round " + round);
            int previous = -1;
            for (int i = 0; i < exported.getFlatIndicesCount(); i++) {
                int flatIndex = exported.getFlatIndices(i);
                assertTrue(flatIndex > previous, "export is ordered by index");
                previous = flatIndex;
                assertTrue(state.isOccupied(flatIndex), "exported cell " + flatIndex + " is occupied");
                assertEquals(state.getMoleculeData(flatIndex), exported.getMoleculeData(i));
                assertEquals(state.getOwnerId(flatIndex), exported.getOwnerIds(i));
            }
        }
    }

    @Test
    void resetClearsOccupancy() {
        MutableCellState state = new MutableCellState(100);
        state.applySnapshot(CellDataColumns.newBuilder()
                .addFlatIndices(7).addMoleculeData(5).addOwnerIds(1)
                .build());
        assertEquals(1, state.countOccupied());

        state.reset();

        assertEquals(0, state.countOccupied());
        assertEquals(0, state.toCellDataColumns().getFlatIndicesCount());
    }

    /**
     * Builds a column set of distinct indices. Deltas may empty a cell, so a share of the entries
     * carries zero molecule data and zero owner.
     */
    private CellDataColumns randomColumns(Random random, int totalCells, int count, boolean occupiedOnly) {
        CellDataColumns.Builder builder = CellDataColumns.newBuilder();
        java.util.SortedSet<Integer> indices = new java.util.TreeSet<>();
        while (indices.size() < count) {
            indices.add(random.nextInt(totalCells));
        }
        for (int flatIndex : indices) {
            boolean empty = !occupiedOnly && random.nextInt(3) == 0;
            builder.addFlatIndices(flatIndex);
            builder.addMoleculeData(empty ? 0 : 1 + random.nextInt(9));
            builder.addOwnerIds(empty ? 0 : random.nextInt(5));
        }
        return builder.build();
    }

    /**
     * The iteration and the export describe the same cells, so a consumer may use either. Both are
     * compared against {@link MutableCellState#isOccupied(int)}, which reads the arrays directly.
     */
    @Test
    void forEachOccupiedCellAgreesWithTheExport() {
        int totalCells = 5000;
        MutableCellState state = new MutableCellState(totalCells);
        Random random = new Random(99);
        state.applySnapshot(randomColumns(random, totalCells, 300, true));
        state.applyDelta(randomColumns(random, totalCells, 150, false));

        CellDataColumns exported = state.toCellDataColumns();
        java.util.List<int[]> visited = new java.util.ArrayList<>();
        state.forEachOccupiedCell((flatIndex, moleculeData, ownerId) ->
                visited.add(new int[]{flatIndex, moleculeData, ownerId}));

        assertEquals(exported.getFlatIndicesCount(), visited.size(), "same number of cells");
        int previous = -1;
        for (int i = 0; i < visited.size(); i++) {
            int[] cell = visited.get(i);
            assertTrue(cell[0] > previous, "iteration is ordered by index");
            previous = cell[0];
            assertTrue(state.isOccupied(cell[0]), "visited cell " + cell[0] + " is occupied");
            assertEquals(exported.getFlatIndices(i), cell[0]);
            assertEquals(exported.getMoleculeData(i), cell[1]);
            assertEquals(exported.getOwnerIds(i), cell[2]);
        }
    }

    @Test
    void forEachOccupiedCellVisitsNothingWhenEmpty() {
        MutableCellState state = new MutableCellState(100);
        int[] visits = {0};
        state.forEachOccupiedCell((flatIndex, moleculeData, ownerId) -> visits[0]++);
        assertEquals(0, visits[0]);
    }
}
