package org.evochora.datapipeline.resume;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads simulation checkpoints from storage for resume functionality.
 * <p>
 * This class finds the last complete chunk for a simulation run and provides
 * the snapshot needed to reconstruct the simulation state.
 * <p>
 * <b>Resume Algorithm:</b>
 * <ol>
 *   <li>Load SimulationMetadata from storage</li>
 *   <li>Find the last batch file (sorted by tick order)</li>
 *   <li>Read all chunks from the last batch</li>
 *   <li>Return the snapshot from the last chunk</li>
 * </ol>
 * <p>
 * <b>Simplicity:</b> Resuming always happens from the start of a chunk (snapshot).
 * This ensures:
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
     * The checkpoint is always based on the snapshot from the last complete chunk.
     * This ensures deterministic resume without truncation complexity.
     *
     * @param runId The simulation run ID to resume
     * @return ResumeCheckpoint containing all data needed for resume
     * @throws ResumeException if no valid checkpoint exists
     * @throws IOException if storage access fails
     */
    public ResumeCheckpoint loadLatestCheckpoint(String runId) throws IOException {
        // 1. Load metadata
        Optional<StoragePath> metadataPath = storageRead.findMetadataPath(runId);
        if (metadataPath.isEmpty()) {
            throw new ResumeException("Metadata not found for run: " + runId);
        }
        SimulationMetadata metadata = storageRead.readMessage(
            metadataPath.get(), SimulationMetadata.parser());

        // Validate run ID matches
        if (!metadata.getSimulationRunId().equals(runId)) {
            throw new ResumeException(String.format(
                "Run ID mismatch: requested '%s' but metadata contains '%s'",
                runId, metadata.getSimulationRunId()));
        }

        log.debug("Loaded metadata for run: {}", runId);

        // 2. Find last batch file
        Optional<StoragePath> lastBatchOpt = storageRead.findLastBatchFile(runId + "/raw/");
        if (lastBatchOpt.isEmpty()) {
            throw new ResumeException("No tick data found for run: " + runId);
        }

        StoragePath lastBatchPath = lastBatchOpt.get();
        log.debug("Found last batch file: {}", lastBatchPath);

        // 3. Read all chunks from last batch
        List<TickDataChunk> chunks = storageRead.readChunkBatch(lastBatchPath);
        if (chunks.isEmpty()) {
            throw new ResumeException("Empty batch file: " + lastBatchPath);
        }

        // 4. Get the last chunk's snapshot
        TickDataChunk lastChunk = chunks.get(chunks.size() - 1);
        TickData snapshot = lastChunk.getSnapshot();

        log.debug("Read {} chunks from batch, using snapshot from tick {}",
            chunks.size(), snapshot.getTickNumber());

        log.info("Resume checkpoint: tick {} (from snapshot)",
            snapshot.getTickNumber());

        return new ResumeCheckpoint(metadata, snapshot);
    }
}
