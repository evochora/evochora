package org.evochora.datapipeline.services.indexers.components;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareMetadataReader;
import org.evochora.datapipeline.api.resources.database.MetadataNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeoutException;

/**
 * Component for reading and caching simulation metadata from the database.
 * <p>
 * Provides metadata polling (blocks until available) and caching for efficient access
 * to metadata fields like samplingInterval.
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Should be used by single indexer instance only.
 */
public class MetadataReadingComponent {
    private static final Logger log = LoggerFactory.getLogger(MetadataReadingComponent.class);
    
    private final IResourceSchemaAwareMetadataReader metadataReader;
    private final int pollIntervalMs;
    private final int maxPollDurationMs;
    
    private SimulationMetadata metadata;
    
    /**
     * Creates metadata reading component.
     *
     * @param metadataReader Database capability for reading metadata
     * @param pollIntervalMs Interval between polling attempts (milliseconds)
     * @param maxPollDurationMs Maximum time to wait for metadata (milliseconds)
     */
    public MetadataReadingComponent(IResourceSchemaAwareMetadataReader metadataReader, 
                                   int pollIntervalMs, 
                                   int maxPollDurationMs) {
        this.metadataReader = metadataReader;
        this.pollIntervalMs = pollIntervalMs;
        this.maxPollDurationMs = maxPollDurationMs;
    }
    
    /**
     * Polls for metadata until available or timeout.
     * <p>
     * Blocks until metadata is available in the database. Used by indexers to wait
     * for MetadataIndexer to complete before starting batch processing.
     *
     * @param runId Simulation run ID
     * @throws InterruptedException if interrupted while polling
     * @throws TimeoutException if metadata not found within maxPollDurationMs
     */
    public void loadMetadata(String runId) throws InterruptedException, TimeoutException {
        log.debug("Polling for metadata: runId={}", runId);
        
        long startTime = System.currentTimeMillis();
        
        while (!Thread.currentThread().isInterrupted()) {
            try {
                this.metadata = metadataReader.getMetadata(runId);
                return;
                
            } catch (MetadataNotFoundException e) {
                // Expected - metadata not yet indexed
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > maxPollDurationMs) {
                    throw new TimeoutException(
                        "Metadata not indexed within " + maxPollDurationMs + "ms for run: " + runId
                    );
                }
                
                // Release connection before idle period (reduces pool pressure)
                // Also forces fresh connection on next attempt (may see new tables/data)
                metadataReader.releaseConnection();
                Thread.sleep(pollIntervalMs);
            }
        }
        
        throw new InterruptedException("Metadata polling interrupted");
    }
    
    /**
     * Gets the sampling interval from cached metadata.
     *
     * @return Sampling interval
     * @throws IllegalStateException if metadata not loaded yet
     */
    public int getSamplingInterval() {
        if (metadata == null) {
            throw new IllegalStateException("Metadata not loaded - call loadMetadata() first");
        }
        return metadata.getSamplingInterval();
    }
    
    /**
     * Gets the complete cached metadata.
     *
     * @return Simulation metadata
     * @throws IllegalStateException if metadata not loaded yet
     */
    public SimulationMetadata getMetadata() {
        if (metadata == null) {
            throw new IllegalStateException("Metadata not loaded - call loadMetadata() first");
        }
        return metadata;
    }
    
}

