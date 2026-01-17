package org.evochora.datapipeline.utils.delta;

import com.google.protobuf.ByteString;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.DeltaType;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.StrategyState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.runtime.model.Environment;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;

/**
 * Builds TickDataChunks from sampled ticks using delta compression.
 * <p>
 * This class encapsulates all chunk-building logic to keep SimulationEngine clean.
 * It tracks changes between samples and decides when to create snapshots, incremental
 * deltas, or accumulated deltas based on the configured intervals.
 * <p>
 * <strong>Usage:</strong>
 * <pre>{@code
 * ChunkBuilder builder = new ChunkBuilder(runId, 5, 20, 1);
 * 
 * // For each sampled tick:
 * Optional<TickDataChunk> chunk = builder.captureTick(
 *     tick, env, organisms, totalCreated, rngState, strategies);
 * 
 * if (chunk.isPresent()) {
 *     queue.send(chunk.get());
 * }
 * 
 * // On shutdown:
 * builder.flushPartialChunk().ifPresent(queue::send);
 * }</pre>
 * <p>
 * <strong>Interval Hierarchy:</strong>
 * <ul>
 *   <li>accumulatedDeltaInterval: Samples between accumulated deltas</li>
 *   <li>snapshotInterval: Accumulated deltas between snapshots</li>
 *   <li>chunkInterval: Snapshots per chunk</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Use from single thread only.
 *
 * @see DeltaCodec
 */
public class ChunkBuilder {
    
    private final String runId;
    private final int accumulatedDeltaInterval;
    private final int snapshotInterval;
    private final int chunkInterval;
    
    // Derived values
    private final int samplesPerSnapshot;
    private final int samplesPerChunk;
    
    // State
    private TickData currentSnapshot;
    private final List<DeltaCapture> currentDeltas = new ArrayList<>();
    private final BitSet accumulatedSinceSnapshot;
    private int samplesSinceSnapshot = 0;
    private int snapshotsInChunk = 0;
    
    /**
     * Creates a new ChunkBuilder with the specified configuration.
     *
     * @param runId simulation run ID for chunk metadata
     * @param totalCells total cells in environment (for BitSet allocation)
     * @param accumulatedDeltaInterval samples between accumulated deltas (must be >= 1)
     * @param snapshotInterval accumulated deltas between snapshots (must be >= 1)
     * @param chunkInterval snapshots per chunk (must be >= 1)
     * @throws IllegalArgumentException if any interval is less than 1
     */
    public ChunkBuilder(String runId, int totalCells, 
                        int accumulatedDeltaInterval, int snapshotInterval, int chunkInterval) {
        if (accumulatedDeltaInterval < 1) {
            throw new IllegalArgumentException("accumulatedDeltaInterval must be >= 1, got: " + accumulatedDeltaInterval);
        }
        if (snapshotInterval < 1) {
            throw new IllegalArgumentException("snapshotInterval must be >= 1, got: " + snapshotInterval);
        }
        if (chunkInterval < 1) {
            throw new IllegalArgumentException("chunkInterval must be >= 1, got: " + chunkInterval);
        }
        
        this.runId = runId;
        this.accumulatedDeltaInterval = accumulatedDeltaInterval;
        this.snapshotInterval = snapshotInterval;
        this.chunkInterval = chunkInterval;
        
        this.samplesPerSnapshot = accumulatedDeltaInterval * snapshotInterval;
        this.samplesPerChunk = samplesPerSnapshot * chunkInterval;
        
        this.accumulatedSinceSnapshot = new BitSet(totalCells);
    }
    
    /**
     * Captures a sampled tick and returns a chunk if one is complete.
     * <p>
     * Call this method for every sampled tick (after samplingInterval filtering).
     * The method determines the tick type (snapshot/accumulated/incremental) and
     * adds it to the current chunk.
     *
     * @param tick tick number
     * @param env environment (for cell extraction)
     * @param organisms current organism states
     * @param totalOrganismsCreated total organisms created since simulation start
     * @param rngState RNG state bytes
     * @param strategyStates energy strategy states
     * @return Optional containing a complete chunk, or empty if chunk not yet complete
     */
    public Optional<TickDataChunk> captureTick(
            long tick,
            Environment env,
            List<OrganismState> organisms,
            long totalOrganismsCreated,
            ByteString rngState,
            List<StrategyState> strategyStates) {
        
        // Get changes since last sample
        BitSet changedSinceLastSample = env.getChangedIndices();
        
        // Accumulate changes for accumulated deltas
        accumulatedSinceSnapshot.or(changedSinceLastSample);
        
        // Determine tick type
        boolean isSnapshot = (samplesSinceSnapshot == 0);
        boolean isAccumulated = !isSnapshot && (samplesSinceSnapshot % accumulatedDeltaInterval == 0);
        
        long captureTimeMs = System.currentTimeMillis();
        
        if (isSnapshot) {
            // Full snapshot - extract all cells
            CellDataColumns allCells = extractAllCells(env);
            currentSnapshot = TickData.newBuilder()
                    .setSimulationRunId(runId)
                    .setTickNumber(tick)
                    .setCaptureTimeMs(captureTimeMs)
                    .setCellColumns(allCells)
                    .addAllOrganisms(organisms)
                    .setTotalOrganismsCreated(totalOrganismsCreated)
                    .setRngState(rngState)
                    .addAllStrategyStates(strategyStates)
                    .build();
            
            snapshotsInChunk++;
            accumulatedSinceSnapshot.clear();
        } else if (isAccumulated) {
            // Accumulated delta - all changes since last snapshot
            CellDataColumns changedCells = extractCellsFromBitSet(env, accumulatedSinceSnapshot);
            DeltaCapture delta = DeltaCodec.captureDelta(
                    tick, captureTimeMs, DeltaType.ACCUMULATED,
                    changedCells, organisms, totalOrganismsCreated,
                    rngState, strategyStates);
            currentDeltas.add(delta);
        } else {
            // Incremental delta - only changes since last sample
            CellDataColumns changedCells = extractCellsFromBitSet(env, changedSinceLastSample);
            DeltaCapture delta = DeltaCodec.captureDelta(
                    tick, captureTimeMs, DeltaType.INCREMENTAL,
                    changedCells, organisms, totalOrganismsCreated,
                    ByteString.EMPTY, List.of());  // No RNG/strategy for incremental
            currentDeltas.add(delta);
        }
        
        samplesSinceSnapshot++;
        
        // Reset change tracking for next sample
        env.resetChangeTracking();
        
        // Check if chunk is complete
        if (snapshotsInChunk >= chunkInterval && samplesSinceSnapshot >= samplesPerSnapshot) {
            return Optional.of(buildAndResetChunk());
        }
        
        return Optional.empty();
    }
    
    /**
     * Flushes any partial chunk on shutdown.
     * <p>
     * Call this during graceful shutdown to ensure no data is lost.
     * A partial chunk still starts with a snapshot and is self-contained.
     *
     * @return Optional containing the partial chunk, or empty if no data buffered
     */
    public Optional<TickDataChunk> flushPartialChunk() {
        if (currentSnapshot == null) {
            return Optional.empty();
        }
        return Optional.of(buildAndResetChunk());
    }
    
    /**
     * Returns the number of samples per chunk.
     *
     * @return samples per chunk
     */
    public int getSamplesPerChunk() {
        return samplesPerChunk;
    }
    
    /**
     * Returns whether a chunk is currently being built.
     *
     * @return true if there's a snapshot buffered
     */
    public boolean hasPartialChunk() {
        return currentSnapshot != null;
    }
    
    // ========================================================================
    // Private Helpers
    // ========================================================================
    
    private TickDataChunk buildAndResetChunk() {
        TickDataChunk chunk = DeltaCodec.createChunk(runId, currentSnapshot, currentDeltas);
        
        // Reset state for next chunk
        currentSnapshot = null;
        currentDeltas.clear();
        samplesSinceSnapshot = 0;
        snapshotsInChunk = 0;
        // Note: accumulatedSinceSnapshot is cleared when new snapshot is taken
        
        return chunk;
    }
    
    private CellDataColumns extractAllCells(Environment env) {
        CellDataColumns.Builder builder = CellDataColumns.newBuilder();
        
        env.forEachOccupiedIndex(flatIndex -> {
            builder.addFlatIndices(flatIndex);
            builder.addMoleculeData(env.getMoleculeInt(flatIndex));
            builder.addOwnerIds(env.getOwnerIdByIndex(flatIndex));
        });
        
        return builder.build();
    }
    
    private CellDataColumns extractCellsFromBitSet(Environment env, BitSet changedIndices) {
        CellDataColumns.Builder builder = CellDataColumns.newBuilder();
        
        // Iterate over set bits
        for (int flatIndex = changedIndices.nextSetBit(0); 
             flatIndex >= 0; 
             flatIndex = changedIndices.nextSetBit(flatIndex + 1)) {
            
            builder.addFlatIndices(flatIndex);
            builder.addMoleculeData(env.getMoleculeInt(flatIndex));
            builder.addOwnerIds(env.getOwnerIdByIndex(flatIndex));
        }
        
        return builder.build();
    }
}
