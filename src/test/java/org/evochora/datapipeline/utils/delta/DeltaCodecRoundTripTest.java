package org.evochora.datapipeline.utils.delta;

import com.google.protobuf.ByteString;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.PluginState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.delta.ChunkCorruptedException;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for {@link DeltaCodec} compression (Encoder) and decompression (Decoder).
 * <p>
 * These are CRITICAL tests that verify encode → decode produces identical results.
 */
@Tag("unit")
class DeltaCodecRoundTripTest {
    
    private static final String RUN_ID = "round-trip-test";
    private static final int TOTAL_CELLS = 100;  // 10x10 environment
    
    private Environment env;
    private DeltaCodec.Decoder decoder;
    
    @BeforeEach
    void setUp() {
        env = new Environment(new int[]{10, 10}, false);
        decoder = new DeltaCodec.Decoder(TOTAL_CELLS);
    }
    
    // ========================================================================
    // Round-Trip: Encoder → Decoder
    // ========================================================================
    
    @Test
    void roundTrip_singleTickChunk_preservesCellState() throws ChunkCorruptedException {
        // Setup environment
        env.setMolecule(Molecule.fromInt(100), new int[]{0, 0});
        env.setMolecule(Molecule.fromInt(200), new int[]{5, 5});
        env.setMolecule(Molecule.fromInt(300), new int[]{9, 9});
        
        // Encode
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, TOTAL_CELLS, 1, 1, 1);
        Optional<TickDataChunk> chunk = captureTick(encoder, 0);
        assertTrue(chunk.isPresent());
        
        // Decode
        List<TickData> decoded = decoder.decompressChunk(chunk.get());
        assertEquals(1, decoded.size());
        
        // Verify cells
        CellDataColumns cells = decoded.get(0).getCellColumns();
        assertEquals(3, cells.getFlatIndicesCount());
        assertCellValue(cells, 0, 100);   // flatIndex 0 = (0,0)
        assertCellValue(cells, 55, 200);  // flatIndex 55 = (5,5) in 10x10
        assertCellValue(cells, 99, 300);  // flatIndex 99 = (9,9) in 10x10
    }
    
    @Test
    void roundTrip_multiTickChunk_preservesAllTicks() throws ChunkCorruptedException {
        // Create encoder with small chunk size: 4 ticks per chunk
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, TOTAL_CELLS, 2, 2, 1);
        
        // Tick 0: snapshot with cell 0
        env.setMolecule(Molecule.fromInt(10), new int[]{0, 0});
        captureTick(encoder, 0);
        
        // Tick 1: incremental - add cell 1
        env.setMolecule(Molecule.fromInt(11), new int[]{1, 0});
        captureTick(encoder, 1);
        
        // Tick 2: accumulated - change cell 0, add cell 2
        env.setMolecule(Molecule.fromInt(20), new int[]{0, 0});
        env.setMolecule(Molecule.fromInt(12), new int[]{2, 0});
        captureTick(encoder, 2);
        
        // Tick 3: incremental - add cell 3 (completes chunk)
        env.setMolecule(Molecule.fromInt(13), new int[]{3, 0});
        Optional<TickDataChunk> chunk = captureTick(encoder, 3);
        assertTrue(chunk.isPresent());
        
        // Decode
        List<TickData> decoded = decoder.decompressChunk(chunk.get());
        assertEquals(4, decoded.size());
        
        // Verify tick 0: only cell 0 = 10
        assertEquals(0, decoded.get(0).getTickNumber());
        assertEquals(1, decoded.get(0).getCellColumns().getFlatIndicesCount());
        assertCellValue(decoded.get(0).getCellColumns(), 0, 10);
        
        // Verify tick 1: cells 0=10, 1=11
        assertEquals(1, decoded.get(1).getTickNumber());
        assertEquals(2, decoded.get(1).getCellColumns().getFlatIndicesCount());
        
        // Verify tick 2: cells 0=20, 1=11, 2=12
        assertEquals(2, decoded.get(2).getTickNumber());
        assertEquals(3, decoded.get(2).getCellColumns().getFlatIndicesCount());
        assertCellValue(decoded.get(2).getCellColumns(), 0, 20);
        
        // Verify tick 3: cells 0=20, 1=11, 2=12, 3=13
        assertEquals(3, decoded.get(3).getTickNumber());
        assertEquals(4, decoded.get(3).getCellColumns().getFlatIndicesCount());
    }
    
    @Test
    void roundTrip_decompressSingleTick_matchesFullDecompression() throws ChunkCorruptedException {
        // Create chunk with 4 ticks
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, TOTAL_CELLS, 2, 2, 1);
        
        env.setMolecule(Molecule.fromInt(10), new int[]{0, 0});
        captureTick(encoder, 0);
        
        env.setMolecule(Molecule.fromInt(11), new int[]{1, 0});
        captureTick(encoder, 1);
        
        env.setMolecule(Molecule.fromInt(12), new int[]{2, 0});
        captureTick(encoder, 2);
        
        env.setMolecule(Molecule.fromInt(13), new int[]{3, 0});
        Optional<TickDataChunk> chunk = captureTick(encoder, 3);
        assertTrue(chunk.isPresent());
        
        // Full decompression
        List<TickData> fullDecompressed = decoder.decompressChunk(chunk.get());
        
        // Single tick decompression for each tick
        for (TickData expected : fullDecompressed) {
            TickData single = decoder.decompressTick(chunk.get(), expected.getTickNumber());
            
            assertEquals(expected.getTickNumber(), single.getTickNumber());
            assertCellColumnsEqual(expected.getCellColumns(), single.getCellColumns());
        }
    }
    
    @Test
    void roundTrip_cellRemoval_handledCorrectly() throws ChunkCorruptedException {
        // Create chunk with cell removal
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, TOTAL_CELLS, 1, 2, 1);
        
        // Tick 0: cells 0, 1, 2
        env.setMolecule(Molecule.fromInt(10), new int[]{0, 0});
        env.setMolecule(Molecule.fromInt(20), new int[]{1, 0});
        env.setMolecule(Molecule.fromInt(30), new int[]{2, 0});
        captureTick(encoder, 0);
        
        // Tick 1: remove cell 1 (moleculeData = 0 means cell cleared)
        env.setMolecule(Molecule.fromInt(0), new int[]{1, 0});
        Optional<TickDataChunk> chunk = captureTick(encoder, 1);
        assertTrue(chunk.isPresent());
        
        // Decode
        List<TickData> decoded = decoder.decompressChunk(chunk.get());
        assertEquals(2, decoded.size());
        
        // Tick 0: 3 cells
        assertEquals(3, decoded.get(0).getCellColumns().getFlatIndicesCount());
        
        // Tick 1: 2 cells (cell 1 removed)
        assertEquals(2, decoded.get(1).getCellColumns().getFlatIndicesCount());
        assertCellNotPresent(decoded.get(1).getCellColumns(), 1);
    }
    
    @Test
    void roundTrip_preservesOrganismStates() throws ChunkCorruptedException {
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, TOTAL_CELLS, 1, 2, 1);
        
        // Tick 0: 1 organism
        env.setMolecule(Molecule.fromInt(10), new int[]{0, 0});
        Optional<TickDataChunk> chunk0 = encoder.captureTick(
                0, env,
                List.of(OrganismState.newBuilder().setOrganismId(1).setEnergy(100).build()),
                1,
                ByteString.copyFromUtf8("rng-0"),
                List.of());
        env.resetChangeTracking();
        assertFalse(chunk0.isPresent());
        
        // Tick 1: 2 organisms (completes chunk)
        env.setMolecule(Molecule.fromInt(11), new int[]{1, 0});
        Optional<TickDataChunk> chunk = encoder.captureTick(
                1, env,
                List.of(
                        OrganismState.newBuilder().setOrganismId(1).setEnergy(90).build(),
                        OrganismState.newBuilder().setOrganismId(2).setEnergy(50).build()
                ),
                2,
                ByteString.copyFromUtf8("rng-1"),
                List.of());
        env.resetChangeTracking();
        assertTrue(chunk.isPresent());
        
        // Decode
        List<TickData> decoded = decoder.decompressChunk(chunk.get());
        
        // Tick 0: 1 organism
        assertEquals(1, decoded.get(0).getOrganismsCount());
        assertEquals(100, decoded.get(0).getOrganisms(0).getEnergy());
        
        // Tick 1: 2 organisms
        assertEquals(2, decoded.get(1).getOrganismsCount());
        assertEquals(90, decoded.get(1).getOrganisms(0).getEnergy());
        assertEquals(50, decoded.get(1).getOrganisms(1).getEnergy());
    }
    
    @Test
    void roundTrip_partialChunk_preservesAllData() throws ChunkCorruptedException {
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, TOTAL_CELLS, 2, 10, 1);
        
        // Capture only 2 ticks (not a full chunk)
        env.setMolecule(Molecule.fromInt(10), new int[]{0, 0});
        captureTick(encoder, 0);
        
        env.setMolecule(Molecule.fromInt(20), new int[]{1, 0});
        captureTick(encoder, 1);
        
        // Flush partial chunk
        Optional<TickDataChunk> partial = encoder.flushPartialChunk();
        assertTrue(partial.isPresent());
        assertEquals(2, partial.get().getTickCount());
        
        // Decode partial chunk
        List<TickData> decoded = decoder.decompressChunk(partial.get());
        assertEquals(2, decoded.size());
        
        // Verify both ticks
        assertEquals(0, decoded.get(0).getTickNumber());
        assertEquals(1, decoded.get(1).getTickNumber());
    }
    
    // ========================================================================
    // Round-Trip: Accumulated Delta Optimization
    // ========================================================================
    
    @Test
    void roundTrip_accumulatedDeltaOptimization_worksCorrectly() throws ChunkCorruptedException {
        // Create encoder with accumulated delta interval = 2
        // Accumulated deltas at ticks 2, 4, 6, ...
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, TOTAL_CELLS, 2, 4, 1);
        
        // Build up several ticks
        env.setMolecule(Molecule.fromInt(10), new int[]{0, 0});
        captureTick(encoder, 0);  // snapshot
        
        env.setMolecule(Molecule.fromInt(11), new int[]{1, 0});
        captureTick(encoder, 1);  // incremental
        
        env.setMolecule(Molecule.fromInt(12), new int[]{2, 0});
        captureTick(encoder, 2);  // accumulated
        
        env.setMolecule(Molecule.fromInt(13), new int[]{3, 0});
        captureTick(encoder, 3);  // incremental
        
        env.setMolecule(Molecule.fromInt(14), new int[]{4, 0});
        captureTick(encoder, 4);  // accumulated
        
        env.setMolecule(Molecule.fromInt(15), new int[]{5, 0});
        captureTick(encoder, 5);  // incremental
        
        env.setMolecule(Molecule.fromInt(16), new int[]{6, 0});
        captureTick(encoder, 6);  // accumulated
        
        env.setMolecule(Molecule.fromInt(17), new int[]{7, 0});
        Optional<TickDataChunk> chunk = captureTick(encoder, 7);  // incremental (completes chunk)
        assertTrue(chunk.isPresent());
        
        // Decompress single tick at position 7
        // This should use accumulated delta at tick 6, then apply tick 7
        TickData tick7 = decoder.decompressTick(chunk.get(), 7);
        assertEquals(7, tick7.getTickNumber());
        assertEquals(8, tick7.getCellColumns().getFlatIndicesCount()); // cells 0-7
    }
    
    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    private Optional<TickDataChunk> captureTick(DeltaCodec.Encoder encoder, long tick) {
        Optional<TickDataChunk> result = encoder.captureTick(
                tick,
                env,
                List.of(OrganismState.newBuilder().setOrganismId(1).setEnergy(100).build()),
                1,
                ByteString.copyFromUtf8("rng-" + tick),
                List.of(PluginState.newBuilder().setPluginClass("TestPlugin").build())
        );
        // Note: captureTick resets change tracking internally
        return result;
    }
    
    private void assertCellColumnsEqual(CellDataColumns expected, CellDataColumns actual) {
        assertEquals(expected.getFlatIndicesCount(), actual.getFlatIndicesCount(),
                "Cell count mismatch");
        
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
