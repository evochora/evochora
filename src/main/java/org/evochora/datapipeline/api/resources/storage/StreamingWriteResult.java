package org.evochora.datapipeline.api.resources.storage;

/**
 * Result of a streaming batch write operation.
 * <p>
 * Contains all metadata derived during the streaming write: the simulation run ID,
 * the physical storage path (including compression extension), the tick range,
 * chunk count, total tick count, and compressed byte count.
 * This information is needed by callers (e.g., PersistenceService) for batch notifications
 * and metrics without requiring a second pass over the data.
 *
 * @param path            the physical storage path where the batch was written
 * @param simulationRunId the simulation run ID (from the first chunk)
 * @param firstTick       the first tick number in the batch (from the first chunk)
 * @param lastTick        the last tick number in the batch (from the last chunk)
 * @param chunkCount      the number of chunks written
 * @param totalTickCount  the sum of tick counts across all chunks
 * @param bytesWritten    the number of compressed bytes written to storage
 */
public record StreamingWriteResult(
    StoragePath path,
    String simulationRunId,
    long firstTick,
    long lastTick,
    int chunkCount,
    int totalTickCount,
    long bytesWritten
) {
    /**
     * Returns the total number of ticks covered by this batch.
     *
     * @return {@code lastTick - firstTick + 1}
     */
    public long tickRange() {
        return lastTick - firstTick + 1;
    }
}
