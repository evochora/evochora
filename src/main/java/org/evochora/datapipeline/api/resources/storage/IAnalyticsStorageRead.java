package org.evochora.datapipeline.api.resources.storage;

import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

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
     * @param path Relative path (e.g. "population/raw/batch_001.parquet")
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
}
