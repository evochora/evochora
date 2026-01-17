package org.evochora.datapipeline.utils.delta;

import com.google.protobuf.ByteString;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.DeltaType;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.StrategyState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.delta.ChunkCorruptedException;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for delta compression and decompression of tick data.
 * <p>
 * This class provides static methods for:
 * <ul>
 *   <li><strong>Decompression:</strong> Reconstructing full tick states from chunks</li>
 *   <li><strong>Compression:</strong> Creating chunks from snapshots and deltas (added in Step 4)</li>
 * </ul>
 * <p>
 * <strong>Error Handling:</strong> All decompression methods throw {@link ChunkCorruptedException}
 * for corrupt data. Callers (services) should catch and handle gracefully - never abort.
 * <p>
 * <strong>Thread Safety:</strong> All methods are stateless and thread-safe.
 * <p>
 * Location: {@code org.evochora.datapipeline.utils.delta.DeltaCodec}
 *
 * @see ChunkCorruptedException
 * @see MutableCellState
 */
public final class DeltaCodec {
    
    private DeltaCodec() {
        // Utility class - no instantiation
    }
    
    // ========================================================================
    // Decompression Methods
    // ========================================================================
    
    /**
     * Decompresses all ticks in a chunk to full TickData objects.
     * <p>
     * This reconstructs the complete environment state for each tick by applying
     * deltas sequentially to the snapshot.
     * <p>
     * <strong>Memory:</strong> O(totalCells) for MutableCellState + O(tickCount) for results
     *
     * @param chunk the chunk to decompress
     * @param totalCells total cells in the environment (for MutableCellState allocation)
     * @return list of fully reconstructed TickData, one per tick in the chunk
     * @throws ChunkCorruptedException if the chunk is corrupt or missing required data
     */
    public static List<TickData> decompressChunk(TickDataChunk chunk, int totalCells) 
            throws ChunkCorruptedException {
        validateChunk(chunk);
        
        List<TickData> result = new ArrayList<>(chunk.getTickCount());
        MutableCellState state = new MutableCellState(totalCells);
        
        // First tick is the snapshot
        TickData snapshot = chunk.getSnapshot();
        state.applySnapshot(snapshot.getCellColumns());
        result.add(snapshot);
        
        // Apply each delta and build TickData
        for (TickDelta delta : chunk.getDeltasList()) {
            validateDelta(delta);
            state.applyDelta(delta.getChangedCells());
            
            TickData reconstructed = TickData.newBuilder()
                    .setSimulationRunId(chunk.getSimulationRunId())
                    .setTickNumber(delta.getTickNumber())
                    .setCaptureTimeMs(delta.getCaptureTimeMs())
                    .setCellColumns(state.toCellDataColumns())
                    .addAllOrganisms(delta.getOrganismsList())
                    .setTotalOrganismsCreated(delta.getTotalOrganismsCreated())
                    .setRngState(delta.getRngState())
                    .addAllStrategyStates(delta.getStrategyStatesList())
                    .build();
            
            result.add(reconstructed);
        }
        
        return result;
    }
    
    /**
     * Decompresses a single tick from a chunk to a full TickData.
     * <p>
     * This is more efficient than {@link #decompressChunk} when only one tick is needed,
     * as it stops applying deltas once the target tick is reached.
     * <p>
     * <strong>Optimization:</strong> If the target tick is after an accumulated delta,
     * we can skip incremental deltas before that accumulated delta.
     *
     * @param chunk the chunk containing the target tick
     * @param targetTick the tick number to decompress
     * @param totalCells total cells in the environment
     * @return the fully reconstructed TickData for the target tick
     * @throws ChunkCorruptedException if the chunk is corrupt or target tick not found
     */
    public static TickData decompressTick(TickDataChunk chunk, long targetTick, int totalCells) 
            throws ChunkCorruptedException {
        validateChunk(chunk);
        
        // Check if target is the snapshot
        TickData snapshot = chunk.getSnapshot();
        if (snapshot.getTickNumber() == targetTick) {
            return snapshot;
        }
        
        // Validate target tick is in range
        if (targetTick < chunk.getFirstTick() || targetTick > chunk.getLastTick()) {
            throw new ChunkCorruptedException(
                    "Target tick " + targetTick + " not in chunk range [" + 
                    chunk.getFirstTick() + ", " + chunk.getLastTick() + "]");
        }
        
        // Find best starting point (closest accumulated delta before target)
        TickDelta bestBase = null;
        int bestBaseIndex = -1;
        List<TickDelta> deltas = chunk.getDeltasList();
        
        for (int i = 0; i < deltas.size(); i++) {
            TickDelta delta = deltas.get(i);
            if (delta.getTickNumber() > targetTick) {
                break;
            }
            if (delta.getDeltaType() == DeltaType.ACCUMULATED) {
                bestBase = delta;
                bestBaseIndex = i;
            }
        }
        
        // Build state from best starting point
        MutableCellState state = new MutableCellState(totalCells);
        int startIndex;
        TickDelta targetDelta = null;
        
        if (bestBase != null && bestBase.getTickNumber() == targetTick) {
            // Target is exactly the accumulated delta - we're done after applying it
            state.applySnapshot(snapshot.getCellColumns());
            state.applyDelta(bestBase.getChangedCells());
            targetDelta = bestBase;
        } else if (bestBase != null) {
            // Start from accumulated delta (it contains all changes since snapshot)
            state.applySnapshot(snapshot.getCellColumns());
            state.applyDelta(bestBase.getChangedCells());
            startIndex = bestBaseIndex + 1;
            
            // Apply deltas until target tick
            for (int i = startIndex; i < deltas.size(); i++) {
                TickDelta delta = deltas.get(i);
                if (delta.getTickNumber() > targetTick) {
                    break;
                }
                state.applyDelta(delta.getChangedCells());
                if (delta.getTickNumber() == targetTick) {
                    targetDelta = delta;
                    break;
                }
            }
        } else {
            // Start from snapshot
            state.applySnapshot(snapshot.getCellColumns());
            startIndex = 0;
            
            // Apply deltas until target tick
            for (int i = startIndex; i < deltas.size(); i++) {
                TickDelta delta = deltas.get(i);
                if (delta.getTickNumber() > targetTick) {
                    break;
                }
                state.applyDelta(delta.getChangedCells());
                if (delta.getTickNumber() == targetTick) {
                    targetDelta = delta;
                    break;
                }
            }
        }
        
        if (targetDelta == null) {
            throw new ChunkCorruptedException(
                    "Target tick " + targetTick + " not found in chunk deltas");
        }
        
        return TickData.newBuilder()
                .setSimulationRunId(chunk.getSimulationRunId())
                .setTickNumber(targetDelta.getTickNumber())
                .setCaptureTimeMs(targetDelta.getCaptureTimeMs())
                .setCellColumns(state.toCellDataColumns())
                .addAllOrganisms(targetDelta.getOrganismsList())
                .setTotalOrganismsCreated(targetDelta.getTotalOrganismsCreated())
                .setRngState(targetDelta.getRngState())
                .addAllStrategyStates(targetDelta.getStrategyStatesList())
                .build();
    }
    
    /**
     * Reconstructs environment state by applying a list of deltas to a base snapshot.
     * <p>
     * This is a lower-level method useful for custom reconstruction logic.
     *
     * @param baseSnapshot the starting cell state (typically from TickData.cell_columns)
     * @param deltas list of delta cell changes to apply in order
     * @param totalCells total cells in the environment
     * @return the reconstructed cell state after all deltas
     */
    public static CellDataColumns reconstructEnvironment(
            CellDataColumns baseSnapshot, 
            List<CellDataColumns> deltas,
            int totalCells) {
        MutableCellState state = new MutableCellState(totalCells);
        state.applySnapshot(baseSnapshot);
        
        for (CellDataColumns delta : deltas) {
            state.applyDelta(delta);
        }
        
        return state.toCellDataColumns();
    }
    
    // ========================================================================
    // Compression Methods
    // ========================================================================
    
    /**
     * Creates a TickDelta from pre-extracted cell data.
     * <p>
     * The SimulationEngine extracts changed cells from Environment + BitSet before calling
     * this method. This keeps DeltaCodec independent of the runtime package.
     * <p>
     * <strong>Delta Types:</strong>
     * <ul>
     *   <li>{@code INCREMENTAL}: Changes since last sample. RNG/strategy states empty.</li>
     *   <li>{@code ACCUMULATED}: All changes since last snapshot. Includes RNG/strategy for checkpointing.</li>
     * </ul>
     *
     * @param tickNumber the simulation tick number
     * @param captureTimeMs wall-clock capture time in milliseconds
     * @param deltaType INCREMENTAL or ACCUMULATED
     * @param changedCells cell data for changed cells (extracted by SimulationEngine)
     * @param organisms current organism states
     * @param totalOrganismsCreated total organisms created since simulation start
     * @param rngState RNG state bytes (empty for INCREMENTAL, required for ACCUMULATED)
     * @param strategyStates strategy states (empty for INCREMENTAL, required for ACCUMULATED)
     * @return the constructed TickDelta protobuf message
     * @throws IllegalArgumentException if deltaType is UNSPECIFIED
     */
    public static TickDelta createDelta(
            long tickNumber,
            long captureTimeMs,
            DeltaType deltaType,
            CellDataColumns changedCells,
            List<OrganismState> organisms,
            long totalOrganismsCreated,
            ByteString rngState,
            List<StrategyState> strategyStates) {
        
        if (deltaType == DeltaType.DELTA_TYPE_UNSPECIFIED) {
            throw new IllegalArgumentException("deltaType must be INCREMENTAL or ACCUMULATED");
        }
        
        return TickDelta.newBuilder()
                .setTickNumber(tickNumber)
                .setCaptureTimeMs(captureTimeMs)
                .setDeltaType(deltaType)
                .setChangedCells(changedCells != null ? changedCells : CellDataColumns.getDefaultInstance())
                .addAllOrganisms(organisms != null ? organisms : List.of())
                .setTotalOrganismsCreated(totalOrganismsCreated)
                .setRngState(rngState != null ? rngState : ByteString.EMPTY)
                .addAllStrategyStates(strategyStates != null ? strategyStates : List.of())
                .build();
    }
    
    /**
     * Creates a TickDataChunk from a snapshot and list of deltas.
     * <p>
     * The chunk is a self-contained unit that starts with a full snapshot,
     * allowing decompression without access to previous chunks.
     *
     * @param simulationRunId the simulation run identifier
     * @param snapshot the full TickData snapshot (must be the first tick in the chunk)
     * @param deltas list of DeltaCapture objects for subsequent ticks
     * @return the constructed TickDataChunk protobuf message
     * @throws IllegalArgumentException if snapshot is null or deltas is null
     */
    public static TickDataChunk createChunk(
            String simulationRunId,
            TickData snapshot,
            List<DeltaCapture> deltas) {
        
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (deltas == null) {
            throw new IllegalArgumentException("deltas must not be null (use empty list)");
        }
        
        long firstTick = snapshot.getTickNumber();
        long lastTick = deltas.isEmpty() ? firstTick : deltas.get(deltas.size() - 1).tickNumber();
        int tickCount = 1 + deltas.size();  // snapshot + deltas
        
        TickDataChunk.Builder builder = TickDataChunk.newBuilder()
                .setSimulationRunId(simulationRunId)
                .setFirstTick(firstTick)
                .setLastTick(lastTick)
                .setTickCount(tickCount)
                .setSnapshot(snapshot);
        
        for (DeltaCapture capture : deltas) {
            builder.addDeltas(capture.delta());
        }
        
        return builder.build();
    }
    
    /**
     * Convenience method to create a DeltaCapture from delta parameters.
     * <p>
     * This wraps {@link #createDelta} and packages the result into a {@link DeltaCapture}.
     *
     * @param tickNumber the simulation tick number
     * @param captureTimeMs wall-clock capture time in milliseconds
     * @param deltaType INCREMENTAL or ACCUMULATED
     * @param changedCells cell data for changed cells
     * @param organisms current organism states
     * @param totalOrganismsCreated total organisms created since simulation start
     * @param rngState RNG state bytes (empty for INCREMENTAL)
     * @param strategyStates strategy states (empty for INCREMENTAL)
     * @return a DeltaCapture containing the constructed TickDelta
     */
    public static DeltaCapture captureDelta(
            long tickNumber,
            long captureTimeMs,
            DeltaType deltaType,
            CellDataColumns changedCells,
            List<OrganismState> organisms,
            long totalOrganismsCreated,
            ByteString rngState,
            List<StrategyState> strategyStates) {
        
        TickDelta delta = createDelta(
                tickNumber, captureTimeMs, deltaType,
                changedCells, organisms, totalOrganismsCreated,
                rngState, strategyStates);
        
        return new DeltaCapture(tickNumber, captureTimeMs, delta);
    }
    
    // ========================================================================
    // Validation Helpers
    // ========================================================================
    
    private static void validateChunk(TickDataChunk chunk) throws ChunkCorruptedException {
        if (chunk == null) {
            throw new ChunkCorruptedException("Chunk is null");
        }
        if (!chunk.hasSnapshot()) {
            throw new ChunkCorruptedException(
                    "Chunk missing snapshot (firstTick=" + chunk.getFirstTick() + ")");
        }
        if (chunk.getTickCount() < 1) {
            throw new ChunkCorruptedException(
                    "Chunk has invalid tick count: " + chunk.getTickCount());
        }
        if (chunk.getTickCount() != chunk.getDeltasCount() + 1) {
            throw new ChunkCorruptedException(
                    "Chunk tick count mismatch: tickCount=" + chunk.getTickCount() + 
                    ", deltas=" + chunk.getDeltasCount() + " (expected tickCount = deltas + 1)");
        }
    }
    
    private static void validateDelta(TickDelta delta) throws ChunkCorruptedException {
        if (delta.getDeltaType() == DeltaType.DELTA_TYPE_UNSPECIFIED) {
            throw new ChunkCorruptedException(
                    "Delta has unspecified type at tick " + delta.getTickNumber());
        }
    }
}
