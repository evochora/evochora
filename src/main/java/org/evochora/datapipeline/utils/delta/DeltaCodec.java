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
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;

/**
 * Central class for delta compression and decompression of tick data.
 * <p>
 * Provides two main components:
 * <ul>
 *   <li>{@link Encoder}: Builds chunks from sampled ticks (used by SimulationEngine)</li>
 *   <li>{@link Decoder}: Reconstructs ticks from chunks (used by EnvironmentController, Indexers)</li>
 * </ul>
 * <p>
 * Both Encoder and Decoder are instance-based to allow state reuse and avoid GC pressure.
 * <p>
 * <strong>Usage (Encoding):</strong>
 * <pre>{@code
 * DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(runId, totalCells, 5, 20, 1);
 * Optional<TickDataChunk> chunk = encoder.captureTick(tick, env, organisms, ...);
 * }</pre>
 * <p>
 * <strong>Usage (Decoding):</strong>
 * <pre>{@code
 * DeltaCodec.Decoder decoder = new DeltaCodec.Decoder(totalCells);
 * TickData tick = decoder.decompressTick(chunk, tickNumber);
 * }</pre>
 * <p>
 * <strong>Error Handling:</strong> Decoder methods throw {@link ChunkCorruptedException}
 * for corrupt data. Callers should catch and handle gracefully - never abort.
 *
 * @see ChunkCorruptedException
 * @see MutableCellState
 */
public final class DeltaCodec {
    
    private DeltaCodec() {
        // No instantiation - use Encoder or Decoder
    }
    
    // ========================================================================
    // Encoder (instance-based, replaces ChunkBuilder)
    // ========================================================================
    
    /**
     * Builds TickDataChunks from sampled ticks using delta compression.
     * <p>
     * This class encapsulates all chunk-building logic to keep SimulationEngine clean.
     * It tracks changes between samples and decides when to create snapshots, incremental
     * deltas, or accumulated deltas based on the configured intervals.
     * <p>
     * <strong>Usage:</strong>
     * <pre>{@code
     * DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(runId, totalCells, 5, 20, 1);
     * 
     * // For each sampled tick:
     * Optional<TickDataChunk> chunk = encoder.captureTick(
     *     tick, env, organisms, totalCreated, rngState, strategies);
     * 
     * if (chunk.isPresent()) {
     *     queue.send(chunk.get());
     * }
     * 
     * // On shutdown:
     * encoder.flushPartialChunk().ifPresent(queue::send);
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
     */
    public static final class Encoder {
        
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
         * Creates a new Encoder with the specified configuration.
         *
         * @param runId simulation run ID for chunk metadata
         * @param totalCells total cells in environment (for BitSet allocation)
         * @param accumulatedDeltaInterval samples between accumulated deltas (must be >= 1)
         * @param snapshotInterval accumulated deltas between snapshots (must be >= 1)
         * @param chunkInterval snapshots per chunk (must be >= 1)
         * @throws IllegalArgumentException if any interval is less than 1
         */
        public Encoder(String runId, int totalCells, 
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
                DeltaCapture delta = captureDelta(
                        tick, captureTimeMs, DeltaType.ACCUMULATED,
                        changedCells, organisms, totalOrganismsCreated,
                        rngState, strategyStates);
                currentDeltas.add(delta);
            } else {
                // Incremental delta - only changes since last sample
                CellDataColumns changedCells = extractCellsFromBitSet(env, changedSinceLastSample);
                DeltaCapture delta = captureDelta(
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
            TickDataChunk chunk = createChunk(runId, currentSnapshot, currentDeltas);
            
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
    
    // ========================================================================
    // Decoder (instance-based, reuses MutableCellState)
    // ========================================================================
    
    /**
     * Decompresses TickDataChunks to full TickData objects.
     * <p>
     * This class maintains a reusable {@link MutableCellState} to avoid GC pressure
     * when decompressing multiple ticks sequentially (e.g., video rendering, API requests).
     * <p>
     * <strong>Usage:</strong>
     * <pre>{@code
     * // Create once per runId (or cache)
     * DeltaCodec.Decoder decoder = new DeltaCodec.Decoder(totalCells);
     * 
     * // Decompress individual ticks (state is reset internally)
     * TickData tick1 = decoder.decompressTick(chunk1, tickNumber1);
     * TickData tick2 = decoder.decompressTick(chunk2, tickNumber2);
     * 
     * // Or decompress entire chunk
     * List<TickData> allTicks = decoder.decompressChunk(chunk);
     * }</pre>
     * <p>
     * <strong>Thread Safety:</strong> Not thread-safe. Use one Decoder per thread,
     * or synchronize externally.
     */
    public static final class Decoder {
        
        private final MutableCellState state;
        
        /**
         * Creates a new Decoder for environments with the specified cell count.
         *
         * @param totalCells total cells in the environment (product of all dimensions)
         * @throws IllegalArgumentException if totalCells is not positive
         */
        public Decoder(int totalCells) {
            this.state = new MutableCellState(totalCells);
        }
        
        /**
         * Creates a new Decoder from environment properties.
         *
         * @param envProps environment properties containing world shape
         */
        public Decoder(EnvironmentProperties envProps) {
            this(calculateTotalCells(envProps.getWorldShape()));
        }
        
        private static int calculateTotalCells(int[] worldShape) {
            int total = 1;
            for (int dim : worldShape) {
                total *= dim;
            }
            return total;
        }
        
        /**
         * Decompresses all ticks in a chunk to full TickData objects.
         * <p>
         * This reconstructs the complete environment state for each tick by applying
         * deltas sequentially to the snapshot.
         *
         * @param chunk the chunk to decompress
         * @return list of fully reconstructed TickData, one per tick in the chunk
         * @throws ChunkCorruptedException if the chunk is corrupt or missing required data
         */
        public List<TickData> decompressChunk(TickDataChunk chunk) throws ChunkCorruptedException {
            validateChunk(chunk);
            
            state.reset();
            List<TickData> result = new ArrayList<>(chunk.getTickCount());
            
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
         * we skip incremental deltas before that accumulated delta.
         *
         * @param chunk the chunk containing the target tick
         * @param targetTick the tick number to decompress
         * @return the fully reconstructed TickData for the target tick
         * @throws ChunkCorruptedException if the chunk is corrupt or target tick not found
         */
        public TickData decompressTick(TickDataChunk chunk, long targetTick) 
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
            
            state.reset();
            
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
    }
    
    // ========================================================================
    // Static Helper Methods (used by Encoder)
    // ========================================================================
    
    /**
     * Creates a TickDelta from pre-extracted cell data.
     * <p>
     * <strong>Delta Types:</strong>
     * <ul>
     *   <li>{@code INCREMENTAL}: Changes since last sample. RNG/strategy states empty.</li>
     *   <li>{@code ACCUMULATED}: All changes since last snapshot. Includes RNG/strategy.</li>
     * </ul>
     *
     * @param tickNumber the simulation tick number
     * @param captureTimeMs wall-clock capture time in milliseconds
     * @param deltaType INCREMENTAL or ACCUMULATED
     * @param changedCells cell data for changed cells
     * @param organisms current organism states
     * @param totalOrganismsCreated total organisms created since simulation start
     * @param rngState RNG state bytes (empty for INCREMENTAL)
     * @param strategyStates strategy states (empty for INCREMENTAL)
     * @return the constructed TickDelta protobuf message
     */
    static TickDelta createDelta(
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
     *
     * @param simulationRunId the simulation run identifier
     * @param snapshot the full TickData snapshot
     * @param deltas list of DeltaCapture objects for subsequent ticks
     * @return the constructed TickDataChunk protobuf message
     */
    static TickDataChunk createChunk(
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
        int tickCount = 1 + deltas.size();
        
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
     * Creates a DeltaCapture from delta parameters.
     *
     * @param tickNumber the simulation tick number
     * @param captureTimeMs wall-clock capture time in milliseconds
     * @param deltaType INCREMENTAL or ACCUMULATED
     * @param changedCells cell data for changed cells
     * @param organisms current organism states
     * @param totalOrganismsCreated total organisms created since simulation start
     * @param rngState RNG state bytes
     * @param strategyStates strategy states
     * @return a DeltaCapture containing the constructed TickDelta
     */
    static DeltaCapture captureDelta(
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
