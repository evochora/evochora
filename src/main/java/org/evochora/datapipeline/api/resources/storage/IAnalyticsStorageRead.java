package org.evochora.datapipeline.api.resources.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;

/**
 * Capability for reading generic analysis artifacts.
 * Used by AnalyticsController to serve files via HTTP.
 * <p>
 * Note: Typically used directly without wrapper monitoring, as HTTP layer
 * handles metrics.
 * <p>
 * <strong>Thread Safety:</strong> Implementations MUST be thread-safe.
 */
public interface IAnalyticsStorageRead extends IResource, IMonitorable {
    /**
     * Opens an input stream to read an analysis artifact.
     *
     * @param runId The simulation run ID
     * @param path Relative path (e.g. "population/lod0/batch_001.parquet")
     * @return Stream to read data from. Caller must close it.
     * @throws IOException If file not found or readable.
     */
    InputStream openAnalyticsInputStream(String runId, String path) throws IOException;

    /**
     * Lists all analytics artifacts matching a prefix.
     *
     * @param runId The simulation run ID
     * @param prefix Path prefix (e.g. "population/") or empty for root.
     * @return List of relative paths.
     * @throws IOException If storage access fails.
     */
    List<String> listAnalyticsFiles(String runId, String prefix) throws IOException;

    /**
     * Lists analytics artifacts matching a prefix, filtered by tick range.
     * <p>
     * Only includes batch Parquet files whose tick range overlaps with
     * {@code [tickFrom, tickTo]}. Non-batch files (e.g. metadata.json) are always included.
     *
     * @param runId    The simulation run ID
     * @param prefix   Path prefix (e.g. "population/lod0/")
     * @param tickFrom Minimum tick (inclusive), or null for no lower bound
     * @param tickTo   Maximum tick (inclusive), or null for no upper bound
     * @return Filtered list of relative paths.
     * @throws IOException If storage access fails.
     */
    List<String> listAnalyticsFiles(String runId, String prefix, Long tickFrom, Long tickTo) throws IOException;

    /**
     * Returns the total tick range across all Parquet batch files matching a prefix.
     * <p>
     * Determines the range purely from filenames (no file content I/O).
     *
     * @param runId  The simulation run ID
     * @param prefix Path prefix (e.g. "population/lod0/")
     * @return {@code long[2] = {minTick, maxTick}}, or null if no batch files found
     * @throws IOException If storage access fails.
     */
    long[] getAnalyticsTickRange(String runId, String prefix) throws IOException;

    /**
     * Lists all simulation run IDs that have analytics data.
     * <p>
     * Only returns runs that have an analytics subdirectory with content.
     * Results are sorted by timestamp (newest first).
     *
     * @return List of run IDs with analytics data
     * @throws IOException If storage access fails.
     */
    List<String> listAnalyticsRunIds() throws IOException;
}
