package org.evochora.datapipeline.api.resources.storage;

import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Capability for writing generic analysis artifacts (blobs/files).
 * Used by Analytics Plugins to store results.
 * <p>
 * Implementations (wrappers) should handle monitoring (bytes written, latency).
 * <p>
 * <strong>Thread Safety:</strong> Implementations MUST be thread-safe.
 */
public interface IAnalyticsStorageWrite extends IResource, IMonitorable {
    /**
     * Opens an output stream to write an analysis artifact.
     * The implementation handles path resolution relative to its analytics root.
     *
     * @param runId The simulation run ID (to separate artifacts by run)
     * @param metricId The metric/plugin identifier (e.g. "population")
     * @param lodLevel The LOD level ("raw", "lod1", etc.) or null for metadata/root files
     * @param filename Filename (e.g. "batch_1000_2000.parquet")
     * @return Stream to write data to. Caller must close it.
     * @throws IOException If storage is not writable.
     */
    OutputStream openAnalyticsOutputStream(String runId, String metricId, String lodLevel, String filename) throws IOException;

    /**
     * Writes a complete blob atomically (optional helper).
     *
     * @param runId The simulation run ID
     * @param metricId The metric/plugin identifier
     * @param lodLevel The LOD level or null
     * @param filename Filename
     * @param data The data to write
     * @throws IOException If write fails
     */
    default void writeAnalyticsBlob(String runId, String metricId, String lodLevel, String filename, byte[] data) throws IOException {
        try (OutputStream out = openAnalyticsOutputStream(runId, metricId, lodLevel, filename)) {
            out.write(data);
        }
    }
}
