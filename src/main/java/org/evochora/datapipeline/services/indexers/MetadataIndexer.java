package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.MetadataInfo;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareMetadataWriter;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An indexer service responsible for indexing simulation metadata to the database.
 * <p>
 * This service subscribes to the metadata-topic and receives instant notifications
 * when metadata is written to storage by MetadataPersistenceService. This eliminates
 * the need for polling and enables event-driven metadata indexing.
 * <p>
 * <strong>One-Shot Pattern:</strong> Processes a single metadata notification and stops.
 *
 * @param <ACK> The acknowledgment token type (implementation-specific, e.g., H2's AckToken)
 */
public class MetadataIndexer<ACK> extends AbstractIndexer<MetadataInfo, ACK> {

    private final IResourceSchemaAwareMetadataWriter database;
    private final int topicPollTimeoutMs;
    
    // Metrics
    private final AtomicLong metadataIndexed = new AtomicLong(0);
    private final AtomicLong metadataFailed = new AtomicLong(0);

    public MetadataIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.database = getRequiredResource("database", IResourceSchemaAwareMetadataWriter.class);
        this.topicPollTimeoutMs = options.hasPath("topicPollTimeoutMs") 
            ? options.getInt("topicPollTimeoutMs") 
            : 30000;  // Default: 30 seconds
    }

    @Override
    protected void indexRun(String runId) throws Exception {
        // Check if metadata already exists in database (resume mode scenario)
        try (IResourceSchemaAwareMetadataWriter db = database) {
            if (db.hasMetadata(runId)) {
                log.debug("Metadata already indexed for run: {} - skipping (resume mode)", runId);
                return;
            }
        }

        log.debug("Waiting for metadata notification for run: {} (timeout: {}ms)", runId, topicPollTimeoutMs);

        // Note: topic.setSimulationRun() already called by AbstractIndexer.discoverRunId()

        // Poll for metadata notification with timeout
        var message = topic.poll(topicPollTimeoutMs, TimeUnit.MILLISECONDS);

        if (message == null) {
            metadataFailed.incrementAndGet();
            log.error("Metadata notification did not arrive within {}ms for run: {}", topicPollTimeoutMs, runId);
            throw new TimeoutException("Metadata notification timeout after " + topicPollTimeoutMs + "ms");
        }
        
        MetadataInfo info = message.payload();
        log.debug("Received metadata notification, reading from storage: {}", info.getStoragePath());
        
        // Read metadata from storage
        StoragePath storagePath = StoragePath.of(info.getStoragePath());
        SimulationMetadata metadata = storage.readMessage(storagePath, SimulationMetadata.parser());
        
        // Index metadata to database
        setShutdownPhase(ShutdownPhase.PROCESSING);
        Thread.interrupted();
        try (IResourceSchemaAwareMetadataWriter db = database) {
            db.insertMetadata(metadata);
            metadataIndexed.incrementAndGet();
            log.debug("Successfully indexed metadata for run: {}, service stopping", runId);
        } catch (Exception e) {
            metadataFailed.incrementAndGet();
            log.error("Failed to index metadata for run: {}", runId);
            throw e;
        } finally {
            setShutdownPhase(ShutdownPhase.WAITING);
        }

        // Acknowledge message after successful processing
        topic.ack(message);
        log.debug("Acknowledged metadata notification for {}", info.getStoragePath());
    }

    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);
        
        metrics.put("metadata_indexed", metadataIndexed.get());
        metrics.put("metadata_failed", metadataFailed.get());
    }
}