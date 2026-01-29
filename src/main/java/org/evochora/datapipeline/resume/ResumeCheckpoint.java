package org.evochora.datapipeline.resume;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;

/**
 * Contains all data needed to resume a simulation from a checkpoint.
 * <p>
 * This record encapsulates the checkpoint data loaded by {@link SnapshotLoader}:
 * <ul>
 *   <li><b>metadata</b> - Complete simulation configuration and program artifacts</li>
 *   <li><b>snapshot</b> - The full state snapshot from the last complete chunk</li>
 * </ul>
 * <p>
 * <b>State Reconstruction:</b>
 * The simulation is always resumed from a snapshot, which is the first tick of a chunk.
 * Snapshots contain complete state including RNG state, making resume deterministic.
 * <p>
 * This approach is simpler than resuming from accumulated deltas because:
 * <ul>
 *   <li>No truncation logic needed</li>
 *   <li>No superseded file handling needed</li>
 *   <li>Chunk boundaries are always aligned</li>
 *   <li>No gaps possible between storage and database</li>
 * </ul>
 *
 * @param metadata Complete simulation metadata (config, programs, tick plugins, etc.)
 * @param snapshot The snapshot TickData from the last complete chunk
 */
public record ResumeCheckpoint(
    SimulationMetadata metadata,
    TickData snapshot
) {

    /**
     * Returns the tick number to resume FROM (i.e., the first tick to generate).
     * <p>
     * This is always snapshot tick + 1, since the snapshot represents the last
     * fully persisted state.
     *
     * @return The tick number where simulation should resume
     */
    public long getResumeFromTick() {
        return snapshot.getTickNumber() + 1;
    }

    /**
     * Returns the tick number of the checkpoint state.
     * <p>
     * This is the tick number of the snapshot that will be used for reconstruction.
     *
     * @return The tick number of the checkpoint state
     */
    public long getCheckpointTick() {
        return snapshot.getTickNumber();
    }
}
