package org.evochora.datapipeline.utils.delta;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.DeltaType;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.delta.ChunkCorruptedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DeltaCodec.Decoder}.
 */
@Tag("unit")
class DeltaCodecDecompressionTest {
    
    private static final String RUN_ID = "test-run-id";
    private static final int TOTAL_CELLS = 100;  // 10x10 environment
    
    private DeltaCodec.Decoder decoder;
    
    @BeforeEach
    void setUp() {
        decoder = new DeltaCodec.Decoder(TOTAL_CELLS);
    }
    
    // ========================================================================
    // decompressChunk Tests
    // ========================================================================
    
    @Test
    void decompressChunk_singleTick_returnsSnapshot() throws ChunkCorruptedException {
        CellDataColumns cells = createCells(new int[]{0, 1, 2}, new int[]{10, 20, 30});
        TickData snapshot = createSnapshotWithCells(0, cells);
        TickDataChunk chunk = createChunkWithSnapshot(snapshot);
        
        List<TickData> result = decoder.decompressChunk(chunk);
        
        assertEquals(1, result.size());
        assertEquals(0, result.get(0).getTickNumber());
        assertEquals(3, result.get(0).getCellColumns().getFlatIndicesCount());
    }
    
    @Test
    void decompressChunk_appliesDeltasCorrectly() throws ChunkCorruptedException {
        // Snapshot: cells 0, 1, 2 have values 10, 20, 30
        CellDataColumns snapshotCells = createCells(
                new int[]{0, 1, 2}, 
                new int[]{10, 20, 30});
        
        // Delta: cell 1 changed to 25, cell 3 added with value 40, cell 2 removed (value 0)
        CellDataColumns deltaCells = createCells(
                new int[]{1, 2, 3}, 
                new int[]{25, 0, 40});
        
        TickData snapshot = createSnapshotWithCells(0, snapshotCells);
        TickDelta delta = createIncrementalDelta(100, deltaCells);
        
        TickDataChunk chunk = TickDataChunk.newBuilder()
                .setSimulationRunId(RUN_ID)
                .setFirstTick(0)
                .setLastTick(100)
                .setTickCount(2)
                .setSnapshot(snapshot)
                .addDeltas(delta)
                .build();
        
        List<TickData> result = decoder.decompressChunk(chunk);
        
        assertEquals(2, result.size());
        
        // First tick: original snapshot
        CellDataColumns tick0Cells = result.get(0).getCellColumns();
        assertEquals(3, tick0Cells.getFlatIndicesCount());
        
        // Second tick: after delta applied
        // Should have cells 0, 1, 3 (cell 2 was removed)
        CellDataColumns tick100Cells = result.get(1).getCellColumns();
        assertEquals(3, tick100Cells.getFlatIndicesCount());
        
        // Verify the values
        assertCellValue(tick100Cells, 0, 10);  // unchanged
        assertCellValue(tick100Cells, 1, 25);  // changed from 20 to 25
        assertCellValue(tick100Cells, 3, 40);  // added
        assertCellNotPresent(tick100Cells, 2); // removed
    }
    
    // ========================================================================
    // decompressTick Tests
    // ========================================================================
    
    @Test
    void decompressTick_snapshotTick_returnsSnapshot() throws ChunkCorruptedException {
        TickDataChunk chunk = createMultiTickChunk();
        
        TickData result = decoder.decompressTick(chunk, 0);
        
        assertEquals(0, result.getTickNumber());
    }
    
    @Test
    void decompressTick_deltaTick_returnsReconstructedState() throws ChunkCorruptedException {
        CellDataColumns snapshotCells = createCells(new int[]{0}, new int[]{10});
        CellDataColumns deltaCells = createCells(new int[]{1}, new int[]{20});
        
        TickData snapshot = createSnapshotWithCells(0, snapshotCells);
        TickDelta delta = createIncrementalDelta(100, deltaCells);
        
        TickDataChunk chunk = TickDataChunk.newBuilder()
                .setSimulationRunId(RUN_ID)
                .setFirstTick(0)
                .setLastTick(100)
                .setTickCount(2)
                .setSnapshot(snapshot)
                .addDeltas(delta)
                .build();
        
        TickData result = decoder.decompressTick(chunk, 100);
        
        assertEquals(100, result.getTickNumber());
        assertEquals(2, result.getCellColumns().getFlatIndicesCount());
    }
    
    @Test
    void decompressTick_tickOutOfRange_throws() {
        TickDataChunk chunk = createMultiTickChunk();
        
        ChunkCorruptedException ex = assertThrows(ChunkCorruptedException.class, () ->
                decoder.decompressTick(chunk, 9999));
        assertTrue(ex.getMessage().contains("not in chunk range"));
    }
    
    @Test
    void decompressTick_tickNotFound_throws() {
        TickDataChunk chunk = createMultiTickChunk();
        
        // Tick 50 is in range [0, 300] but not actually in the chunk
        ChunkCorruptedException ex = assertThrows(ChunkCorruptedException.class, () ->
                decoder.decompressTick(chunk, 50));
        assertTrue(ex.getMessage().contains("not found"));
    }
    
    // ========================================================================
    // Decoder Reuse Tests
    // ========================================================================
    
    @Test
    void decoder_canBeReusedForMultipleChunks() throws ChunkCorruptedException {
        // First chunk
        CellDataColumns cells1 = createCells(new int[]{0}, new int[]{10});
        TickData snapshot1 = createSnapshotWithCells(0, cells1);
        TickDataChunk chunk1 = createChunkWithSnapshot(snapshot1);
        
        List<TickData> result1 = decoder.decompressChunk(chunk1);
        assertEquals(1, result1.size());
        assertEquals(10, result1.get(0).getCellColumns().getMoleculeData(0));
        
        // Second chunk with different data
        CellDataColumns cells2 = createCells(new int[]{5}, new int[]{50});
        TickData snapshot2 = createSnapshotWithCells(100, cells2);
        TickDataChunk chunk2 = createChunkWithSnapshot(snapshot2);
        
        List<TickData> result2 = decoder.decompressChunk(chunk2);
        assertEquals(1, result2.size());
        assertEquals(50, result2.get(0).getCellColumns().getMoleculeData(0));
        
        // Verify first result is still correct (decoder doesn't corrupt old results)
        assertEquals(10, result1.get(0).getCellColumns().getMoleculeData(0));
    }
    
    @Test
    void decoder_canDecompressTickAfterChunk() throws ChunkCorruptedException {
        TickDataChunk chunk = createMultiTickChunk();
        
        // First decompress whole chunk
        decoder.decompressChunk(chunk);
        
        // Then decompress single tick (should work, decoder resets internally)
        TickData tick = decoder.decompressTick(chunk, 0);
        assertEquals(0, tick.getTickNumber());
    }
    
    // ========================================================================
    // Edge Cases
    // ========================================================================
    
    @Test
    void decompressChunk_emptyDelta_handledCorrectly() throws ChunkCorruptedException {
        CellDataColumns snapshotCells = createCells(new int[]{0}, new int[]{10});
        CellDataColumns emptyDelta = CellDataColumns.getDefaultInstance();
        
        TickData snapshot = createSnapshotWithCells(0, snapshotCells);
        TickDelta delta = createIncrementalDelta(100, emptyDelta);
        
        TickDataChunk chunk = TickDataChunk.newBuilder()
                .setSimulationRunId(RUN_ID)
                .setFirstTick(0)
                .setLastTick(100)
                .setTickCount(2)
                .setSnapshot(snapshot)
                .addDeltas(delta)
                .build();
        
        List<TickData> result = decoder.decompressChunk(chunk);
        
        assertEquals(2, result.size());
        // Both ticks should have same cell state (delta was empty)
        assertEquals(1, result.get(0).getCellColumns().getFlatIndicesCount());
        assertEquals(1, result.get(1).getCellColumns().getFlatIndicesCount());
    }
    
    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    private TickDataChunk createChunkWithSnapshot(TickData snapshot) {
        return TickDataChunk.newBuilder()
                .setSimulationRunId(RUN_ID)
                .setFirstTick(snapshot.getTickNumber())
                .setLastTick(snapshot.getTickNumber())
                .setTickCount(1)
                .setSnapshot(snapshot)
                .build();
    }
    
    private TickDataChunk createMultiTickChunk() {
        TickData snapshot = createSnapshot(0);
        TickDelta delta1 = createIncrementalDelta(100, CellDataColumns.getDefaultInstance());
        TickDelta delta2 = createIncrementalDelta(200, CellDataColumns.getDefaultInstance());
        TickDelta delta3 = createAccumulatedDelta(300, CellDataColumns.getDefaultInstance());
        
        return TickDataChunk.newBuilder()
                .setSimulationRunId(RUN_ID)
                .setFirstTick(0)
                .setLastTick(300)
                .setTickCount(4)
                .setSnapshot(snapshot)
                .addDeltas(delta1)
                .addDeltas(delta2)
                .addDeltas(delta3)
                .build();
    }
    
    private TickData createSnapshot(long tickNumber) {
        return createSnapshotWithCells(tickNumber, CellDataColumns.getDefaultInstance());
    }
    
    private TickData createSnapshotWithCells(long tickNumber, CellDataColumns cells) {
        return TickData.newBuilder()
                .setSimulationRunId(RUN_ID)
                .setTickNumber(tickNumber)
                .setCaptureTimeMs(tickNumber * 10)
                .setCellColumns(cells)
                .setTotalOrganismsCreated(0)
                .build();
    }
    
    private TickDelta createIncrementalDelta(long tickNumber, CellDataColumns changedCells) {
        return TickDelta.newBuilder()
                .setTickNumber(tickNumber)
                .setCaptureTimeMs(tickNumber * 10)
                .setDeltaType(DeltaType.INCREMENTAL)
                .setChangedCells(changedCells)
                .setTotalOrganismsCreated(0)
                .build();
    }
    
    private TickDelta createAccumulatedDelta(long tickNumber, CellDataColumns changedCells) {
        return TickDelta.newBuilder()
                .setTickNumber(tickNumber)
                .setCaptureTimeMs(tickNumber * 10)
                .setDeltaType(DeltaType.ACCUMULATED)
                .setChangedCells(changedCells)
                .setTotalOrganismsCreated(0)
                .build();
    }
    
    private CellDataColumns createCells(int[] flatIndices, int[] moleculeData) {
        CellDataColumns.Builder builder = CellDataColumns.newBuilder();
        for (int i = 0; i < flatIndices.length; i++) {
            builder.addFlatIndices(flatIndices[i]);
            builder.addMoleculeData(moleculeData[i]);
            builder.addOwnerIds(0);
        }
        return builder.build();
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
                fail("Cell at flatIndex " + flatIndex + " should not be present but has value " + 
                        cells.getMoleculeData(i));
            }
        }
    }
}
