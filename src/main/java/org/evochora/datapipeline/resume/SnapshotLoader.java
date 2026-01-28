package org.evochora.datapipeline.resume;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.evochora.datapipeline.api.contracts.DeltaType;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead.SortOrder;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads simulation checkpoints from storage for resume functionality.
 * <p>
 * This class finds the last usable checkpoint for a simulation run,
 * truncates the last chunk to remove ticks after the resume point,
 * and provides all data needed to reconstruct the simulation state.
 * <p>
 * <b>Resume Algorithm:</b>
 * <ol>
 *   <li>Load SimulationMetadata from storage</li>
 *   <li>Find the last batch file (sorted by tick order)</li>
 *   <li>Read all chunks from the last batch</li>
 *   <li>Find the last Accumulated Delta in the last chunk (or fall back to snapshot)</li>
 *   <li>If needed, truncate the chunk to remove ticks after the resume point</li>
 *   <li>Return the checkpoint data for state reconstruction</li>
 * </ol>
 * <p>
 * <b>Truncation:</b> When resuming from an accumulated delta, there may be ticks
 * after that delta in the same chunk. These are removed to prevent overlapping data
 * when the simulation continues. The truncated chunk is written with a new filename,
 * and the original is moved to a superseded folder.
 * <p>
 * <b>Crash Safety:</b> If the process crashes between writing the truncated chunk
 * and moving the original, the storage layer's deduplication logic (in
 * {@code listBatchFiles()}) will automatically prefer the file with the smaller
 * lastTick (the truncated one).
 */
public class SnapshotLoader {

    private static final Logger log = LoggerFactory.getLogger(SnapshotLoader.class);

    private final IBatchStorageRead storageRead;
    private final IBatchStorageWrite storageWrite;

    /**
     * Creates a SnapshotLoader with the given storage resources.
     *
     * @param storageRead Storage resource for reading checkpoints
     * @param storageWrite Storage resource for writing truncated chunks
     */
    public SnapshotLoader(IBatchStorageRead storageRead, IBatchStorageWrite storageWrite) {
        this.storageRead = storageRead;
        this.storageWrite = storageWrite;
    }

    /**
     * Loads the latest checkpoint for the given simulation run.
     * <p>
     * This method also truncates the last chunk if necessary to remove any ticks
     * after the resume point, preventing overlapping data when the simulation continues.
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

        log.info("Loaded metadata for run: {}", runId);

        // 2. Find last batch file
        // Note: listBatchFiles() already handles deduplication for crash recovery
        // Use DESCENDING order with limit=1 to efficiently get only the last batch file
        BatchFileListResult batches = storageRead.listBatchFiles(
            runId + "/raw/", null, 1, SortOrder.DESCENDING);
        if (batches.getFilenames().isEmpty()) {
            throw new ResumeException("No tick data found for run: " + runId);
        }

        StoragePath lastBatchPath = batches.getFilenames().get(0);
        log.info("Found last batch file: {}", lastBatchPath);

        // 3. Read all chunks from last batch
        List<TickDataChunk> chunks = storageRead.readChunkBatch(lastBatchPath);
        if (chunks.isEmpty()) {
            throw new ResumeException("Empty batch file: " + lastBatchPath);
        }
        TickDataChunk lastChunk = chunks.get(chunks.size() - 1);

        log.info("Read {} chunks from batch, last chunk: ticks {}-{}",
            chunks.size(), lastChunk.getFirstTick(), lastChunk.getLastTick());

        // 4. Find last accumulated delta
        TickDelta lastAccumulatedDelta = null;
        for (TickDelta delta : lastChunk.getDeltasList()) {
            if (delta.getDeltaType() == DeltaType.ACCUMULATED) {
                lastAccumulatedDelta = delta;
            }
        }

        // Note: lastAccumulatedDelta may be null - that's OK, we fall back to snapshot

        // 5. Determine resume point tick (from accumulated delta or snapshot)
        long resumePointTick = (lastAccumulatedDelta != null)
            ? lastAccumulatedDelta.getTickNumber()
            : lastChunk.getSnapshot().getTickNumber();

        // 6. Check if truncation is needed (only if there are ticks after resume point)
        boolean needsTruncation = lastChunk.getLastTick() > resumePointTick;

        if (needsTruncation) {
            log.info("Truncating last chunk: removing ticks {} to {} (resume point: {})",
                resumePointTick + 1, lastChunk.getLastTick(), resumePointTick);

            // 6a. Truncate the last chunk
            TickDataChunk truncatedChunk = truncateChunk(lastChunk, resumePointTick);

            // 6b. Handle earlier chunks in same batch - keep only those before resume point
            List<TickDataChunk> truncatedBatch = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                TickDataChunk chunk = chunks.get(i);
                if (chunk == lastChunk) {
                    truncatedBatch.add(truncatedChunk);
                } else if (chunk.getLastTick() <= resumePointTick) {
                    truncatedBatch.add(chunk);  // Keep unchanged
                }
                // Chunks entirely after resume point are dropped
            }

            // 6c. Write truncated batch (different filename due to different lastTick)
            long newFirstTick = truncatedBatch.get(0).getFirstTick();
            long newLastTick = truncatedBatch.get(truncatedBatch.size() - 1).getLastTick();
            StoragePath truncatedPath = storageWrite.writeChunkBatch(
                truncatedBatch, newFirstTick, newLastTick);
            log.info("Wrote truncated batch: {} (ticks {}-{})",
                truncatedPath, newFirstTick, newLastTick);

            // 6d. Move original to superseded (if crash before this, load-time
            //     heuristic will prefer truncated file with smaller lastTick)
            storageWrite.moveToSuperseded(lastBatchPath);
            log.info("Moved original batch to superseded: {}", lastBatchPath);

            // Use truncated chunk for state reconstruction
            lastChunk = truncatedChunk;
        }

        // Log resume point info
        if (lastAccumulatedDelta != null) {
            log.info("Resuming from accumulated delta at tick {}", resumePointTick);
        } else {
            log.info("No accumulated delta found, resuming from snapshot at tick {}",
                resumePointTick);
        }

        return new ResumeCheckpoint(
            metadata,
            lastChunk.getSnapshot(),
            lastAccumulatedDelta
        );
    }

    /**
     * Truncates a chunk to remove all deltas after the specified tick.
     * <p>
     * The snapshot is always kept (it's the foundation of the chunk).
     * Only deltas with tick numbers greater than {@code lastValidTick} are removed.
     *
     * @param chunk The original chunk to truncate
     * @param lastValidTick The last tick to keep (inclusive)
     * @return A new chunk with deltas trimmed to the specified tick
     */
    private TickDataChunk truncateChunk(TickDataChunk chunk, long lastValidTick) {
        // Keep snapshot (always first tick in chunk)
        TickData snapshot = chunk.getSnapshot();

        // Filter deltas: keep only those <= lastValidTick
        List<TickDelta> validDeltas = chunk.getDeltasList().stream()
            .filter(delta -> delta.getTickNumber() <= lastValidTick)
            .collect(Collectors.toList());

        // Calculate new tick count: 1 (snapshot) + number of valid deltas
        int newTickCount = 1 + validDeltas.size();

        return TickDataChunk.newBuilder()
            .setSimulationRunId(chunk.getSimulationRunId())
            .setFirstTick(chunk.getFirstTick())
            .setLastTick(lastValidTick)
            .setTickCount(newTickCount)
            .setSnapshot(snapshot)
            .addAllDeltas(validDeltas)
            .build();
    }
}
