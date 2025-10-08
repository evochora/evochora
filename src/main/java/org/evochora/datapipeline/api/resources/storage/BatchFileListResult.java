package org.evochora.datapipeline.api.resources.storage;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of a paginated batch file listing operation.
 * <p>
 * This immutable class represents one page of results from {@link IBatchStorageRead#listBatchFiles}.
 * It supports S3-style pagination where clients can iterate through large result sets without
 * loading all filenames into memory at once.
 * <p>
 * Example usage:
 * <pre>
 * String token = null;
 * do {
 *     BatchFileListResult result = storage.listBatchFiles("sim123/", token, 1000);
 *     for (String filename : result.getFilenames()) {
 *         // Process file
 *     }
 *     token = result.getNextContinuationToken();
 * } while (result.isTruncated());
 * </pre>
 */
public class BatchFileListResult {

    private final List<String> filenames;
    private final String nextContinuationToken;
    private final boolean truncated;

    /**
     * Constructs a BatchFileListResult.
     *
     * @param filenames List of batch filenames (relative paths from storage root)
     * @param nextContinuationToken Token for fetching next page, or null if no more results
     * @param truncated true if more results exist beyond this page
     * @throws IllegalArgumentException if filenames is null
     */
    public BatchFileListResult(List<String> filenames, String nextContinuationToken, boolean truncated) {
        if (filenames == null) {
            throw new IllegalArgumentException("filenames cannot be null");
        }
        this.filenames = Collections.unmodifiableList(filenames);
        this.nextContinuationToken = nextContinuationToken;
        this.truncated = truncated;
    }

    /**
     * Returns the list of batch filenames in this page.
     * <p>
     * Filenames are relative paths from the storage root, suitable for passing to
     * {@link IBatchStorageRead#readBatch(String)}.
     * <p>
     * Example: "sim123/000/000/batch_0000000000_0000000999.pb.zst"
     *
     * @return Immutable list of filenames (never null, may be empty)
     */
    public List<String> getFilenames() {
        return filenames;
    }

    /**
     * Returns the continuation token for fetching the next page.
     * <p>
     * Pass this value to the next call to {@link IBatchStorageRead#listBatchFiles} to
     * continue iteration. If null, there are no more results.
     *
     * @return Continuation token, or null if this is the last page
     */
    public String getNextContinuationToken() {
        return nextContinuationToken;
    }

    /**
     * Indicates whether more results exist beyond this page.
     * <p>
     * If true, call {@link IBatchStorageRead#listBatchFiles} again with
     * {@link #getNextContinuationToken()} to fetch the next page.
     *
     * @return true if more results exist, false if this is the last page
     */
    public boolean isTruncated() {
        return truncated;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchFileListResult that = (BatchFileListResult) o;
        return truncated == that.truncated &&
               filenames.equals(that.filenames) &&
               Objects.equals(nextContinuationToken, that.nextContinuationToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filenames, nextContinuationToken, truncated);
    }

    @Override
    public String toString() {
        return String.format(
            "BatchFileListResult{files=%d, truncated=%s, nextToken=%s}",
            filenames.size(), truncated, nextContinuationToken != null ? "present" : "null"
        );
    }
}