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
import org.evochora.datapipeline.api.delta.ICellStateSource;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.function.LongPredicate;

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

        // Derived values
        private final int samplesPerSnapshot;
        private final int samplesPerChunk;

        // State
        private TickData currentSnapshot;
        private final List<DeltaCapture> currentDeltas = new ArrayList<>();
        private final BitSet accumulatedSinceSnapshot;
        private int samplesSinceSnapshot = 0;

        // Reusable builder to avoid repeated allocations
        private final CellDataColumns.Builder cellColumnsBuilder = CellDataColumns.newBuilder();
        
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
         * @param totalUniqueGenomes total unique genomes ever observed
         * @param allGenomesEverSeen set of all genome hashes ever seen (stored in snapshots only)
         * @param rngState RNG state bytes
         * @param pluginStates energy strategy states
         * @return Optional containing a complete chunk, or empty if chunk not yet complete
         */
        public Optional<TickDataChunk> captureTick(
                long tick,
                Environment env,
                List<OrganismState> organisms,
                long totalOrganismsCreated,
                long totalUniqueGenomes,
                LongOpenHashSet allGenomesEverSeen,
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
                TickData.Builder snapshotBuilder = TickData.newBuilder()
                        .setSimulationRunId(runId)
                        .setTickNumber(tick)
                        .setCaptureTimeMs(captureTimeMs)
                        .setCellColumns(allCells)
                        .addAllOrganisms(organisms)
                        .setTotalOrganismsCreated(totalOrganismsCreated)
                        .setTotalUniqueGenomes(totalUniqueGenomes)
                        .setRngState(rngState)
                        .addAllPluginStates(pluginStates);
                // Store genome hash set in snapshots for resume
                for (long hash : allGenomesEverSeen) {
                    snapshotBuilder.addAllGenomeHashesEverSeen(hash);
                }
                currentSnapshot = snapshotBuilder.build();

                accumulatedSinceSnapshot.clear();
            } else if (isAccumulated) {
                // Accumulated delta - all changes since last snapshot
                // Note: RNG state and plugin states are only stored in snapshots (not accumulated deltas)
                // since resume always happens from snapshot (chunk start)
                CellDataColumns changedCells = extractCellsFromBitSet(env, accumulatedSinceSnapshot);
                DeltaCapture delta = captureDelta(
                        tick, captureTimeMs, DeltaType.ACCUMULATED,
                        changedCells, organisms, totalOrganismsCreated,
                        totalUniqueGenomes,
                        ByteString.EMPTY, List.of());  // No RNG/plugin state for accumulated
                currentDeltas.add(delta);
            } else {
                // Incremental delta - only changes since last sample
                CellDataColumns changedCells = extractCellsFromBitSet(env, changedSinceLastSample);
                DeltaCapture delta = captureDelta(
                        tick, captureTimeMs, DeltaType.INCREMENTAL,
                        changedCells, organisms, totalOrganismsCreated,
                        totalUniqueGenomes,
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
            // Note: accumulatedSinceSnapshot is cleared when new snapshot is taken
            
            return chunk;
        }
        
        /**
         * Serializes every occupied cell. The environment iterates in its own index order and hands
         * out its own indices; the columns carry the canonical row-major index of
         * {@link EnvironmentProperties}, the numbering every reader of persisted tick data expects.
         */
        private CellDataColumns extractAllCells(Environment env) {
            cellColumnsBuilder.clear();

            env.forEachOccupiedIndex(flatIndex -> {
                cellColumnsBuilder.addFlatIndices(env.toCanonicalIndex(flatIndex));
                cellColumnsBuilder.addMoleculeData(env.getMoleculeInt(flatIndex));
                cellColumnsBuilder.addOwnerIds(env.getOwnerIdByIndex(flatIndex));
            });

            return cellColumnsBuilder.build();
        }

        /**
         * Serializes the cells whose environment indices are set in {@code changedIndices}, under
         * their canonical row-major index as in {@link #extractAllCells}.
         */
        private CellDataColumns extractCellsFromBitSet(Environment env, BitSet changedIndices) {
            cellColumnsBuilder.clear();

            // Iterate over set bits
            for (int flatIndex = changedIndices.nextSetBit(0);
                 flatIndex >= 0;
                 flatIndex = changedIndices.nextSetBit(flatIndex + 1)) {

                cellColumnsBuilder.addFlatIndices(env.toCanonicalIndex(flatIndex));
                cellColumnsBuilder.addMoleculeData(env.getMoleculeInt(flatIndex));
                cellColumnsBuilder.addOwnerIds(env.getOwnerIdByIndex(flatIndex));
            }

            return cellColumnsBuilder.build();
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
         * The chunk {@link #state} was built from, compared by identity, or {@code null} before
         * anything has been decompressed.
         * <p>
         * <strong>Invariant:</strong> while this is set, {@link #state} holds exactly the
         * environment at {@link #currentTick}, reconstructed from this chunk's snapshot. The
         * reconstruction relies on it - it advances the state it has rather than rebuilding, and
         * applies an accumulated delta on top of it. Every place that touches the state updates
         * both fields in the same step, and {@link #reset()} clears all three together; a change
         * that separates them would make reconstructions silently wrong rather than fail.
         * <p>
         * <strong>Rests on a chunk holding one snapshot.</strong> An accumulated delta accumulates
         * since the last snapshot, so applying it to a state from earlier in the chunk is only
         * correct while no snapshot lies in between. A chunk cannot express one today - it has a
         * single snapshot field and the delta types are incremental and accumulated - and giving it
         * several would not compile against the calls to {@code getSnapshot()} here. Whoever makes
         * that change has to give this shortcut a second condition: reuse the state only when no
         * snapshot lies between where it stands and the target.
         */
        private TickDataChunk currentChunk;

        /** The tick {@link #state} holds, or -1 before anything has been decompressed. */
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
         * Applies a delta's cells, refusing to continue if they were not read.
         * <p>
         * A chunk may be read with its cells built only for the deltas a reconstruction was
         * expected to walk through. Should that expectation be wrong, the missing link would not
         * announce itself - the reconstruction would simply produce a different world. The encoder
         * always writes the field, so its absence means it was dropped while reading, and that is
         * a reason to stop rather than to continue.
         *
         * @param delta the delta to apply
         * @throws ChunkCorruptedException if the delta was read without its cells
         */
        private void applyDeltaCells(TickDelta delta) throws ChunkCorruptedException {
            if (!delta.hasChangedCells()) {
                throw new ChunkCorruptedException(
                    "Delta at tick " + delta.getTickNumber() + " was read without its cells, but "
                    + "the reconstruction needs it.");
            }
            state.applyDelta(delta.getChangedCells());
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
         * <strong>State afterwards:</strong> the decoder stands on the last tick of the chunk,
         * not on a cleared state. A later call need not account for that: it compares its target
         * against the tick the decoder stands on and lays the snapshot down again where it cannot
         * work forward. For incremental processing of selected ticks, use {@link #decompressTick}
         * instead, which reconstructs one tick without building every other one.
         *
         * @param chunk the chunk to decompress
         * @return list of fully reconstructed TickData, one per tick in the chunk
         * @throws ChunkCorruptedException if the chunk is corrupt or missing required data
         */
        public List<TickData> decompressChunk(TickDataChunk chunk) throws ChunkCorruptedException {
            validateChunk(chunk);

            List<TickData> result = new ArrayList<>(chunk.getTickCount());

            // No reset here: applySnapshot clears both arrays before it writes, and the chunk
            // identity is overwritten a line below
            TickData snapshot = chunk.getSnapshot();
            state.applySnapshot(snapshot.getCellColumns());
            result.add(snapshot);
            currentChunk = chunk;
            currentTick = snapshot.getTickNumber();
            
            // Apply each delta and build TickData
            for (TickDelta delta : chunk.getDeltasList()) {
                validateDelta(delta);
                applyDeltaCells(delta);
                currentTick = delta.getTickNumber();
                
                TickData reconstructed = TickData.newBuilder()
                        .setSimulationRunId(chunk.getSimulationRunId())
                        .setTickNumber(delta.getTickNumber())
                        .setCaptureTimeMs(delta.getCaptureTimeMs())
                        .setCellColumns(state.toCellDataColumns())
                        .addAllOrganisms(delta.getOrganismsList())
                        .setTotalOrganismsCreated(delta.getTotalOrganismsCreated())
                        .setTotalUniqueGenomes(delta.getTotalUniqueGenomes())
                        .setRngState(delta.getRngState())
                        .addAllPluginStates(delta.getPluginStatesList())
                        .build();

                result.add(reconstructed);

                // Yield after each delta to prevent system freezing during full-chunk decompression
                Thread.yield();
            }

            return result;
        }
        
        /**
         * Reconstructs a tick but leaves its cells in the decoder's state instead of packing them
         * into the returned {@link TickData}, whose cell columns stay empty. The chunk's snapshot
         * is the exception: it already carries its cells and is handed back as it stands, with the
         * state holding the same cells.
         * <p>
         * For consumers that only read the cells: building the columns walks the whole grid and
         * allocates a message holding every occupied cell, which is wasted work when the result is
         * read once and discarded. Reach the cells through {@link #getCellState()} afterwards; they
         * stay valid until the next call on this decoder.
         *
         * @param chunk      the chunk holding the tick
         * @param targetTick the tick to reconstruct
         * @return the tick's data without cell columns
         * @throws ChunkCorruptedException if the chunk is corrupt or does not hold the tick
         */
        public TickData decompressTickCellsInState(TickDataChunk chunk, long targetTick)
                throws ChunkCorruptedException {
            return decompressTick(chunk, targetTick, false);
        }

        /**
         * The cells of the tick reconstructed last, for use with
         * {@link #decompressTickCellsInState(TickDataChunk, long)}.
         *
         * @return the decoder's cell state
         */
        public ICellStateSource getCellState() {
            return state;
        }

        /**
         * Reconstructs a single tick of a chunk, its cells included in the returned
         * {@link TickData}.
         * <p>
         * The decoder holds on to the cells of the tick it reconstructed last. A tick that lies
         * later in the same chunk is built on top of them; a tick that lies earlier, or one from
         * another chunk, makes the decoder lay the chunk's snapshot down again and work forward
         * from there. Either way an accumulated delta on the path is taken as a shortcut over the
         * incremental deltas it spans. The chunk's snapshot tick is handed back as it stands, since
         * it already carries its cells.
         *
         * @param chunk      the chunk holding the tick
         * @param targetTick the tick to reconstruct
         * @return the tick's data with its cell columns filled in
         * @throws ChunkCorruptedException if the chunk is corrupt, does not hold the tick, or holds
         *         a delta on the path that was read without its cells
         */
        public TickData decompressTick(TickDataChunk chunk, long targetTick)
                throws ChunkCorruptedException {
            return decompressTick(chunk, targetTick, true);
        }

        private TickData decompressTick(TickDataChunk chunk, long targetTick, boolean includeCells)
                throws ChunkCorruptedException {
            validateChunk(chunk);
            
            // Check if target is the snapshot
            TickData snapshot = chunk.getSnapshot();
            if (snapshot.getTickNumber() == targetTick) {
                // The state has to describe the tick that is returned. Standing anywhere else in
                // this chunk means standing on a later tick, whose deltas are only undone by
                // laying the snapshot down again.
                if (currentChunk != chunk || currentTick != targetTick) {
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
                advanceStateToTick(deltas, targetTick);
            }
            // else: currentTick == targetTick, state is already correct
            
            // Find the target delta to get metadata (organisms, etc.)
            TickDelta targetDelta = findDelta(deltas, targetTick);
            if (targetDelta == null) {
                throw new ChunkCorruptedException(
                        "Target tick " + targetTick + " not found in chunk deltas");
            }
            
            currentTick = targetTick;
            
            TickData.Builder builder = TickData.newBuilder()
                    .setSimulationRunId(chunk.getSimulationRunId())
                    .setTickNumber(targetDelta.getTickNumber())
                    .setCaptureTimeMs(targetDelta.getCaptureTimeMs())
                    .addAllOrganisms(targetDelta.getOrganismsList())
                    .setTotalOrganismsCreated(targetDelta.getTotalOrganismsCreated())
                    .setTotalUniqueGenomes(targetDelta.getTotalUniqueGenomes())
                    .setRngState(targetDelta.getRngState())
                    .addAllPluginStates(targetDelta.getPluginStatesList());
            if (includeCells) {
                builder.setCellColumns(state.toCellDataColumns());
            }
            return builder.build();
        }
        
        /**
         * Rebuilds state from scratch for a target tick.
         * Uses accumulated deltas as shortcuts when available.
         */
        private void rebuildStateForTick(TickDataChunk chunk, TickData snapshot,
                                          List<TickDelta> deltas, long targetTick) throws ChunkCorruptedException {
            // No reset here: applySnapshot below clears both arrays before it writes
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
                applyDeltaCells(bestAcc);
                currentTick = bestAcc.getTickNumber();
                
                // Apply remaining incremental deltas
                for (int i = bestAccIndex + 1; i < deltas.size(); i++) {
                    TickDelta delta = deltas.get(i);
                    if (delta.getTickNumber() > targetTick) {
                        break;
                    }
                    applyDeltaCells(delta);
                    currentTick = delta.getTickNumber();
                }
            } else {
                // No accumulated delta, apply all deltas from snapshot
                currentTick = snapshot.getTickNumber();
                for (TickDelta delta : deltas) {
                    if (delta.getTickNumber() > targetTick) {
                        break;
                    }
                    applyDeltaCells(delta);
                    currentTick = delta.getTickNumber();
                }
            }
        }
        
        /**
         * Advances the state from where it stands to the target tick.
         * <p>
         * Only called while the state belongs to this chunk and lies at or before the target, so it
         * builds on what is there instead of starting over. An accumulated delta in between is
         * taken as a shortcut: it carries everything that changed since the snapshot, so applying
         * it skips the incremental deltas it spans.
         */
        private void advanceStateToTick(List<TickDelta> deltas, long targetTick)
                throws ChunkCorruptedException {
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
                // An accumulated delta carries every cell that changed since the snapshot, with the
                // values it has at that point. Applied to a state that already stands on an earlier
                // recording of this chunk, it overwrites exactly those cells and leaves the rest -
                // which never changed since the snapshot - as they are. The result is the state at
                // the accumulated delta, so the snapshot does not have to be laid down again.
                applyDeltaCells(bestAcc);
                currentTick = bestAcc.getTickNumber();
                
                // Apply remaining incremental deltas after the accumulated
                for (int i = bestAccIndex + 1; i < deltas.size(); i++) {
                    TickDelta delta = deltas.get(i);
                    if (delta.getTickNumber() > targetTick) {
                        break;
                    }
                    applyDeltaCells(delta);
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
                    applyDeltaCells(delta);
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
     * @param totalUniqueGenomes total unique genomes ever observed
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
            long totalUniqueGenomes,
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
                .setTotalUniqueGenomes(totalUniqueGenomes)
                .setRngState(rngState != null ? rngState : ByteString.EMPTY)
                .addAllPluginStates(pluginStates != null ? pluginStates : List.of())
                .build();
    }
    
    /**
     * Determines which deltas of a chunk an environment reconstruction walks through.
     * <p>
     * The decoder reaches a tick by starting from the chunk's snapshot, jumping to the last
     * accumulated delta at or before the tick, and applying the incremental deltas from there.
     * Deltas outside those paths are never read, so their cells need not be built.
     * <p>
     * This is the same choice {@code Decoder.rebuildStateForTick} and
     * {@code Decoder.advanceStateToTick} make, stated once so that a reader deciding what to
     * materialize and the decoder consuming the result cannot drift apart.
     *
     * The snapshot is not among the positions this returns, and needs no say in them: its cells
     * are built whatever a reader asks for, because every reconstruction starts from them.
     *
     * @param deltaTicks   tick number of every delta, in chunk order
     * @param deltaTypes   type of every delta, in the same order
     * @param readsCellsAt answers whether the environment is read at a given tick
     * @return positions of the deltas whose cells a reconstruction touches
     * @throws IllegalArgumentException if the two directory columns differ in length
     */
    public static BitSet cellsNeededFor(List<Long> deltaTicks, List<DeltaType> deltaTypes,
                                        LongPredicate readsCellsAt) {
        if (deltaTicks.size() != deltaTypes.size()) {
            throw new IllegalArgumentException(
                "Delta directory is inconsistent: " + deltaTicks.size() + " ticks but "
                + deltaTypes.size() + " types");
        }
        BitSet needed = new BitSet(deltaTicks.size());
        int reached = -1;                  // last delta position the decoder stands on; -1 = snapshot

        for (int target = 0; target < deltaTicks.size(); target++) {
            if (!readsCellsAt.test(deltaTicks.get(target))) {
                continue;
            }
            int from = reached;
            for (int position = target; position > reached; position--) {
                if (deltaTypes.get(position) == DeltaType.ACCUMULATED) {
                    from = position;
                    needed.set(position);
                    break;
                }
            }
            for (int position = from + 1; position <= target; position++) {
                needed.set(position);
            }
            reached = target;
        }
        return needed;
    }

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
        
        // The directory precedes the deltas on the wire, so a reader knows which of them a
        // reconstruction walks through before it decides whether to build their payload
        for (DeltaCapture capture : deltas) {
            builder.addDeltaTicks(capture.delta().getTickNumber());
            builder.addDeltaTypes(capture.delta().getDeltaType());
        }
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
     * @param totalUniqueGenomes total unique genomes ever observed
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
            long totalUniqueGenomes,
            ByteString rngState,
            List<PluginState> pluginStates) {

        TickDelta delta = createDelta(
                tickNumber, captureTimeMs, deltaType,
                changedCells, organisms, totalOrganismsCreated,
                totalUniqueGenomes,
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
