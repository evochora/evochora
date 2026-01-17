package org.evochora.datapipeline.utils.delta;

import com.google.protobuf.ByteString;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.DeltaType;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.StrategyState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DeltaCodec} compression methods.
 */
@Tag("unit")
class DeltaCodecCompressionTest {
    
    private static final String RUN_ID = "test-run-id";
    
    // ========================================================================
    // createDelta Tests
    // ========================================================================
    
    @Test
    void createDelta_incremental_buildsValidProtobuf() {
        CellDataColumns cells = createCells(new int[]{5, 10}, new int[]{50, 100});
        List<OrganismState> organisms = List.of(
                OrganismState.newBuilder().setOrganismId(1).setEnergy(500).build()
        );
        
        TickDelta delta = DeltaCodec.createDelta(
                100L, 1000L, DeltaType.INCREMENTAL,
                cells, organisms, 5L,
                ByteString.EMPTY, List.of());
        
        assertEquals(100L, delta.getTickNumber());
        assertEquals(1000L, delta.getCaptureTimeMs());
        assertEquals(DeltaType.INCREMENTAL, delta.getDeltaType());
        assertEquals(2, delta.getChangedCells().getFlatIndicesCount());
        assertEquals(1, delta.getOrganismsCount());
        assertEquals(5L, delta.getTotalOrganismsCreated());
        assertTrue(delta.getRngState().isEmpty());
        assertEquals(0, delta.getStrategyStatesCount());
    }
    
    @Test
    void createDelta_accumulated_includesRngAndStrategies() {
        CellDataColumns cells = createCells(new int[]{0}, new int[]{10});
        ByteString rngState = ByteString.copyFromUtf8("rng-state-data");
        List<StrategyState> strategies = List.of(
                StrategyState.newBuilder().setStrategyType("org.evochora.runtime.worldgen.SolarRadiationCreator").build()
        );
        
        TickDelta delta = DeltaCodec.createDelta(
                500L, 5000L, DeltaType.ACCUMULATED,
                cells, List.of(), 10L,
                rngState, strategies);
        
        assertEquals(500L, delta.getTickNumber());
        assertEquals(DeltaType.ACCUMULATED, delta.getDeltaType());
        assertEquals(rngState, delta.getRngState());
        assertEquals(1, delta.getStrategyStatesCount());
        assertEquals("org.evochora.runtime.worldgen.SolarRadiationCreator", delta.getStrategyStates(0).getStrategyType());
    }
    
    @Test
    void createDelta_unspecifiedType_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                DeltaCodec.createDelta(
                        0L, 0L, DeltaType.DELTA_TYPE_UNSPECIFIED,
                        CellDataColumns.getDefaultInstance(), List.of(), 0L,
                        ByteString.EMPTY, List.of()));
    }
    
    @Test
    void createDelta_nullParams_handledGracefully() {
        // Null parameters should be converted to defaults
        TickDelta delta = DeltaCodec.createDelta(
                100L, 1000L, DeltaType.INCREMENTAL,
                null, null, 0L, null, null);
        
        assertEquals(100L, delta.getTickNumber());
        assertEquals(0, delta.getChangedCells().getFlatIndicesCount());
        assertEquals(0, delta.getOrganismsCount());
        assertTrue(delta.getRngState().isEmpty());
    }
    
    // ========================================================================
    // createChunk Tests
    // ========================================================================
    
    @Test
    void createChunk_snapshotOnly_buildsValidChunk() {
        TickData snapshot = createSnapshot(0L);
        
        TickDataChunk chunk = DeltaCodec.createChunk(RUN_ID, snapshot, List.of());
        
        assertEquals(RUN_ID, chunk.getSimulationRunId());
        assertEquals(0L, chunk.getFirstTick());
        assertEquals(0L, chunk.getLastTick());
        assertEquals(1, chunk.getTickCount());
        assertTrue(chunk.hasSnapshot());
        assertEquals(0, chunk.getDeltasCount());
    }
    
    @Test
    void createChunk_withDeltas_buildsValidChunk() {
        TickData snapshot = createSnapshot(0L);
        
        DeltaCapture delta1 = createDeltaCapture(100L, DeltaType.INCREMENTAL);
        DeltaCapture delta2 = createDeltaCapture(200L, DeltaType.INCREMENTAL);
        DeltaCapture delta3 = createDeltaCapture(300L, DeltaType.ACCUMULATED);
        
        TickDataChunk chunk = DeltaCodec.createChunk(
                RUN_ID, snapshot, List.of(delta1, delta2, delta3));
        
        assertEquals(0L, chunk.getFirstTick());
        assertEquals(300L, chunk.getLastTick());
        assertEquals(4, chunk.getTickCount());
        assertEquals(3, chunk.getDeltasCount());
        
        assertEquals(100L, chunk.getDeltas(0).getTickNumber());
        assertEquals(DeltaType.INCREMENTAL, chunk.getDeltas(0).getDeltaType());
        assertEquals(300L, chunk.getDeltas(2).getTickNumber());
        assertEquals(DeltaType.ACCUMULATED, chunk.getDeltas(2).getDeltaType());
    }
    
    @Test
    void createChunk_nullSnapshot_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                DeltaCodec.createChunk(RUN_ID, null, List.of()));
    }
    
    @Test
    void createChunk_nullDeltas_throws() {
        TickData snapshot = createSnapshot(0L);
        assertThrows(IllegalArgumentException.class, () ->
                DeltaCodec.createChunk(RUN_ID, snapshot, null));
    }
    
    // ========================================================================
    // captureDelta Tests
    // ========================================================================
    
    @Test
    void captureDelta_wrapsCreateDelta() {
        CellDataColumns cells = createCells(new int[]{1}, new int[]{10});
        
        DeltaCapture capture = DeltaCodec.captureDelta(
                100L, 1000L, DeltaType.INCREMENTAL,
                cells, List.of(), 0L,
                ByteString.EMPTY, List.of());
        
        assertEquals(100L, capture.tickNumber());
        assertEquals(1000L, capture.captureTimeMs());
        assertNotNull(capture.delta());
        assertEquals(100L, capture.delta().getTickNumber());
        assertEquals(DeltaType.INCREMENTAL, capture.delta().getDeltaType());
    }
    
    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    private TickData createSnapshot(long tickNumber) {
        return TickData.newBuilder()
                .setSimulationRunId(RUN_ID)
                .setTickNumber(tickNumber)
                .setCaptureTimeMs(tickNumber * 10)
                .setCellColumns(CellDataColumns.getDefaultInstance())
                .setTotalOrganismsCreated(0)
                .build();
    }
    
    private DeltaCapture createDeltaCapture(long tickNumber, DeltaType type) {
        TickDelta delta = DeltaCodec.createDelta(
                tickNumber, tickNumber * 10, type,
                CellDataColumns.getDefaultInstance(), List.of(), 0L,
                ByteString.EMPTY, List.of());
        return new DeltaCapture(tickNumber, tickNumber * 10, delta);
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
}
