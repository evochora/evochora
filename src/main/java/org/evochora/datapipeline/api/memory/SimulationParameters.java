package org.evochora.datapipeline.api.memory;

/**
 * Parameters needed for memory estimation across all pipeline components.
 * <p>
 * All estimates use <strong>WORST-CASE assumptions</strong>:
 * <ul>
 *   <li>Environment: every cell occupied unless {@link #withCellOccupancy(double)} lowers the fraction</li>
 *   <li>Organisms: maxOrganisms at the configured maximum</li>
 * </ul>
 * <p>
 * <strong>Design Philosophy:</strong> Better to warn unnecessarily than crash after
 * days of simulation! Memory estimation should always be conservative to prevent
 * OOM errors during long-running simulations.
 * <p>
 * <strong>Memory Size Constants (Java Heap after Protobuf deserialization):</strong>
 * <ul>
 *   <li>{@link #BYTES_PER_CELL}: 56 bytes - CellState with 4 int32 fields + Protobuf overhead</li>
 *   <li>{@link #BYTES_PER_ORGANISM}: 2000 bytes - OrganismState with 48 RegisterValues, Vectors, stacks, persistent state</li>
 *   <li>{@link #TICKDATA_WRAPPER_OVERHEAD}: 500 bytes - TickData wrapper (simulation_run_id, rng_state, etc.)</li>
 * </ul>
 * <p>
 * <strong>Delta Compression Parameters:</strong>
 * <ul>
 *   <li>{@code samplingInterval}: Ticks between samples (default: 1)</li>
 *   <li>{@code accumulatedDeltaInterval}: Samples between accumulated deltas (default: 5)</li>
 *   <li>{@code snapshotInterval}: Accumulated deltas between snapshots (default: 10)</li>
 *   <li>{@code chunkInterval}: Snapshots per chunk (default: 1)</li>
 *   <li>{@code estimatedDeltaRatio}: Expected change rate per tick (default: 0.01 = 1%)</li>
 * </ul>
 * <p>
 * <strong>Usage:</strong> Created by ServiceManager from pipeline configuration
 * and passed to all {@link IMemoryEstimatable} components for estimation.
 *
 * @param environmentShape The environment dimensions (e.g., [800, 600] for 2D, [100, 100, 50] for 3D).
 *                          Used to calculate total cells.
 * @param totalCells Total cells in environment = product of shape dimensions.
 *                   For [800, 600] = 480,000 cells. This is the size of the world,
 *                   independent of occupancy.
 * @param occupiedCells Number of cells expected to hold a molecule or an owner; equal to
 *                      totalCells in the worst case.
 * @param maxOrganisms Maximum expected organisms in the simulation.
 *                     This should be a configured upper bound or a reasonable estimate.
 *                     For worst-case: assume all organisms alive simultaneously.
 * @param samplingInterval Ticks between samples.
 * @param accumulatedDeltaInterval Samples between accumulated deltas.
 * @param snapshotInterval Accumulated deltas between snapshots.
 * @param chunkInterval Snapshots per chunk.
 * @param estimatedDeltaRatio Expected change rate per tick (0.0-1.0).
 */
public record SimulationParameters(
    int[] environmentShape,
    long totalCells,
    long occupiedCells,
    int maxOrganisms,
    int samplingInterval,
    int accumulatedDeltaInterval,
    int snapshotInterval,
    int chunkInterval,
    double estimatedDeltaRatio
) {

    /**
     * Rejects parameters an estimate cannot be built on: a world without cells, a cell count that
     * does not match the shape, more occupied cells than the world has, intervals that would
     * divide by zero, or a change rate outside the unit interval. Every estimate derives from
     * these values, so a wrong one here would be wrong everywhere without any later step noticing.
     */
    public SimulationParameters {
        if (environmentShape == null || environmentShape.length == 0) {
            throw new IllegalArgumentException("environmentShape must have at least one dimension");
        }
        long product = 1L;
        for (int i = 0; i < environmentShape.length; i++) {
            if (environmentShape[i] < 1) {
                throw new IllegalArgumentException("environmentShape[" + i + "] must be >= 1, got " + environmentShape[i]);
            }
            try {
                product = Math.multiplyExact(product, environmentShape[i]);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("environmentShape " + java.util.Arrays.toString(environmentShape)
                        + " holds more cells than a long can count", overflow);
            }
        }
        if (totalCells != product) {
            throw new IllegalArgumentException("totalCells must equal the product of the shape ("
                    + product + "), got " + totalCells);
        }
        if (totalCells > Integer.MAX_VALUE) {
            // The environment indexes its cells with an int; the per-tick estimates, a cell count
            // times a few hundred bytes, therefore fit a long, and the per-chunk estimates check
            // their own arithmetic
            throw new IllegalArgumentException("totalCells must not exceed " + Integer.MAX_VALUE
                    + ", the most cells an environment can index, got " + totalCells);
        }
        if (occupiedCells < 0 || occupiedCells > totalCells) {
            throw new IllegalArgumentException("occupiedCells must lie between 0 and totalCells ("
                    + totalCells + "), got " + occupiedCells);
        }
        if (maxOrganisms < 0) {
            throw new IllegalArgumentException("maxOrganisms must be >= 0, got " + maxOrganisms);
        }
        requireAtLeastOne("samplingInterval", samplingInterval);
        requireAtLeastOne("accumulatedDeltaInterval", accumulatedDeltaInterval);
        requireAtLeastOne("snapshotInterval", snapshotInterval);
        requireAtLeastOne("chunkInterval", chunkInterval);
        try {
            // The ticks a chunk spans are the largest product the intervals form; it must fit an int
            Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(samplingInterval, accumulatedDeltaInterval),
                    snapshotInterval), chunkInterval);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("samplingInterval × accumulatedDeltaInterval × snapshotInterval"
                    + " × chunkInterval must not exceed " + Integer.MAX_VALUE + " ticks per chunk, got "
                    + samplingInterval + " × " + accumulatedDeltaInterval + " × " + snapshotInterval + " × " + chunkInterval,
                    overflow);
        }
        if (!(estimatedDeltaRatio >= 0.0 && estimatedDeltaRatio <= 1.0)) {
            throw new IllegalArgumentException("estimatedDeltaRatio must lie between 0.0 and 1.0, got " + estimatedDeltaRatio);
        }
    }

    private static void requireAtLeastOne(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be >= 1, got " + value);
        }
    }

    /**
     * Bytes per CellState in Java heap after Protobuf deserialization.
     * <p>
     * Breakdown:
     * <ul>
     *   <li>Object header: 12 bytes</li>
     *   <li>GeneratedMessageV3 base fields: 20 bytes</li>
     *   <li>4 int32 fields (flat_index, molecule_type, molecule_value, owner_id): 16 bytes</li>
     *   <li>Alignment padding: 8 bytes</li>
     * </ul>
     * Total: ~56 bytes per CellState
     */
    public static final int BYTES_PER_CELL = 56;
    
    /**
     * Bytes per OrganismState in Java heap after Protobuf deserialization.
     * <p>
     * Breakdown (8-bank register architecture, 48 register slots):
     * <ul>
     *   <li>Object header + base fields: 80 bytes</li>
     *   <li>Strings (program_id, failure_reason): 60 bytes</li>
     *   <li>7 Vector messages (ip, initial_position, dv, etc.): 280 bytes</li>
     *   <li>48 RegisterValue messages (32 scalar × 20 + 16 vector × 40): 1280 bytes</li>
     *   <li>Stacks (data_stack, location_stack, call_stack): 120 bytes</li>
     *   <li>Maps (instruction_register_values_before): 60 bytes</li>
     *   <li>PersistentRegisterStore (typical 1 snapshot): 120 bytes</li>
     * </ul>
     * Total: ~2000 bytes per OrganismState
     */
    public static final int BYTES_PER_ORGANISM = 2000;
    
    /**
     * Overhead bytes for TickData wrapper (excluding cells and organisms).
     * <p>
     * Includes: simulation_run_id (String), tick_number, capture_time_ms,
     * rng_state (bytes), strategy_states (list), total_organisms_created.
     */
    public static final int TICKDATA_WRAPPER_OVERHEAD = 500;

    // ========================================================================
    // Serialized Protobuf Wire Format Sizes (on-wire bytes, NOT Java heap)
    // ========================================================================

    /**
     * Serialized bytes per cell in protobuf wire format.
     * <p>
     * CellDataColumns uses 3 packed int32 columns (flat_indices, molecule_data, owner_ids).
     * Worst-case varint encoding for int32 is 5 bytes. 3 × 5 = 15 bytes/cell.
     */
    public static final int SERIALIZED_BYTES_PER_CELL = 15;

    /**
     * Serialized bytes per OrganismState in protobuf wire format.
     * <p>
     * Breakdown (typical worst case without deep stacks):
     * <ul>
     *   <li>48 register values (32 scalar × 5 + 16 vector × 12): ~352 bytes</li>
     *   <li>9 vectors (ip, initial_position, dv, data_pointers, etc.): ~207 bytes</li>
     *   <li>Fixed scalars (ids, energy, flags, counters): ~80 bytes</li>
     *   <li>Strings (program_id): ~30 bytes</li>
     *   <li>Instruction execution data + next instruction preview: ~80 bytes</li>
     *   <li>PersistentRegisterStore (typical 1 snapshot): ~150 bytes</li>
     * </ul>
     * Protobuf wire format is more compact than Java heap (no object headers/padding).
     */
    public static final int SERIALIZED_BYTES_PER_ORGANISM = 900;

    /**
     * Serialized TickData wrapper overhead in protobuf wire format.
     * <p>
     * Dominated by rng_state (Well19937c: 624 ints × 4 bytes = ~2500 bytes)
     * plus plugin states (~300 bytes) and string/counter fields (~200 bytes).
     */
    public static final int SERIALIZED_TICKDATA_WRAPPER_OVERHEAD = 3000;
    
    // ========================================================================
    // Default Values for Delta Compression
    // ========================================================================
    
    /** Default sampling interval (every tick). */
    public static final int DEFAULT_SAMPLING_INTERVAL = 1;
    
    /** Default accumulated delta interval (every 5 samples). */
    public static final int DEFAULT_ACCUMULATED_DELTA_INTERVAL = 5;
    
    /** Default snapshot interval (every 10 accumulated deltas). */
    public static final int DEFAULT_SNAPSHOT_INTERVAL = 10;
    
    /** Default chunk interval (1 snapshot per chunk = 50 ticks/chunk). */
    public static final int DEFAULT_CHUNK_INTERVAL = 1;
    
    /** Default estimated delta ratio (1% of cells change per tick). */
    public static final double DEFAULT_ESTIMATED_DELTA_RATIO = 0.01;

    /**
     * Default organism density factor for deriving maxOrganisms from totalCells.
     * <p>
     * Formula: {@code maxOrganisms = max(1, (int)(totalCells * organismDensityFactor))}
     */
    public static final double DEFAULT_ORGANISM_DENSITY_FACTOR = 0.0005;

    /**
     * Default upper bound on the cells one organism owns, for pricing the buffers the environment
     * keeps per visited organism. An assumption, not a limit of the simulation: the primordial
     * organism's field is 110 × 85 cells, of which it owns at most all, and until the fork of a
     * replication the copy belongs to it as well, so twice that, rounded up.
     */
    public static final int DEFAULT_MAX_CELLS_PER_ORGANISM = 20_000;

    /**
     * Default expected cell occupancy for realistic memory estimation.
     * <p>
     * Typical simulations occupy roughly 25% of environment cells with molecules.
     * Used alongside the 100% worst-case estimate to provide a more realistic
     * "expected peak" number.
     */
    public static final double DEFAULT_EXPECTED_CELL_OCCUPANCY = 0.25;

    // ========================================================================
    // Factory Methods
    // ========================================================================
    
    /**
     * Creates SimulationParameters from environment shape and max organisms with default delta settings.
     * <p>
     * Automatically calculates totalCells from shape dimensions.
     * Uses default delta compression parameters.
     *
     * @param environmentShape The environment dimensions (e.g., [800, 600]).
     * @param maxOrganisms Maximum expected organisms.
     * @return SimulationParameters with calculated totalCells and default delta settings.
     */
    public static SimulationParameters of(int[] environmentShape, int maxOrganisms) {
        long totalCells = 1L;
        for (int dim : environmentShape) {
            totalCells *= dim;
        }
        return new SimulationParameters(
            environmentShape, totalCells, totalCells, maxOrganisms,
            DEFAULT_SAMPLING_INTERVAL, DEFAULT_ACCUMULATED_DELTA_INTERVAL,
            DEFAULT_SNAPSHOT_INTERVAL, DEFAULT_CHUNK_INTERVAL, DEFAULT_ESTIMATED_DELTA_RATIO
        );
    }
    
    /**
     * Creates SimulationParameters with all parameters specified.
     *
     * @param environmentShape The environment dimensions.
     * @param maxOrganisms Maximum expected organisms.
     * @param samplingInterval Ticks between samples.
     * @param accumulatedDeltaInterval Samples between accumulated deltas.
     * @param snapshotInterval Accumulated deltas between snapshots.
     * @param chunkInterval Snapshots per chunk.
     * @param estimatedDeltaRatio Expected change rate per tick.
     * @return SimulationParameters with all values.
     */
    public static SimulationParameters of(
            int[] environmentShape, int maxOrganisms,
            int samplingInterval, int accumulatedDeltaInterval,
            int snapshotInterval, int chunkInterval, double estimatedDeltaRatio) {
        long totalCells = 1L;
        for (int dim : environmentShape) {
            totalCells *= dim;
        }
        return new SimulationParameters(
            environmentShape, totalCells, totalCells, maxOrganisms,
            samplingInterval, accumulatedDeltaInterval,
            snapshotInterval, chunkInterval, estimatedDeltaRatio
        );
    }
    
    /**
     * Returns a copy expecting the given fraction of the world's cells to be occupied.
     * <p>
     * {@link #totalCells()} stays the size of the world: structures that hold every cell, such as
     * the environment's grid arrays and bit sets, are estimated from it. Structures that hold only
     * occupied cells — persisted cells, owner indices, serialization buffers — are estimated from
     * {@link #occupiedCells()}, which this method scales. The factories start with every cell
     * occupied, the worst case; the "expected peak" estimation uses a lower fraction.
     *
     * @param occupancy Fraction of cells expected to be occupied (0.0–1.0).
     * @return New SimulationParameters with the world size kept and occupiedCells scaled.
     */
    public SimulationParameters withCellOccupancy(double occupancy) {
        if (!(occupancy >= 0.0 && occupancy <= 1.0)) {
            throw new IllegalArgumentException("occupancy must lie between 0.0 and 1.0, got " + occupancy);
        }
        long occupied = Math.max(1, (long) (totalCells * occupancy));
        return new SimulationParameters(
            environmentShape, totalCells, occupied, maxOrganisms,
            samplingInterval, accumulatedDeltaInterval,
            snapshotInterval, chunkInterval, estimatedDeltaRatio
        );
    }

    /**
     * Returns the worst-case cells per tick.
     * <p>
     * <strong>ALWAYS returns 100% occupancy</strong> - we estimate as if every cell
     * in the environment is occupied. This ensures we never underestimate memory
     * requirements.
     *
     * @return Total cells (100% occupancy, no reduction).
     */
    public long worstCaseCellsPerTick() {
        return totalCells;
    }
    
    /**
     * Returns the worst-case organisms per tick.
     * <p>
     * Returns the configured maxOrganisms value, assuming all organisms
     * are alive simultaneously.
     *
     * @return Maximum organisms (100% of configured limit).
     */
    public int worstCaseOrganismsPerTick() {
        return maxOrganisms;
    }
    
    /**
     * Calculates total bytes per TickData in Java heap at worst-case.
     * <p>
     * Uses measured byte estimates based on Protobuf message analysis:
     * <ul>
     *   <li>Per cell: {@value #BYTES_PER_CELL} bytes (CellState in Java heap)</li>
     *   <li>Per organism: {@value #BYTES_PER_ORGANISM} bytes (OrganismState in Java heap)</li>
     *   <li>Wrapper overhead: {@value #TICKDATA_WRAPPER_OVERHEAD} bytes</li>
     * </ul>
     *
     * @return Estimated bytes per TickData for the occupied cells and max organisms.
     */
    public long estimateBytesPerTick() {
        return estimateEnvironmentBytesPerTick() + estimateOrganismBytesPerTick() + TICKDATA_WRAPPER_OVERHEAD;
    }
    
    /**
     * Calculates bytes for environment cells only (for environment-specific indexers).
     * <p>
     * Each CellState in Java heap consumes approximately {@value #BYTES_PER_CELL} bytes:
     * <ul>
     *   <li>Object header: 12 bytes</li>
     *   <li>GeneratedMessageV3 base: 20 bytes</li>
     *   <li>4 int32 fields: 16 bytes</li>
     *   <li>Alignment: 8 bytes</li>
     * </ul>
     *
     * @return Estimated bytes for all occupied cells.
     */
    public long estimateEnvironmentBytesPerTick() {
        return occupiedCells * BYTES_PER_CELL;
    }
    
    /**
     * Calculates bytes for organisms only (for organism-specific indexers).
     * <p>
     * Each OrganismState in Java heap consumes approximately {@value #BYTES_PER_ORGANISM} bytes
     * due to nested Protobuf messages (Vectors, RegisterValues, ProcFrames, etc.).
     *
     * @return Estimated bytes for all organisms at 100% capacity.
     */
    public long estimateOrganismBytesPerTick() {
        return (long) maxOrganisms * BYTES_PER_ORGANISM;
    }
    
    // ========================================================================
    // Delta Compression Calculations
    // ========================================================================
    
    /**
     * Calculates the number of simulation ticks covered by one chunk.
     * <p>
     * Formula: samplingInterval × samplesPerChunk
     * <p>
     * With defaults (1 × 5 × 20 × 1 = 100), each chunk covers 100 simulation ticks.
     * With samplingInterval=10000: 10000 × 25 = 250,000 simulation ticks per chunk.
     *
     * @return Number of simulation ticks spanned by one chunk.
     */
    public int simulationTicksPerChunk() {
        return samplingInterval * samplesPerChunk();
    }

    /**
     * Calculates the number of recorded data samples per chunk.
     * <p>
     * Formula: accumulatedDeltaInterval × snapshotInterval × chunkInterval
     * <p>
     * This is independent of samplingInterval because samplingInterval only controls
     * how many simulation ticks are skipped between samples, not how many samples
     * exist in a chunk.
     * <p>
     * With defaults (5 × 10 × 1 = 50), each chunk contains 50 samples.
     *
     * @return Number of data samples (snapshots + deltas) per chunk.
     */
    public int samplesPerChunk() {
        return accumulatedDeltaInterval * snapshotInterval * chunkInterval;
    }
    
    /**
     * Calculates the number of snapshots per chunk.
     *
     * @return Number of full snapshots in each chunk.
     */
    public int snapshotsPerChunk() {
        return chunkInterval;
    }
    
    /**
     * Calculates the number of accumulated deltas per chunk.
     * <p>
     * Each chunk has (snapshotInterval - 1) accumulated deltas per snapshot.
     *
     * @return Number of accumulated deltas per chunk.
     */
    public int accumulatedDeltasPerChunk() {
        return chunkInterval * (snapshotInterval - 1);
    }
    
    /**
     * Calculates the number of incremental deltas per chunk.
     * <p>
     * Total deltas = samplesPerChunk - snapshots
     * Accumulated deltas = accumulatedDeltasPerChunk
     * Incremental = Total - Accumulated
     *
     * @return Number of incremental deltas per chunk.
     */
    public int incrementalDeltasPerChunk() {
        int totalDeltas = samplesPerChunk() - snapshotsPerChunk();
        return totalDeltas - accumulatedDeltasPerChunk();
    }
    
    /**
     * Estimates bytes per delta (incremental or accumulated).
     * <p>
     * Uses estimatedDeltaRatio to calculate expected changed cells.
     * At 1% change rate with 1M cells, delta contains ~10,000 cells.
     *
     * @return Estimated bytes per delta.
     */
    public long estimateBytesPerDelta() {
        long changedCells = (long) Math.ceil(occupiedCells * estimatedDeltaRatio);
        return changedCells * BYTES_PER_CELL 
             + (long) maxOrganisms * BYTES_PER_ORGANISM 
             + TICKDATA_WRAPPER_OVERHEAD;
    }
    
    /**
     * Estimates total bytes per chunk in Java heap.
     * <p>
     * Chunk contains:
     * <ul>
     *   <li>1 snapshot (full tick)</li>
     *   <li>N-1 deltas (accumulated + incremental)</li>
     * </ul>
     * <p>
     * Note: This is worst-case for heap, not compressed storage size.
     *
     * @return Estimated bytes per chunk.
     * @throws ArithmeticException if the chunk is so long that its bytes exceed a long; an
     *                             estimate that wrapped would understate the need silently
     */
    public long estimateBytesPerChunk() {
        int numSnapshots = snapshotsPerChunk();
        int numDeltas = samplesPerChunk() - numSnapshots;

        return Math.addExact(Math.multiplyExact((long) numSnapshots, estimateBytesPerTick()),
                             Math.multiplyExact((long) numDeltas, estimateBytesPerDelta()));
    }
    
    /**
     * Estimates the compression ratio achieved by delta compression.
     * <p>
     * Compares chunk size to equivalent number of full snapshots.
     * Higher ratio = better compression.
     *
     * @return Compression ratio (e.g., 10.0 = 10:1 compression).
     */
    public double estimateCompressionRatio() {
        long uncompressedSize = Math.multiplyExact((long) samplesPerChunk(), estimateBytesPerTick());
        long compressedSize = estimateBytesPerChunk();
        if (compressedSize == 0) return 1.0;
        return (double) uncompressedSize / compressedSize;
    }
    
    // ========================================================================
    // Serialized Protobuf Wire Format Estimation
    // ========================================================================

    /**
     * Estimates serialized bytes per TickData in protobuf wire format (snapshot).
     * <p>
     * This estimates the on-wire size of a single serialized TickData message,
     * as opposed to {@link #estimateBytesPerTick()} which estimates Java heap
     * after deserialization. Used for estimating message broker memory overhead.
     *
     * @return Estimated serialized bytes per TickData for the occupied cells.
     */
    public long estimateSerializedBytesPerTick() {
        return occupiedCells * SERIALIZED_BYTES_PER_CELL
             + (long) maxOrganisms * SERIALIZED_BYTES_PER_ORGANISM
             + SERIALIZED_TICKDATA_WRAPPER_OVERHEAD;
    }

    /**
     * Estimates serialized bytes per TickDelta in protobuf wire format.
     * <p>
     * Uses {@link #estimatedDeltaRatio} to calculate expected changed cells.
     * Organisms are always fully serialized in deltas (they change almost entirely every tick).
     *
     * @return Estimated serialized bytes per TickDelta.
     */
    public long estimateSerializedBytesPerDelta() {
        long changedCells = (long) Math.ceil(occupiedCells * estimatedDeltaRatio);
        return changedCells * SERIALIZED_BYTES_PER_CELL
             + (long) maxOrganisms * SERIALIZED_BYTES_PER_ORGANISM
             + SERIALIZED_TICKDATA_WRAPPER_OVERHEAD;
    }

    /**
     * Estimates serialized bytes per TickDataChunk in protobuf wire format.
     * <p>
     * Mirrors {@link #estimateBytesPerChunk()} but uses serialized wire format
     * constants instead of Java heap constants. Used by services that hold
     * serialized messages on heap (e.g., PersistenceService with Artemis).
     *
     * @return Estimated serialized bytes per chunk.
     * @throws ArithmeticException if the chunk is so long that its bytes exceed a long
     */
    public long estimateSerializedBytesPerChunk() {
        int numSnapshots = snapshotsPerChunk();
        int numDeltas = samplesPerChunk() - numSnapshots;

        return Math.addExact(Math.multiplyExact((long) numSnapshots, estimateSerializedBytesPerTick()),
                             Math.multiplyExact((long) numDeltas, estimateSerializedBytesPerDelta()));
    }

    // ========================================================================
    // String Representation
    // ========================================================================
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SimulationParameters[shape=[");
        for (int i = 0; i < environmentShape.length; i++) {
            if (i > 0) sb.append("×");
            sb.append(environmentShape[i]);
        }
        sb.append("], totalCells=").append(totalCells);
        sb.append(", occupiedCells=").append(occupiedCells);
        sb.append(", maxOrganisms=").append(maxOrganisms);
        sb.append(", env=").append(formatBytes(estimateEnvironmentBytesPerTick()));
        sb.append(", org=").append(formatBytes(estimateOrganismBytesPerTick()));
        sb.append(", total=").append(formatBytes(estimateBytesPerTick())).append("/tick]");
        return sb.toString();
    }
    
    /**
     * Formats bytes as human-readable string (KB, MB, GB).
     *
     * @param bytes The byte count.
     * @return Formatted string.
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}

