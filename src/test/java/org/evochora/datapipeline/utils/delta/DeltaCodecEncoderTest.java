package org.evochora.datapipeline.utils.delta;

import com.google.protobuf.ByteString;
import org.evochora.datapipeline.api.contracts.DeltaType;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.PluginState;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DeltaCodec.Encoder}.
 */
@Tag("unit")
class DeltaCodecEncoderTest {
    
    private static final String RUN_ID = "test-run";
    private Environment env;
    
    @BeforeEach
    void setUp() {
        // 10x10 environment = 100 cells
        env = new Environment(new int[]{10, 10}, false);
    }
    
    // ========================================================================
    // Configuration Validation
    // ========================================================================
    
    @Test
    void constructor_invalidAccumulatedInterval_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new DeltaCodec.Encoder(RUN_ID, 100, 0, 10, 1));
    }
    
    @Test
    void constructor_invalidSnapshotInterval_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new DeltaCodec.Encoder(RUN_ID, 100, 5, 0, 1));
    }
    
    @Test
    void constructor_invalidChunkInterval_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new DeltaCodec.Encoder(RUN_ID, 100, 5, 10, 0));
    }
    
    @Test
    void getSamplesPerChunk_calculatesCorrectly() {
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, 100, 5, 20, 1);
        assertEquals(100, encoder.getSamplesPerChunk());  // 5 * 20 * 1
        
        DeltaCodec.Encoder encoder2 = new DeltaCodec.Encoder(RUN_ID, 100, 2, 10, 3);
        assertEquals(60, encoder2.getSamplesPerChunk());  // 2 * 10 * 3
    }
    
    // ========================================================================
    // Snapshot Creation
    // ========================================================================
    
    @Test
    void captureTick_firstTick_createsSnapshot() {
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, 100, 5, 20, 1);
        
        // Set some cells
        env.setMolecule(Molecule.fromInt(100), new int[]{0, 0});
        env.setMolecule(Molecule.fromInt(200), new int[]{5, 5});
        
        Optional<TickDataChunk> result = captureTick(encoder, 0);
        
        // First tick doesn't complete chunk yet (need 100 samples)
        assertFalse(result.isPresent());
        assertTrue(encoder.hasPartialChunk());
    }
    
    @Test
    void captureTick_atSnapshotInterval_createsSnapshot() {
        // With accumulatedDeltaInterval=2, snapshotInterval=2
        // Snapshots at sample 0, 4, 8, ...
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, 100, 2, 2, 1);
        
        // Sample 0: snapshot
        captureTick(encoder, 0);
        
        // Sample 1: incremental
        env.setMolecule(Molecule.fromInt(100), new int[]{1, 1});
        captureTick(encoder, 1);
        
        // Sample 2: accumulated
        env.setMolecule(Molecule.fromInt(200), new int[]{2, 2});
        captureTick(encoder, 2);
        
        // Sample 3: incremental
        env.setMolecule(Molecule.fromInt(300), new int[]{3, 3});
        Optional<TickDataChunk> result = captureTick(encoder, 3);
        
        // Chunk complete (4 samples = 1 chunk with chunkInterval=1, snapshotInterval=2, accumulatedDeltaInterval=2)
        assertTrue(result.isPresent());
        
        TickDataChunk chunk = result.get();
        assertEquals(RUN_ID, chunk.getSimulationRunId());
        assertEquals(0, chunk.getFirstTick());
        assertEquals(3, chunk.getLastTick());
        assertEquals(4, chunk.getTickCount());  // 1 snapshot + 3 deltas
    }
    
    // ========================================================================
    // Delta Types
    // ========================================================================
    
    @Test
    void captureTick_incrementalDelta_hasIncrementalType() {
        // accumulatedDeltaInterval=5 means accumulated at 5, 10, 15, ...
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, 100, 5, 20, 1);
        
        // Sample 0: snapshot
        captureTick(encoder, 0);
        
        // Sample 1: should be incremental (not at accumulated interval)
        env.setMolecule(Molecule.fromInt(100), new int[]{1, 1});
        captureTick(encoder, 1);
        
        // Flush to inspect
        Optional<TickDataChunk> chunk = encoder.flushPartialChunk();
        assertTrue(chunk.isPresent());
        
        assertEquals(1, chunk.get().getDeltasCount());
        assertEquals(DeltaType.INCREMENTAL, chunk.get().getDeltas(0).getDeltaType());
    }
    
    @Test
    void captureTick_accumulatedDelta_hasAccumulatedType() {
        // accumulatedDeltaInterval=2 means accumulated at 2, 4, 6, ...
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, 100, 2, 10, 1);
        
        // Sample 0: snapshot
        captureTick(encoder, 0);
        
        // Sample 1: incremental
        env.setMolecule(Molecule.fromInt(100), new int[]{1, 1});
        captureTick(encoder, 1);
        
        // Sample 2: accumulated
        env.setMolecule(Molecule.fromInt(200), new int[]{2, 2});
        captureTick(encoder, 2);
        
        // Flush to inspect
        Optional<TickDataChunk> chunk = encoder.flushPartialChunk();
        assertTrue(chunk.isPresent());
        
        assertEquals(2, chunk.get().getDeltasCount());
        assertEquals(DeltaType.INCREMENTAL, chunk.get().getDeltas(0).getDeltaType());
        assertEquals(DeltaType.ACCUMULATED, chunk.get().getDeltas(1).getDeltaType());
    }
    
    @Test
    void captureTick_accumulatedDelta_containsAllChangesSinceSnapshot() {
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, 100, 2, 10, 1);
        
        // Sample 0: snapshot
        env.setMolecule(Molecule.fromInt(10), new int[]{0, 0});
        captureTick(encoder, 0);
        
        // Sample 1: change cell 1
        env.setMolecule(Molecule.fromInt(100), new int[]{1, 0});
        captureTick(encoder, 1);
        
        // Sample 2: change cell 2 (accumulated should have cells 1 AND 2)
        env.setMolecule(Molecule.fromInt(200), new int[]{2, 0});
        captureTick(encoder, 2);
        
        // Flush and check accumulated delta
        TickDataChunk chunk = encoder.flushPartialChunk().get();
        
        // Accumulated delta (index 1) should contain both cells 1 and 2
        var accumulatedDelta = chunk.getDeltas(1);
        assertEquals(DeltaType.ACCUMULATED, accumulatedDelta.getDeltaType());
        assertEquals(2, accumulatedDelta.getChangedCells().getFlatIndicesCount());
    }
    
    // ========================================================================
    // Flush Partial Chunk
    // ========================================================================
    
    @Test
    void flushPartialChunk_noData_returnsEmpty() {
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, 100, 5, 20, 1);
        
        assertFalse(encoder.flushPartialChunk().isPresent());
    }
    
    @Test
    void flushPartialChunk_withData_returnsPartialChunk() {
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, 100, 5, 20, 1);
        
        // Capture a few ticks (not enough for complete chunk)
        captureTick(encoder, 0);  // snapshot
        env.setMolecule(Molecule.fromInt(100), new int[]{1, 1});
        captureTick(encoder, 1);  // delta
        
        Optional<TickDataChunk> chunk = encoder.flushPartialChunk();
        
        assertTrue(chunk.isPresent());
        assertEquals(0, chunk.get().getFirstTick());
        assertEquals(1, chunk.get().getLastTick());
        assertEquals(2, chunk.get().getTickCount());
    }
    
    @Test
    void flushPartialChunk_clearsState() {
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, 100, 5, 20, 1);
        
        captureTick(encoder, 0);
        assertTrue(encoder.hasPartialChunk());
        
        encoder.flushPartialChunk();
        
        assertFalse(encoder.hasPartialChunk());
        assertFalse(encoder.flushPartialChunk().isPresent());
    }
    
    // ========================================================================
    // Multi-Chunk Test
    // ========================================================================
    
    @Test
    void captureTick_multipleChunks_workCorrectly() {
        // Small intervals for easy testing: 1 accumulated, 2 snapshots, 1 chunk
        // = 2 samples per chunk
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, 100, 1, 2, 1);

        // Chunk 1: samples 0, 1
        env.setMolecule(Molecule.fromInt(10), new int[]{0, 0});
        Optional<TickDataChunk> chunk1sample0 = captureTick(encoder, 0);
        assertFalse(chunk1sample0.isPresent());

        env.setMolecule(Molecule.fromInt(20), new int[]{1, 0});
        Optional<TickDataChunk> chunk1 = captureTick(encoder, 1);
        assertTrue(chunk1.isPresent());
        assertEquals(0, chunk1.get().getFirstTick());
        assertEquals(1, chunk1.get().getLastTick());

        // Chunk 2: samples 2, 3
        env.setMolecule(Molecule.fromInt(30), new int[]{2, 0});
        Optional<TickDataChunk> chunk2sample0 = captureTick(encoder, 2);
        assertFalse(chunk2sample0.isPresent());

        env.setMolecule(Molecule.fromInt(40), new int[]{3, 0});
        Optional<TickDataChunk> chunk2 = captureTick(encoder, 3);
        assertTrue(chunk2.isPresent());
        assertEquals(2, chunk2.get().getFirstTick());
        assertEquals(3, chunk2.get().getLastTick());
    }

    @Test
    void captureTick_withChunkIntervalGreaterThanOne_producesLargerChunks() {
        // Test that chunkInterval > 1 works correctly
        // accumulatedDeltaInterval=2, snapshotInterval=2, chunkInterval=2
        // samplesPerSnapshot = 2 * 2 = 4
        // samplesPerChunk = 4 * 2 = 8
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, 100, 2, 2, 2);
        assertEquals(8, encoder.getSamplesPerChunk());

        // Capture 8 samples (ticks 0-7)
        // Sample 0: snapshot
        // Sample 1: incremental
        // Sample 2: accumulated
        // Sample 3: incremental
        // Sample 4: incremental (NOT a new snapshot!)
        // Sample 5: incremental
        // Sample 6: accumulated
        // Sample 7: incremental -> chunk complete!
        for (int i = 0; i < 7; i++) {
            env.setMolecule(Molecule.fromInt(100 + i), new int[]{i % 10, i / 10});
            Optional<TickDataChunk> result = captureTick(encoder, i);
            assertFalse(result.isPresent(), "Chunk should not complete at tick " + i);
        }

        // 8th sample (tick 7) should complete the chunk
        env.setMolecule(Molecule.fromInt(200), new int[]{7, 0});
        Optional<TickDataChunk> chunk = captureTick(encoder, 7);

        assertTrue(chunk.isPresent(), "Chunk should complete at tick 7");
        assertEquals(0, chunk.get().getFirstTick());
        assertEquals(7, chunk.get().getLastTick());
        assertEquals(8, chunk.get().getTickCount());  // 1 snapshot + 7 deltas

        // Verify delta types
        // isAccumulated = !isSnapshot && (samplesSinceSnapshot % accumulatedDeltaInterval == 0)
        // With accumulatedDeltaInterval=2:
        // tick 1 (sample 1): 1%2=1 → INCREMENTAL
        // tick 2 (sample 2): 2%2=0 → ACCUMULATED
        // tick 3 (sample 3): 3%2=1 → INCREMENTAL
        // tick 4 (sample 4): 4%2=0 → ACCUMULATED
        // tick 5 (sample 5): 5%2=1 → INCREMENTAL
        // tick 6 (sample 6): 6%2=0 → ACCUMULATED
        // tick 7 (sample 7): 7%2=1 → INCREMENTAL
        var deltas = chunk.get().getDeltasList();
        assertEquals(7, deltas.size());
        assertEquals(DeltaType.INCREMENTAL, deltas.get(0).getDeltaType()); // tick 1
        assertEquals(DeltaType.ACCUMULATED, deltas.get(1).getDeltaType()); // tick 2
        assertEquals(DeltaType.INCREMENTAL, deltas.get(2).getDeltaType()); // tick 3
        assertEquals(DeltaType.ACCUMULATED, deltas.get(3).getDeltaType()); // tick 4
        assertEquals(DeltaType.INCREMENTAL, deltas.get(4).getDeltaType()); // tick 5
        assertEquals(DeltaType.ACCUMULATED, deltas.get(5).getDeltaType()); // tick 6
        assertEquals(DeltaType.INCREMENTAL, deltas.get(6).getDeltaType()); // tick 7
    }

    @Test
    void captureTick_withChunkIntervalThree_producesCorrectChunks() {
        // Even larger chunkInterval to confirm scalability
        // accumulatedDeltaInterval=1, snapshotInterval=2, chunkInterval=3
        // samplesPerSnapshot = 1 * 2 = 2
        // samplesPerChunk = 2 * 3 = 6
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, 100, 1, 2, 3);
        assertEquals(6, encoder.getSamplesPerChunk());

        // Capture 6 samples
        for (int i = 0; i < 5; i++) {
            env.setMolecule(Molecule.fromInt(100 + i), new int[]{i, 0});
            Optional<TickDataChunk> result = captureTick(encoder, i);
            assertFalse(result.isPresent(), "Chunk should not complete at tick " + i);
        }

        // 6th sample should complete chunk
        env.setMolecule(Molecule.fromInt(200), new int[]{5, 0});
        Optional<TickDataChunk> chunk = captureTick(encoder, 5);

        assertTrue(chunk.isPresent(), "Chunk should complete at tick 5");
        assertEquals(0, chunk.get().getFirstTick());
        assertEquals(5, chunk.get().getLastTick());
        assertEquals(6, chunk.get().getTickCount());
    }
    
    // ========================================================================
    // Integration: Change Tracking Reset
    // ========================================================================
    
    @Test
    void captureTick_resetsChangeTracking() {
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(RUN_ID, 100, 5, 20, 1);
        
        // Make a change
        env.setMolecule(Molecule.fromInt(100), new int[]{5, 5});
        assertEquals(1, env.getChangedIndices().cardinality());
        
        // Capture tick - should reset
        captureTick(encoder, 0);
        
        // Change tracking should be reset
        assertEquals(0, env.getChangedIndices().cardinality());
    }
    
    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    private Optional<TickDataChunk> captureTick(DeltaCodec.Encoder encoder, long tick) {
        return encoder.captureTick(
                tick,
                env,
                List.of(OrganismState.newBuilder().setOrganismId(1).setEnergy(100).build()),
                1,
                ByteString.copyFromUtf8("rng-" + tick),
                List.of(PluginState.newBuilder().setPluginClass("TestPlugin").build())
        );
    }
}
