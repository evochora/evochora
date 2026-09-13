package org.evochora.datapipeline.resume;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Predicate;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.storage.ChunkFieldFilter;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads simulation checkpoints from storage for resume functionality.
 * <p>
 * A checkpoint is the snapshot of one recorded chunk together with the run's metadata. Which
 * chunk that is depends on the caller:
 * <ul>
 *   <li>{@link #loadLatestCheckpoint(String)} takes the last chunk of the last batch file, the
 *       point a run is continued from</li>
 *   <li>{@link #loadCheckpointContaining(String, long)} takes the chunk whose tick range covers
 *       a chosen tick, the point a recorded window is replayed from</li>
 * </ul>
 * <p>
 * Both read the chunks with {@link ChunkFieldFilter#SNAPSHOT_ONLY}, so the delta bytes of a batch
 * are discarded at the wire level instead of being turned into objects.
 * <p>
 * <b>Simplicity:</b> A chunk's snapshot is the state at the chunk's first tick, so a checkpoint
 * always lies on a chunk boundary. This ensures:
 * <ul>
 *   <li>No truncation needed (chunks are atomic units)</li>
 *   <li>No superseded file handling needed</li>
 *   <li>Chunk boundaries are always aligned after resume</li>
 *   <li>No gaps possible between storage and database</li>
 * </ul>
 */
public class SnapshotLoader {

    private static final Logger log = LoggerFactory.getLogger(SnapshotLoader.class);

    private final IBatchStorageRead storageRead;

    /**
     * Creates a SnapshotLoader with the given storage resource.
     *
     * @param storageRead Storage resource for reading checkpoints
     */
    public SnapshotLoader(IBatchStorageRead storageRead) {
        this.storageRead = storageRead;
    }

    /**
     * Loads the latest checkpoint for the given simulation run.
     * <p>
     * The checkpoint is based on the snapshot of the last chunk in the last batch file, which is
     * the latest state the run persisted completely.
     *
     * @param runId The simulation run ID to resume
     * @return ResumeCheckpoint containing all data needed for resume
     * @throws ResumeException if no valid checkpoint exists
     * @throws IOException if storage access fails
     */
    public ResumeCheckpoint loadLatestCheckpoint(String runId) throws IOException {
        SimulationMetadata metadata = loadMetadata(runId);

        Optional<StoragePath> lastBatchOpt = storageRead.findLastBatchFile(runId + "/raw/");
        if (lastBatchOpt.isEmpty()) {
            throw new ResumeException("No tick data found for run: " + runId);
        }

        StoragePath lastBatchPath = lastBatchOpt.get();
        log.debug("Found last batch file: {}", lastBatchPath);

        TickData[] last = new TickData[1];
        forEachSnapshotChunk(lastBatchPath, chunk -> {
            last[0] = chunk.getSnapshot();
            return true;
        });
        if (last[0] == null) {
            throw new IOException("Empty batch file: " + lastBatchPath);
        }
        TickData snapshot = last[0];

        log.info("Resume checkpoint: tick {} (from snapshot)", snapshot.getTickNumber());

        return new ResumeCheckpoint(metadata, snapshot);
    }

    /**
     * Loads the checkpoint of the chunk that covers the given tick.
     * <p>
     * The checkpoint is based on the snapshot of that chunk, which is the state at the chunk's
     * first tick — at or before the requested tick, never after it.
     *
     * @param runId The simulation run ID to read
     * @param tick The tick the chunk must cover
     * @return ResumeCheckpoint with the run's metadata and the covering chunk's snapshot
     * @throws ResumeException if the run has no metadata, no tick data, or no chunk covering the tick
     * @throws IOException if storage access fails
     */
    public ResumeCheckpoint loadCheckpointContaining(String runId, long tick) throws IOException {
        SimulationMetadata metadata = loadMetadata(runId);

        Optional<StoragePath> batchOpt = storageRead.findBatchFileContaining(runId + "/raw/", tick);
        TickData snapshot = null;
        if (batchOpt.isPresent()) {
            StoragePath batchPath = batchOpt.get();
            log.debug("Found batch file covering tick {}: {}", tick, batchPath);

            TickData[] covering = new TickData[1];
            forEachSnapshotChunk(batchPath, chunk -> {
                if (tick >= chunk.getFirstTick() && tick <= chunk.getLastTick()) {
                    covering[0] = chunk.getSnapshot();
                    return false;
                }
                return true;
            });
            snapshot = covering[0];
        }

        if (snapshot == null) {
            throw new ResumeException(describeMissingTick(runId, tick));
        }

        log.info("Checkpoint for tick {}: snapshot at tick {}", tick, snapshot.getTickNumber());

        return new ResumeCheckpoint(metadata, snapshot);
    }

    /**
     * Loads and validates the metadata of a run.
     *
     * @param runId The simulation run ID the metadata must belong to
     * @return The run's metadata
     * @throws ResumeException if no metadata exists or it belongs to another run
     * @throws IOException if storage access fails
     */
    private SimulationMetadata loadMetadata(String runId) throws IOException {
        Optional<StoragePath> metadataPath = storageRead.findMetadataPath(runId);
        if (metadataPath.isEmpty()) {
            throw new ResumeException("Metadata not found for run: " + runId);
        }
        SimulationMetadata metadata = storageRead.readMessage(
            metadataPath.get(), SimulationMetadata.parser());

        if (!metadata.getSimulationRunId().equals(runId)) {
            throw new ResumeException(String.format(
                "Run ID mismatch: requested '%s' but metadata contains '%s'",
                runId, metadata.getSimulationRunId()));
        }

        log.debug("Loaded metadata for run: {}", runId);
        return metadata;
    }

    /**
     * Builds the message for a tick that no chunk of the run covers, naming where the recorded
     * data ends so that a caller can see how far the run reaches.
     *
     * @param runId The simulation run ID that was searched
     * @param tick The tick that was requested
     * @return The message describing the miss
     * @throws IOException if storage access fails
     */
    private String describeMissingTick(String runId, long tick) throws IOException {
        Optional<StoragePath> lastBatchOpt = storageRead.findLastBatchFile(runId + "/raw/");
        if (lastBatchOpt.isEmpty()) {
            return "No tick data found for run: " + runId;
        }

        long[] lastCoveredTick = {-1};
        forEachSnapshotChunk(lastBatchOpt.get(), chunk -> {
            lastCoveredTick[0] = chunk.getLastTick();
            return true;
        });

        return String.format(
            "No chunk covering tick %d in run '%s': the recorded data ends at tick %d",
            tick, runId, lastCoveredTick[0]);
    }

    /**
     * Passes the chunks of a batch file to a visitor, snapshots only, until the visitor stops.
     * <p>
     * Stopping matters for the reading side: every chunk the visitor accepts costs the
     * deserialization of a full snapshot, so a search ends at the chunk it was looking for.
     *
     * @param batchPath The batch file to read
     * @param visitor Receives each chunk in file order and returns {@code false} to stop
     * @throws IOException if storage access or parsing fails
     */
    private void forEachSnapshotChunk(StoragePath batchPath, Predicate<TickDataChunk> visitor) throws IOException {
        class EarlyExit extends RuntimeException {
            EarlyExit() {
                super(null, null, false, false);
            }
        }

        try {
            storageRead.forEachChunk(batchPath, ChunkFieldFilter.SNAPSHOT_ONLY, chunk -> {
                if (!visitor.test(chunk)) {
                    throw new EarlyExit();
                }
            });
        } catch (EarlyExit e) {
            // The visitor found what it was looking for - normal control flow
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to read snapshot from: " + batchPath, e);
        }
    }
}
