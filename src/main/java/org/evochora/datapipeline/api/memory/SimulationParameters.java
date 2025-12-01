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
     * Calculates an estimate for bytes per tick at worst-case.
     * <p>
     * Uses conservative byte estimates:
     * <ul>
     *   <li>Per cell: ~20 bytes (position, energy, organism ID)</li>
     *   <li>Per organism: ~500 bytes (registers, stacks, code, position)</li>
     * </ul>
     *
     * @return Estimated bytes per tick at 100% occupancy.
     */
    public long estimateBytesPerTick() {
        long cellBytes = (long) totalCells * 20;       // ~20 bytes per cell
        long organismBytes = (long) maxOrganisms * 500; // ~500 bytes per organism
        return cellBytes + organismBytes;
    }
    
    /**
     * Calculates bytes for environment cells only (for environment-specific indexers).
     * <p>
     * Environment cells are stored as compressed BLOBs. Each cell in the BLOB
     * contains approximately 100 bytes (position, energy, content type, flags).
     *
     * @return Estimated bytes for all cells at 100% occupancy.
     */
    public long estimateEnvironmentBytesPerTick() {
        // ~100 bytes per cell for full cell state (including metadata)
        return (long) totalCells * 100;
    }
    
    /**
     * Calculates bytes for organisms only (for organism-specific indexers).
     * <p>
     * Organism state includes static data (code, birth info) and per-tick
     * runtime state (registers, stacks, energy, position).
     *
     * @return Estimated bytes for all organisms at 100% capacity.
     */
    public long estimateOrganismBytesPerTick() {
        // ~500 bytes per organism for full runtime state
        return (long) maxOrganisms * 500;
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
        sb.append(", ~").append(formatBytes(estimateBytesPerTick())).append("/tick]");
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

