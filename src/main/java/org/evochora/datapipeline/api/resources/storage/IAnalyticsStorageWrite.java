package org.evochora.datapipeline.api.resources.storage;

import java.io.IOException;
import java.io.OutputStream;

import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;

/**
 * Capability for writing generic analysis artifacts (blobs/files).
 * Used by Analytics Plugins to store results.
 * <p>
 * <strong>Storage Structure:</strong>
 * <pre>
 * {runId}/analytics/{metricId}/{lodLevel}/{subPath}/{filename}
 * Example: sim-123/analytics/population/lod0/000/001/batch_0001000_0001999.parquet
 * </pre>
 * <p>
 * Implementations (wrappers) should handle monitoring (bytes written, latency).
 * <p>
 * <strong>Thread Safety:</strong> Implementations MUST be thread-safe.
 */
public interface IAnalyticsStorageWrite extends IResource, IMonitorable {
    
    /**
     * Opens an output stream to write an analysis artifact with hierarchical folder structure.
     * <p>
     * The implementation handles path resolution relative to its analytics root.
     * Parent directories are created automatically.
     *
     * @param runId The simulation run ID (to separate artifacts by run)
     * @param metricId The metric/plugin identifier (e.g. "population")
     * @param lodLevel The LOD level ("lod0", "lod1", etc.) or null for metadata/root files
     * @param subPath Hierarchical subfolder path (e.g. "000/001") or null for flat structure
     * @param filename Filename (e.g. "batch_0001000_0001999.parquet")
     * @return Stream to write data to. Caller must close it.
     * @throws IOException If storage is not writable.
     */
    OutputStream openAnalyticsOutputStream(String runId, String metricId, String lodLevel, String subPath, String filename) throws IOException;
    
    /**
     * Opens an output stream to write an analysis artifact (flat structure, no subPath).
     * <p>
     * Convenience overload for backwards compatibility and metadata files.
     *
     * @param runId The simulation run ID (to separate artifacts by run)
     * @param metricId The metric/plugin identifier (e.g. "population")
     * @param lodLevel The LOD level ("lod0", "lod1", etc.) or null for metadata/root files
     * @param filename Filename (e.g. "metadata.json")
     * @return Stream to write data to. Caller must close it.
     * @throws IOException If storage is not writable.
     */
    default OutputStream openAnalyticsOutputStream(String runId, String metricId, String lodLevel, String filename) throws IOException {
        return openAnalyticsOutputStream(runId, metricId, lodLevel, null, filename);
    }

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
        try (OutputStream out = openAnalyticsOutputStream(runId, metricId, lodLevel, null, filename)) {
            out.write(data);
        }
    }
}
