package org.evochora.datapipeline.api.analytics;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.storage.IAnalyticsStorageWrite;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Context provided to plugins.
 * Abstracts away Parquet generation, temp file handling, and storage.
 */
public interface IAnalyticsContext {
    /**
     * @return The simulation metadata for the current run
     */
    SimulationMetadata getMetadata();

    /**
     * @return The run ID of the current simulation
     */
    String getRunId();

    /**
     * Creates a writer for a generic artifact (blob).
     * <p>
     * This is a low-level API. For Parquet, use specific helpers or write to a temp file
     * and copy it using this stream.
     *
     * @param metricId The metric/plugin identifier
     * @param lodLevel The LOD level or null
     * @param filename The filename (e.g. "batch_1.parquet")
     * @return Output stream to write to
     * @throws IOException if storage access fails
     */
    OutputStream openArtifactStream(String metricId, String lodLevel, String filename) throws IOException;
    
    /**
     * Get the configured temporary directory for this indexer instance.
     * Plugins should use this for creating temporary files (e.g. for Parquet generation).
     * 
     * @return Path to a writable temporary directory
     */
    java.nio.file.Path getTempDirectory();
}

