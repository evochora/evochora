package org.evochora.datapipeline.utils.delta;

import com.google.protobuf.ByteString;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.DeltaType;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.delta.ChunkCorruptedException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for {@link DeltaCodec} compression and decompression.
 * <p>
 * These are CRITICAL tests that verify compress → decompress produces identical results.
 */
@Tag("unit")
class DeltaCodecRoundTripTest {
    
    private static final String RUN_ID = "round-trip-test";
    private static final int TOTAL_CELLS = 1000;  // 10x10x10 environment
    
    // ========================================================================
    // Round-Trip: Snapshot Only
    // ========================================================================
    
    @Test
    void roundTrip_snapshotOnly_preservesAllData() throws ChunkCorruptedException {
        // Create snapshot with known state
        CellDataColumns originalCells = createCells(
                new int[]{0, 50, 100, 500, 999},
                new int[]{10, 20, 30, 40, 50},
                new int[]{1, 2, 3, 4, 5});
        
        List<OrganismState> organisms = List.of(
                OrganismState.newBuilder().setOrganismId(1).setEnergy(100).build(),
                OrganismState.newBuilder().setOrganismId(2).setEnergy(200).build()
        );
        
        TickData snapshot = TickData.newBuilder()
                .setSimulationRunId(RUN_ID)
                .setTickNumber(0)
                .setCaptureTimeMs(1000)
                .setCellColumns(originalCells)
                .addAllOrganisms(organisms)
                .setTotalOrganismsCreated(2)
                .setRngState(ByteString.copyFromUtf8("rng-state"))
                .build();
        
        // Compress
        TickDataChunk chunk = DeltaCodec.createChunk(RUN_ID, snapshot, List.of());
        
        // Decompress
        List<TickData> decompressed = DeltaCodec.decompressChunk(chunk, TOTAL_CELLS);
        
        // Verify
        assertEquals(1, decompressed.size());
        TickData result = decompressed.get(0);
        
        assertEquals(0, result.getTickNumber());
        assertEquals(1000, result.getCaptureTimeMs());
        assertEquals(2, result.getOrganismsCount());
        assertEquals(2, result.getTotalOrganismsCreated());
        
        assertCellColumnsEqual(originalCells, result.getCellColumns());
    }
    
    // ========================================================================
    // Round-Trip: With Deltas
    // ========================================================================
    
    @Test
    void roundTrip_withDeltas_preservesCellStates() throws ChunkCorruptedException {
        // Initial state: cells 0, 1, 2 with values 10, 20, 30
        CellDataColumns snapshotCells = createCells(
                new int[]{0, 1, 2},
                new int[]{10, 20, 30},
                new int[]{0, 0, 0});
        
        TickData snapshot = createSnapshotWithCells(0, snapshotCells);
        
        // Delta 1: Change cell 1 to 25, add cell 3 with 40
        CellDataColumns delta1Cells = createCells(
                new int[]{1, 3},
                new int[]{25, 40},
                new int[]{0, 0});
        DeltaCapture delta1 = DeltaCodec.captureDelta(
                100, 1000, DeltaType.INCREMENTAL,
                delta1Cells, List.of(), 0,
                ByteString.EMPTY, List.of());
        
        // Delta 2: Remove cell 2 (moleculeData = 0), change cell 0 to 15
        CellDataColumns delta2Cells = createCells(
                new int[]{0, 2},
                new int[]{15, 0},  // 0 means cell cleared
                new int[]{0, 0});
        DeltaCapture delta2 = DeltaCodec.captureDelta(
                200, 2000, DeltaType.INCREMENTAL,
                delta2Cells, List.of(), 0,
                ByteString.EMPTY, List.of());
        
        // Compress
        TickDataChunk chunk = DeltaCodec.createChunk(
                RUN_ID, snapshot, List.of(delta1, delta2));
        
        // Decompress
        List<TickData> decompressed = DeltaCodec.decompressChunk(chunk, TOTAL_CELLS);
        
        // Verify tick 0 (snapshot)
        assertEquals(3, decompressed.size());
        CellDataColumns tick0Cells = decompressed.get(0).getCellColumns();
        assertCellValue(tick0Cells, 0, 10);
        assertCellValue(tick0Cells, 1, 20);
        assertCellValue(tick0Cells, 2, 30);
        
        // Verify tick 100 (after delta 1)
        // Expected: cell 0=10, cell 1=25 (changed), cell 2=30, cell 3=40 (added)
        CellDataColumns tick100Cells = decompressed.get(1).getCellColumns();
        assertEquals(4, tick100Cells.getFlatIndicesCount());
        assertCellValue(tick100Cells, 0, 10);
        assertCellValue(tick100Cells, 1, 25);
        assertCellValue(tick100Cells, 2, 30);
        assertCellValue(tick100Cells, 3, 40);
        
        // Verify tick 200 (after delta 2)
        // Expected: cell 0=15, cell 1=25, cell 3=40 (cell 2 removed)
        CellDataColumns tick200Cells = decompressed.get(2).getCellColumns();
        assertEquals(3, tick200Cells.getFlatIndicesCount());
        assertCellValue(tick200Cells, 0, 15);
        assertCellValue(tick200Cells, 1, 25);
        assertCellNotPresent(tick200Cells, 2);
        assertCellValue(tick200Cells, 3, 40);
    }
    
    // ========================================================================
    // Round-Trip: Single Tick Decompression
    // ========================================================================
    
    @Test
    void roundTrip_decompressSingleTick_matchesFullDecompression() throws ChunkCorruptedException {
        // Create chunk with multiple ticks
        // Snapshot: cells 0, 1 with values 100, 200
        CellDataColumns snapshotCells = createCells(
                new int[]{0, 1},
                new int[]{100, 200},
                new int[]{0, 0});
        TickData snapshot = createSnapshotWithCells(0, snapshotCells);
        
        List<DeltaCapture> deltas = new ArrayList<>();
        
        // INCREMENTAL deltas: each adds one new cell
        // Delta 100: add cell 2
        deltas.add(DeltaCodec.captureDelta(100, 1000, DeltaType.INCREMENTAL,
                createCells(new int[]{2}, new int[]{102}, new int[]{0}),
                List.of(), 0, ByteString.EMPTY, List.of()));
        
        // Delta 200: add cell 3
        deltas.add(DeltaCodec.captureDelta(200, 2000, DeltaType.INCREMENTAL,
                createCells(new int[]{3}, new int[]{203}, new int[]{0}),
                List.of(), 0, ByteString.EMPTY, List.of()));
        
        // ACCUMULATED delta at 300: contains ALL changes since snapshot (cells 2, 3, 4)
        // Note: Accumulated delta must include all cells changed since snapshot!
        deltas.add(DeltaCodec.captureDelta(300, 3000, DeltaType.ACCUMULATED,
                createCells(new int[]{2, 3, 4}, new int[]{102, 203, 304}, new int[]{0, 0, 0}),
                List.of(), 0, ByteString.copyFromUtf8("rng"), List.of()));
        
        // Delta 400: add cell 5 (incremental since accumulated)
        deltas.add(DeltaCodec.captureDelta(400, 4000, DeltaType.INCREMENTAL,
                createCells(new int[]{5}, new int[]{405}, new int[]{0}),
                List.of(), 0, ByteString.EMPTY, List.of()));
        
        TickDataChunk chunk = DeltaCodec.createChunk(RUN_ID, snapshot, deltas);
        
        // Full decompression
        List<TickData> fullDecompressed = DeltaCodec.decompressChunk(chunk, TOTAL_CELLS);
        
        // Single tick decompression for each tick
        for (int i = 0; i < fullDecompressed.size(); i++) {
            TickData expected = fullDecompressed.get(i);
            TickData single = DeltaCodec.decompressTick(chunk, expected.getTickNumber(), TOTAL_CELLS);
            
            assertEquals(expected.getTickNumber(), single.getTickNumber());
            assertCellColumnsEqual(expected.getCellColumns(), single.getCellColumns());
        }
    }
    
    // ========================================================================
    // Round-Trip: Accumulated Delta Optimization
    // ========================================================================
    
    @Test
    void roundTrip_accumulatedDelta_allowsCorrectReconstruction() throws ChunkCorruptedException {
        // Snapshot: cell 0 = 10
        CellDataColumns snapshotCells = createCells(new int[]{0}, new int[]{10}, new int[]{0});
        TickData snapshot = createSnapshotWithCells(0, snapshotCells);
        
        // Delta 1: Add cell 1
        CellDataColumns delta1Cells = createCells(new int[]{1}, new int[]{20}, new int[]{0});
        DeltaCapture delta1 = DeltaCodec.captureDelta(
                100, 1000, DeltaType.INCREMENTAL,
                delta1Cells, List.of(), 0,
                ByteString.EMPTY, List.of());
        
        // Delta 2: Accumulated (cells 1, 2 - all changes since snapshot)
        // Note: Accumulated delta contains ALL changes since snapshot
        CellDataColumns delta2Cells = createCells(new int[]{1, 2}, new int[]{20, 30}, new int[]{0, 0});
        DeltaCapture delta2 = DeltaCodec.captureDelta(
                200, 2000, DeltaType.ACCUMULATED,
                delta2Cells, List.of(), 0,
                ByteString.copyFromUtf8("rng"), List.of());
        
        // Delta 3: Add cell 3
        CellDataColumns delta3Cells = createCells(new int[]{3}, new int[]{40}, new int[]{0});
        DeltaCapture delta3 = DeltaCodec.captureDelta(
                300, 3000, DeltaType.INCREMENTAL,
                delta3Cells, List.of(), 0,
                ByteString.EMPTY, List.of());
        
        TickDataChunk chunk = DeltaCodec.createChunk(
                RUN_ID, snapshot, List.of(delta1, delta2, delta3));
        
        // Decompress tick 300 - should use accumulated delta at 200 as starting point
        TickData tick300 = DeltaCodec.decompressTick(chunk, 300, TOTAL_CELLS);
        
        // Expected state at tick 300:
        // From snapshot: cell 0 = 10
        // From accumulated delta 200: cells 1=20, 2=30
        // From incremental delta 300: cell 3=40
        CellDataColumns cells = tick300.getCellColumns();
        assertEquals(4, cells.getFlatIndicesCount());
        assertCellValue(cells, 0, 10);
        assertCellValue(cells, 1, 20);
        assertCellValue(cells, 2, 30);
        assertCellValue(cells, 3, 40);
    }
    
    // ========================================================================
    // Round-Trip: Organism State
    // ========================================================================
    
    @Test
    void roundTrip_preservesOrganismStates() throws ChunkCorruptedException {
        TickData snapshot = TickData.newBuilder()
                .setSimulationRunId(RUN_ID)
                .setTickNumber(0)
                .setCaptureTimeMs(0)
                .setCellColumns(CellDataColumns.getDefaultInstance())
                .addOrganisms(OrganismState.newBuilder().setOrganismId(1).setEnergy(100).build())
                .setTotalOrganismsCreated(1)
                .build();
        
        // Delta with new organism
        DeltaCapture delta = DeltaCodec.captureDelta(
                100, 1000, DeltaType.INCREMENTAL,
                CellDataColumns.getDefaultInstance(),
                List.of(
                        OrganismState.newBuilder().setOrganismId(1).setEnergy(90).build(),
                        OrganismState.newBuilder().setOrganismId(2).setEnergy(50).build()
                ),
                2,
                ByteString.EMPTY, List.of());
        
        TickDataChunk chunk = DeltaCodec.createChunk(RUN_ID, snapshot, List.of(delta));
        List<TickData> decompressed = DeltaCodec.decompressChunk(chunk, TOTAL_CELLS);
        
        // Tick 0: 1 organism
        assertEquals(1, decompressed.get(0).getOrganismsCount());
        assertEquals(1, decompressed.get(0).getTotalOrganismsCreated());
        
        // Tick 100: 2 organisms
        assertEquals(2, decompressed.get(1).getOrganismsCount());
        assertEquals(2, decompressed.get(1).getTotalOrganismsCreated());
        assertEquals(90, decompressed.get(1).getOrganisms(0).getEnergy());
        assertEquals(50, decompressed.get(1).getOrganisms(1).getEnergy());
    }
    
    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    private TickData createSnapshotWithCells(long tickNumber, CellDataColumns cells) {
        return TickData.newBuilder()
                .setSimulationRunId(RUN_ID)
                .setTickNumber(tickNumber)
                .setCaptureTimeMs(tickNumber * 10)
                .setCellColumns(cells)
                .setTotalOrganismsCreated(0)
                .build();
    }
    
    private CellDataColumns createCells(int[] flatIndices, int[] moleculeData, int[] ownerIds) {
        CellDataColumns.Builder builder = CellDataColumns.newBuilder();
        for (int i = 0; i < flatIndices.length; i++) {
            builder.addFlatIndices(flatIndices[i]);
            builder.addMoleculeData(moleculeData[i]);
            builder.addOwnerIds(ownerIds[i]);
        }
        return builder.build();
    }
    
    private void assertCellColumnsEqual(CellDataColumns expected, CellDataColumns actual) {
        assertEquals(expected.getFlatIndicesCount(), actual.getFlatIndicesCount(),
                "Cell count mismatch");
        
        // Build maps for comparison (order may differ)
        for (int i = 0; i < expected.getFlatIndicesCount(); i++) {
            int flatIndex = expected.getFlatIndices(i);
            int expectedMolecule = expected.getMoleculeData(i);
            int expectedOwner = expected.getOwnerIds(i);
            
            boolean found = false;
            for (int j = 0; j < actual.getFlatIndicesCount(); j++) {
                if (actual.getFlatIndices(j) == flatIndex) {
                    assertEquals(expectedMolecule, actual.getMoleculeData(j),
                            "Molecule data mismatch at flatIndex " + flatIndex);
                    assertEquals(expectedOwner, actual.getOwnerIds(j),
                            "Owner ID mismatch at flatIndex " + flatIndex);
                    found = true;
                    break;
                }
            }
            assertTrue(found, "Cell at flatIndex " + flatIndex + " not found in actual");
        }
    }
    
    private void assertCellValue(CellDataColumns cells, int flatIndex, int expectedValue) {
        for (int i = 0; i < cells.getFlatIndicesCount(); i++) {
            if (cells.getFlatIndices(i) == flatIndex) {
                assertEquals(expectedValue, cells.getMoleculeData(i),
                        "Cell at flatIndex " + flatIndex + " has wrong value");
                return;
            }
        }
        fail("Cell at flatIndex " + flatIndex + " not found");
    }
    
    private void assertCellNotPresent(CellDataColumns cells, int flatIndex) {
        for (int i = 0; i < cells.getFlatIndicesCount(); i++) {
            if (cells.getFlatIndices(i) == flatIndex) {
                fail("Cell at flatIndex " + flatIndex + " should not be present");
            }
        }
    }
}
