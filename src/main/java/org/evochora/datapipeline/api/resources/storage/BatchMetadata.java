package org.evochora.datapipeline.api.resources.storage;

import java.time.Instant;
import java.util.Objects;

/**
 * Metadata about a stored batch file.
 * <p>
 * This immutable record represents information about a single batch file, parsed from
 * manifest entries. It provides all necessary information for indexers to decide whether
 * to read a batch without needing to access the batch file itself.
 * <p>
 * Instances are created by storage implementations when reading manifests or after
 * writing batches.
 */
public class BatchMetadata {

    /**
     * Relative filename from simulation root.
     * <p>
     * Example: "001/234/batch_0012340000_0012340999.pb.zst"
     * <p>
     * This path can be passed directly to {@link IBatchStorageResource#readBatch(String)}
     * to retrieve the batch contents.
     */
    public final String filename;

    /**
     * First tick number in the batch (inclusive).
     * <p>
     * Combined with lastTick, this defines the tick range covered by this batch.
     * Indexers use this to determine if the batch overlaps their query range.
     */
    public final long firstTick;

    /**
     * Last tick number in the batch (inclusive).
     * <p>
     * Note: This is the actual last tick in the batch, which may be less than
     * firstTick + batchSize if the batch was incomplete (e.g., at shutdown).
     */
    public final long lastTick;

    /**
     * Number of TickData records in the batch.
     * <p>
     * This is typically lastTick - firstTick + 1, but may differ if sampling
     * is used or if some ticks were filtered out.
     */
    public final int recordCount;

    /**
     * Compressed size of the batch file in bytes.
     * <p>
     * This is the actual on-disk size, useful for monitoring storage usage
     * and estimating read times.
     */
    public final long compressedSize;

    /**
     * Timestamp when the batch was written.
     * <p>
     * Useful for debugging, monitoring, and determining data freshness.
     * This is the time when the batch file was committed, not the simulation time.
     */
    public final Instant createdAt;

    /**
     * Constructs a BatchMetadata instance.
     *
     * @param filename The relative filename from simulation root
     * @param firstTick The first tick in the batch
     * @param lastTick The last tick in the batch
     * @param recordCount The number of records in the batch
     * @param compressedSize The compressed file size in bytes
     * @param createdAt The timestamp when the batch was written
     * @throws IllegalArgumentException if filename is null/empty, firstTick > lastTick,
     *                                  recordCount < 0, or compressedSize < 0
     */
    public BatchMetadata(String filename, long firstTick, long lastTick,
                         int recordCount, long compressedSize, Instant createdAt) {
        if (filename == null || filename.isEmpty()) {
            throw new IllegalArgumentException("filename cannot be null or empty");
        }
        if (firstTick > lastTick) {
            throw new IllegalArgumentException(
                String.format("firstTick (%d) cannot be greater than lastTick (%d)", firstTick, lastTick)
            );
        }
        if (recordCount < 0) {
            throw new IllegalArgumentException("recordCount cannot be negative");
        }
        if (compressedSize < 0) {
            throw new IllegalArgumentException("compressedSize cannot be negative");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt cannot be null");
        }

        this.filename = filename;
        this.firstTick = firstTick;
        this.lastTick = lastTick;
        this.recordCount = recordCount;
        this.compressedSize = compressedSize;
        this.createdAt = createdAt;
    }

    /**
     * Checks if this batch overlaps with the given tick range.
     *
     * @param queryStart The start of the query range (inclusive)
     * @param queryEnd The end of the query range (inclusive)
     * @return true if this batch overlaps [queryStart, queryEnd]
     */
    public boolean overlaps(long queryStart, long queryEnd) {
        return this.firstTick <= queryEnd && this.lastTick >= queryStart;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchMetadata that = (BatchMetadata) o;
        return firstTick == that.firstTick &&
               lastTick == that.lastTick &&
               recordCount == that.recordCount &&
               compressedSize == that.compressedSize &&
               filename.equals(that.filename) &&
               createdAt.equals(that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filename, firstTick, lastTick, recordCount, compressedSize, createdAt);
    }

    @Override
    public String toString() {
        return String.format(
            "BatchMetadata{filename='%s', ticks=[%d-%d], records=%d, size=%d bytes, created=%s}",
            filename, firstTick, lastTick, recordCount, compressedSize, createdAt
        );
    }
}
