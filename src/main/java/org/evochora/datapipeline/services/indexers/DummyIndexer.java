package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.IMetadataReader;
import org.evochora.datapipeline.services.indexers.components.MetadataReadingComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test indexer for validating metadata reading infrastructure.
 * <p>
 * <strong>Phase 2.5.1 Scope:</strong>
 * <ul>
 *   <li>Discovers simulation run</li>
 *   <li>Waits for metadata to be indexed (polls database)</li>
 *   <li>Reads and logs metadata (especially samplingInterval)</li>
 *   <li>Does NOT process batches (added in Phase 2.5.2)</li>
 * </ul>
 * <p>
 * <strong>Purpose:</strong> Validate metadata reading capability before adding
 * batch coordination and processing.
 * <p>
 * <strong>Note:</strong> DummyIndexer will later be migrated to use BatchInfo from topic
 * instead of polling the database. Currently uses BatchInfo as message type.
 *
 * @param <ACK> The acknowledgment token type (implementation-specific, e.g., H2's AckToken)
 */
public class DummyIndexer<ACK> extends AbstractIndexer<BatchInfo, ACK> {
    private static final Logger log = LoggerFactory.getLogger(DummyIndexer.class);
    
    private final IMetadataReader metadataReader;
    private final MetadataReadingComponent metadataComponent;
    private final AtomicLong runsProcessed = new AtomicLong(0);
    
    public DummyIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        
        // Setup metadata reader and component
        this.metadataReader = getRequiredResource("metadata", IMetadataReader.class);
        int pollIntervalMs = options.hasPath("pollIntervalMs") ? options.getInt("pollIntervalMs") : 1000;
        int maxPollDurationMs = options.hasPath("maxPollDurationMs") ? options.getInt("maxPollDurationMs") : 300000;
        
        this.metadataComponent = new MetadataReadingComponent(metadataReader, pollIntervalMs, maxPollDurationMs);
    }
    
    @Override
    protected void indexRun(String runId) throws Exception {
        // Use try-with-resources to ensure connection cleanup
        try (IMetadataReader reader = metadataReader) {
            log.debug("Starting metadata reading for run: {}", runId);
            
            // Load metadata (polls until available)
            metadataComponent.loadMetadata(runId);
            
            runsProcessed.incrementAndGet();
            
            log.debug("Successfully read metadata for run: {}", runId);
            
            // Phase 2.5.1: Stop after metadata (no batch processing yet)
        }  // AutoCloseable.close() releases connection
    }
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);
        
        metrics.put("runs_processed", runsProcessed.get());
    }
}

