package org.evochora.datapipeline.api.memory;

/**
 * Parameters needed for memory estimation across all pipeline components.
 * <p>
 * All estimates use <strong>WORST-CASE assumptions</strong>:
 * <ul>
 *   <li>Environment: 100% cell occupancy (all cells filled with organisms/energy)</li>
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
 *   <li>{@link #BYTES_PER_ORGANISM}: 800 bytes - OrganismState with nested Vectors, RegisterValues, stacks</li>
 *   <li>{@link #TICKDATA_WRAPPER_OVERHEAD}: 500 bytes - TickData wrapper (simulation_run_id, rng_state, etc.)</li>
 * </ul>
 * <p>
 * <strong>Usage:</strong> Created by ServiceManager from pipeline configuration
 * and passed to all {@link IMemoryEstimatable} components for estimation.
 *
 * @param environmentShape The environment dimensions (e.g., [800, 600] for 2D, [100, 100, 50] for 3D).
 *                          Used to calculate total cells.
 * @param totalCells Total cells in environment = product of shape dimensions.
 *                   For [800, 600] = 480,000 cells. This is the WORST-CASE cell count
 *                   (100% occupancy assumed for all estimations).
 * @param maxOrganisms Maximum expected organisms in the simulation.
 *                     This should be a configured upper bound or a reasonable estimate.
 *                     For worst-case: assume all organisms alive simultaneously.
 */
public record SimulationParameters(
    int[] environmentShape,
    int totalCells,
    int maxOrganisms
) {
    
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
     * Breakdown:
     * <ul>
     *   <li>Object header + base fields: 80 bytes</li>
     *   <li>Strings (program_id, failure_reason): 60 bytes</li>
     *   <li>7 Vector messages (ip, initial_position, dv, ip_before_fetch, dv_before_fetch, etc.): 280 bytes</li>
     *   <li>RegisterValue lists (data_registers, procedure_registers, formal_param_registers): 120 bytes</li>
     *   <li>Vector lists (location_registers, data_pointers): 80 bytes</li>
     *   <li>Stacks (data_stack, location_stack, call_stack): 120 bytes</li>
     *   <li>Maps (instruction_register_values_before): 60 bytes</li>
     * </ul>
     * Total: ~800 bytes per OrganismState
     */
    public static final int BYTES_PER_ORGANISM = 800;
    
    /**
     * Overhead bytes for TickData wrapper (excluding cells and organisms).
     * <p>
     * Includes: simulation_run_id (String), tick_number, capture_time_ms,
     * rng_state (bytes), strategy_states (list), total_organisms_created.
     */
    public static final int TICKDATA_WRAPPER_OVERHEAD = 500;
    
    /**
     * Creates SimulationParameters from environment shape and max organisms.
     * <p>
     * Automatically calculates totalCells from shape dimensions.
     *
     * @param environmentShape The environment dimensions (e.g., [800, 600]).
     * @param maxOrganisms Maximum expected organisms.
     * @return SimulationParameters with calculated totalCells.
     */
    public static SimulationParameters of(int[] environmentShape, int maxOrganisms) {
        int totalCells = 1;
        for (int dim : environmentShape) {
            totalCells *= dim;
        }
        return new SimulationParameters(environmentShape, totalCells, maxOrganisms);
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
    public int worstCaseCellsPerTick() {
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
     * @return Estimated bytes per TickData at 100% environment occupancy and max organisms.
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
     * @return Estimated bytes for all cells at 100% occupancy.
     */
    public long estimateEnvironmentBytesPerTick() {
        return (long) totalCells * BYTES_PER_CELL;
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
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SimulationParameters[shape=[");
        for (int i = 0; i < environmentShape.length; i++) {
            if (i > 0) sb.append("×");
            sb.append(environmentShape[i]);
        }
        sb.append("], totalCells=").append(totalCells);
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

