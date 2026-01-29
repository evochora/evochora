package org.evochora.datapipeline.utils.delta;

import com.google.protobuf.ByteString;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.DeltaType;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.PluginState;
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
         * Creates a new Encoder for a new simulation.
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
            this.chunkInterval = chunkInterval;

            this.samplesPerSnapshot = accumulatedDeltaInterval * snapshotInterval;
            this.samplesPerChunk = samplesPerSnapshot * chunkInterval;

            this.accumulatedSinceSnapshot = new BitSet(totalCells);
        }

        /**
         * Creates an Encoder initialized with a checkpoint snapshot for resume.
         * <p>
         * The encoder is primed with the snapshot so subsequent ticks are treated
         * as deltas within the same chunk, not as new chunk starts.
         *
         * @param resumeSnapshot checkpoint snapshot (must not be null)
         * @param runId simulation run ID for chunk metadata
         * @param totalCells total cells in environment (for BitSet allocation)
         * @param accumulatedDeltaInterval samples between accumulated deltas (must be >= 1)
         * @param snapshotInterval accumulated deltas between snapshots (must be >= 1)
         * @param chunkInterval snapshots per chunk (must be >= 1)
         * @return encoder initialized with the checkpoint snapshot
         * @throws IllegalArgumentException if resumeSnapshot is null or any interval is less than 1
         */
        public static Encoder forResume(TickData resumeSnapshot, String runId, int totalCells,
                                        int accumulatedDeltaInterval, int snapshotInterval, int chunkInterval) {
            if (resumeSnapshot == null) {
                throw new IllegalArgumentException("resumeSnapshot cannot be null");
            }
            Encoder encoder = new Encoder(runId, totalCells, accumulatedDeltaInterval, snapshotInterval, chunkInterval);
            encoder.currentSnapshot = resumeSnapshot;
            encoder.samplesSinceSnapshot = 1;  // Snapshot counts as sample 0, next tick is sample 1
            encoder.snapshotsInChunk = 1;
            return encoder;
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
         * @param pluginStates energy strategy states
         * @return Optional containing a complete chunk, or empty if chunk not yet complete
         */
        public Optional<TickDataChunk> captureTick(
                long tick,
                Environment env,
                List<OrganismState> organisms,
                long totalOrganismsCreated,
                ByteString rngState,
                List<PluginState> pluginStates) {
            
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
                        .addAllPluginStates(pluginStates)
                        .build();
                
                snapshotsInChunk++;
                accumulatedSinceSnapshot.clear();
            } else if (isAccumulated) {
                // Accumulated delta - all changes since last snapshot
                // Note: RNG state and plugin states are only stored in snapshots (not accumulated deltas)
                // since resume always happens from snapshot (chunk start)
                CellDataColumns changedCells = extractCellsFromBitSet(env, accumulatedSinceSnapshot);
                DeltaCapture delta = captureDelta(
                        tick, captureTimeMs, DeltaType.ACCUMULATED,
                        changedCells, organisms, totalOrganismsCreated,
                        ByteString.EMPTY, List.of());  // No RNG/plugin state for accumulated
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
            // Note: chunkInterval is a multiplier for chunk size, not "snapshots per chunk"
            // (TickDataChunk only holds one snapshot; chunkInterval just means larger chunks)
            if (samplesSinceSnapshot >= samplesPerChunk) {
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
        
        // State tracking for incremental decompression
        private TickDataChunk currentChunk;
        private long currentTick;
        
        /**
         * Creates a new Decoder for environments with the specified cell count.
         *
         * @param totalCells total cells in the environment (product of all dimensions)
         * @throws IllegalArgumentException if totalCells is not positive
         */
        public Decoder(int totalCells) {
            this.state = new MutableCellState(totalCells);
            this.currentChunk = null;
            this.currentTick = -1;
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
         * Resets the decoder state.
         * <p>
         * Call this when switching between unrelated decompression sequences,
         * or to force a fresh start from snapshot.
         */
        public void reset() {
            state.reset();
            currentChunk = null;
            currentTick = -1;
        }
        
        /**
         * Decompresses all ticks in a chunk to full TickData objects.
         * <p>
         * This reconstructs the complete environment state for each tick by applying
         * deltas sequentially to the snapshot.
         * <p>
         * <strong>Note:</strong> This method resets decoder state. For incremental
         * processing of selected ticks, use {@link #decompressTick} instead.
         *
         * @param chunk the chunk to decompress
         * @return list of fully reconstructed TickData, one per tick in the chunk
         * @throws ChunkCorruptedException if the chunk is corrupt or missing required data
         */
        public List<TickData> decompressChunk(TickDataChunk chunk) throws ChunkCorruptedException {
            validateChunk(chunk);
            
            // Reset state for full chunk decompression
            reset();
            
            List<TickData> result = new ArrayList<>(chunk.getTickCount());
            
            // First tick is the snapshot
            TickData snapshot = chunk.getSnapshot();
            state.applySnapshot(snapshot.getCellColumns());
            result.add(snapshot);
            currentChunk = chunk;
            currentTick = snapshot.getTickNumber();
            
            // Apply each delta and build TickData
            for (TickDelta delta : chunk.getDeltasList()) {
                validateDelta(delta);
                state.applyDelta(delta.getChangedCells());
                currentTick = delta.getTickNumber();
                
                TickData reconstructed = TickData.newBuilder()
                        .setSimulationRunId(chunk.getSimulationRunId())
                        .setTickNumber(delta.getTickNumber())
                        .setCaptureTimeMs(delta.getCaptureTimeMs())
                        .setCellColumns(state.toCellDataColumns())
                        .addAllOrganisms(delta.getOrganismsList())
                        .setTotalOrganismsCreated(delta.getTotalOrganismsCreated())
                        .setRngState(delta.getRngState())
                        .addAllPluginStates(delta.getPluginStatesList())
                        .build();
                
                result.add(reconstructed);
            }
            
            return result;
        }
        
        /**
         * Decompresses a single tick from a chunk to a full TickData.
         * <p>
         * <strong>Stateful Optimization:</strong> The decoder tracks its current position.
         * For sequential forward access (e.g., tick 100 → 101 → 102), only the new deltas
         * are applied. For backward jumps or chunk changes, the state is rebuilt from
         * the best starting point (snapshot or accumulated delta).
         * <p>
         * <strong>Accumulated Delta Optimization:</strong> For larger forward jumps,
         * the decoder finds the closest accumulated delta and uses it as a shortcut,
         * skipping all incremental deltas before it.
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
                // Update state tracking even for snapshot returns
                if (currentChunk != chunk) {
                    state.reset();
                    state.applySnapshot(snapshot.getCellColumns());
                    currentChunk = chunk;
                    currentTick = targetTick;
                }
                return snapshot;
            }
            
            // Validate target tick is in range
            if (targetTick < chunk.getFirstTick() || targetTick > chunk.getLastTick()) {
                throw new ChunkCorruptedException(
                        "Target tick " + targetTick + " not in chunk range [" + 
                        chunk.getFirstTick() + ", " + chunk.getLastTick() + "]");
            }
            
            List<TickDelta> deltas = chunk.getDeltasList();
            
            // Determine if we can reuse current state
            boolean canReuseState = (currentChunk == chunk) && (currentTick <= targetTick);
            
            if (!canReuseState) {
                // Need to rebuild state: different chunk or backward jump
                rebuildStateForTick(chunk, snapshot, deltas, targetTick);
            } else if (currentTick < targetTick) {
                // Same chunk, forward jump - check if accumulated delta shortcut is better
                advanceStateToTick(chunk, snapshot, deltas, targetTick);
            }
            // else: currentTick == targetTick, state is already correct
            
            // Find the target delta to get metadata (organisms, etc.)
            TickDelta targetDelta = findDelta(deltas, targetTick);
            if (targetDelta == null) {
                throw new ChunkCorruptedException(
                        "Target tick " + targetTick + " not found in chunk deltas");
            }
            
            currentTick = targetTick;
            
            return TickData.newBuilder()
                    .setSimulationRunId(chunk.getSimulationRunId())
                    .setTickNumber(targetDelta.getTickNumber())
                    .setCaptureTimeMs(targetDelta.getCaptureTimeMs())
                    .setCellColumns(state.toCellDataColumns())
                    .addAllOrganisms(targetDelta.getOrganismsList())
                    .setTotalOrganismsCreated(targetDelta.getTotalOrganismsCreated())
                    .setRngState(targetDelta.getRngState())
                    .addAllPluginStates(targetDelta.getPluginStatesList())
                    .build();
        }
        
        /**
         * Rebuilds state from scratch for a target tick.
         * Uses accumulated deltas as shortcuts when available.
         */
        private void rebuildStateForTick(TickDataChunk chunk, TickData snapshot,
                                          List<TickDelta> deltas, long targetTick) {
            state.reset();
            currentChunk = chunk;
            
            // Find best starting point (closest accumulated delta before target)
            TickDelta bestAcc = null;
            int bestAccIndex = -1;
            
            for (int i = 0; i < deltas.size(); i++) {
                TickDelta delta = deltas.get(i);
                if (delta.getTickNumber() > targetTick) {
                    break;
                }
                if (delta.getDeltaType() == DeltaType.ACCUMULATED) {
                    bestAcc = delta;
                    bestAccIndex = i;
                }
            }
            
            // Apply snapshot
            state.applySnapshot(snapshot.getCellColumns());
            
            if (bestAcc != null) {
                // Use accumulated delta as shortcut
                state.applyDelta(bestAcc.getChangedCells());
                currentTick = bestAcc.getTickNumber();
                
                // Apply remaining incremental deltas
                for (int i = bestAccIndex + 1; i < deltas.size(); i++) {
                    TickDelta delta = deltas.get(i);
                    if (delta.getTickNumber() > targetTick) {
                        break;
                    }
                    state.applyDelta(delta.getChangedCells());
                    currentTick = delta.getTickNumber();
                }
            } else {
                // No accumulated delta, apply all deltas from snapshot
                currentTick = snapshot.getTickNumber();
                for (TickDelta delta : deltas) {
                    if (delta.getTickNumber() > targetTick) {
                        break;
                    }
                    state.applyDelta(delta.getChangedCells());
                    currentTick = delta.getTickNumber();
                }
            }
        }
        
        /**
         * Advances state from current position to target tick.
         * Checks if an accumulated delta shortcut is more efficient.
         */
        private void advanceStateToTick(TickDataChunk chunk, TickData snapshot,
                                         List<TickDelta> deltas, long targetTick) {
            // Find if there's an accumulated delta between currentTick and targetTick
            TickDelta bestAcc = null;
            int bestAccIndex = -1;
            
            for (int i = 0; i < deltas.size(); i++) {
                TickDelta delta = deltas.get(i);
                if (delta.getTickNumber() <= currentTick) {
                    continue; // Already past this delta
                }
                if (delta.getTickNumber() > targetTick) {
                    break;
                }
                if (delta.getDeltaType() == DeltaType.ACCUMULATED) {
                    bestAcc = delta;
                    bestAccIndex = i;
                }
            }
            
            if (bestAcc != null) {
                // Accumulated delta found - reset and use it as shortcut
                // (accumulated contains all changes since snapshot, more efficient than incremental chain)
                state.reset();
                state.applySnapshot(snapshot.getCellColumns());
                state.applyDelta(bestAcc.getChangedCells());
                currentTick = bestAcc.getTickNumber();
                
                // Apply remaining incremental deltas after the accumulated
                for (int i = bestAccIndex + 1; i < deltas.size(); i++) {
                    TickDelta delta = deltas.get(i);
                    if (delta.getTickNumber() > targetTick) {
                        break;
                    }
                    state.applyDelta(delta.getChangedCells());
                    currentTick = delta.getTickNumber();
                }
            } else {
                // No accumulated delta in range - apply incrementals from current position
                for (TickDelta delta : deltas) {
                    if (delta.getTickNumber() <= currentTick) {
                        continue; // Already applied
                    }
                    if (delta.getTickNumber() > targetTick) {
                        break;
                    }
                    state.applyDelta(delta.getChangedCells());
                    currentTick = delta.getTickNumber();
                }
            }
        }
        
        /**
         * Finds a delta by tick number.
         */
        private TickDelta findDelta(List<TickDelta> deltas, long tickNumber) {
            for (TickDelta delta : deltas) {
                if (delta.getTickNumber() == tickNumber) {
                    return delta;
                }
            }
            return null;
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
     * @param pluginStates strategy states (empty for INCREMENTAL)
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
            List<PluginState> pluginStates) {
        
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
                .addAllPluginStates(pluginStates != null ? pluginStates : List.of())
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
     * @param pluginStates strategy states
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
            List<PluginState> pluginStates) {
        
        TickDelta delta = createDelta(
                tickNumber, captureTimeMs, deltaType,
                changedCells, organisms, totalOrganismsCreated,
                rngState, pluginStates);
        
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
