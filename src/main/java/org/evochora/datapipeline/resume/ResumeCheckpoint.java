package org.evochora.datapipeline.resume;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;

/**
 * Contains all data needed to resume a simulation from a checkpoint.
 * <p>
 * This record encapsulates the checkpoint data loaded by {@link SnapshotLoader}:
 * <ul>
 *   <li><b>metadata</b> - Complete simulation configuration and program artifacts</li>
 *   <li><b>snapshot</b> - The full state snapshot from the beginning of the chunk</li>
 *   <li><b>accumulatedDelta</b> - The last accumulated delta (if available), which contains
 *       all changes since the snapshot and the RNG state at that tick</li>
 * </ul>
 * <p>
 * <b>State Reconstruction:</b>
 * <ul>
 *   <li>If {@link #hasAccumulatedDelta()} returns true: Apply snapshot cells, then apply
 *       accumulated delta changes. Use accumulated delta's RNG state and organism states.</li>
 *   <li>If {@link #hasAccumulatedDelta()} returns false: Use snapshot only. More tick loss
 *       (between snapshot and crash point), but still deterministically resumable.</li>
 * </ul>
 *
 * @param metadata Complete simulation metadata (config, programs, tick plugins, etc.)
 * @param snapshot The snapshot TickData from the beginning of the chunk
 * @param accumulatedDelta The last accumulated delta, or null if resuming from snapshot only
 */
public record ResumeCheckpoint(
    SimulationMetadata metadata,
    TickData snapshot,
    TickDelta accumulatedDelta  // nullable - may be null if resuming from snapshot only
) {

    /**
     * Returns the tick number to resume FROM (i.e., the first tick to generate).
     * <p>
     * If accumulated delta exists: accumulated delta tick + 1
     * If no accumulated delta: snapshot tick + 1
     *
     * @return The tick number where simulation should resume
     */
    public long getResumeFromTick() {
        if (accumulatedDelta != null) {
            return accumulatedDelta.getTickNumber() + 1;
        }
        return snapshot.getTickNumber() + 1;
    }

    /**
     * Returns the tick number of the last valid state (the checkpoint tick).
     * <p>
     * This is the tick number of the state that will be reconstructed.
     *
     * @return The tick number of the checkpoint state
     */
    public long getCheckpointTick() {
        if (accumulatedDelta != null) {
            return accumulatedDelta.getTickNumber();
        }
        return snapshot.getTickNumber();
    }

    /**
     * Returns true if resuming from accumulated delta (preferred),
     * false if falling back to snapshot only.
     * <p>
     * Resuming from accumulated delta loses fewer ticks because the
     * accumulated delta captures state closer to the crash point.
     *
     * @return true if accumulated delta is available
     */
    public boolean hasAccumulatedDelta() {
        return accumulatedDelta != null;
    }
}
