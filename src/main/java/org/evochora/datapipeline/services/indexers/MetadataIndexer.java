package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.database.IMetadataWriter;
import org.evochora.datapipeline.api.services.IService;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An indexer service responsible for reading simulation metadata from storage
 * and writing it to a database.
 */
public class MetadataIndexer extends AbstractIndexer implements IMonitorable {

    private static final int MAX_ERRORS = 100;

    private final IMetadataWriter database;
    private final int metadataFilePollIntervalMs;
    private final int metadataFileMaxPollDurationMs;
    
    // Metrics
    private final AtomicLong metadataIndexed = new AtomicLong(0);
    private final AtomicLong metadataFailed = new AtomicLong(0);
    
    // Error tracking (follows pattern from PersistenceService)
    private final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();

    public MetadataIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.database = getRequiredResource("database", IMetadataWriter.class);
        this.metadataFilePollIntervalMs = options.hasPath("metadataFilePollIntervalMs") ? options.getInt("metadataFilePollIntervalMs") : 1000;
        this.metadataFileMaxPollDurationMs = options.hasPath("metadataFileMaxPollDurationMs") ? options.getInt("metadataFileMaxPollDurationMs") : 60000;
    }

    @Override
    protected void prepareSchema(String runId) throws Exception {
        // Create schema and tables for this simulation run
        database.createSimulationRun(runId);
    }
    
    @Override
    protected void indexRun(String runId) throws Exception {
        log.info("Indexing metadata for run: {}", runId);
        String metadataKey = runId + "/metadata.pb";
        
        SimulationMetadata metadata = pollForMetadataFile(metadataKey);
        
        // Use try-with-resources to ensure connection is released
        // Note: prepareSchema() and setSchema() already called by AbstractIndexer.discoverRunId()
        try (IMetadataWriter db = database) {
            db.insertMetadata(metadata);
            metadataIndexed.incrementAndGet();
            log.info("Successfully indexed metadata for run: {}", runId);
        } catch (Exception e) {
            metadataFailed.incrementAndGet();
            recordError("METADATA_INDEXING_FAILED",
                    "Failed to index metadata for run: " + runId,
                    "Error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            throw e;
        }
    }

    private SimulationMetadata pollForMetadataFile(String key) throws InterruptedException, TimeoutException, IOException {
        log.debug("Polling for metadata file: {}", key);
        long startTime = System.currentTimeMillis();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                return storage.readMessage(key, SimulationMetadata.parser());
            } catch (IOException e) {
                if (System.currentTimeMillis() - startTime > metadataFileMaxPollDurationMs) {
                    metadataFailed.incrementAndGet();
                    recordError("METADATA_FILE_TIMEOUT",
                            "Metadata file did not appear within timeout",
                            "Key: " + key + ", Timeout: " + metadataFileMaxPollDurationMs + "ms");
                    throw new TimeoutException("Metadata file did not appear within " + metadataFileMaxPollDurationMs + "ms: " + key);
                }
                Thread.sleep(metadataFilePollIntervalMs);
            }
        }
        
        recordError("METADATA_POLLING_INTERRUPTED",
                "Metadata file polling was interrupted",
                "Key: " + key);
        throw new InterruptedException("Metadata polling interrupted.");
    }

    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
                "metadata_indexed", metadataIndexed.get(),
                "metadata_failed", metadataFailed.get(),
                "error_count", errors.size()
        );
    }

    @Override
    public List<OperationalError> getErrors() {
        return new ArrayList<>(errors);
    }

    @Override
    public void clearErrors() {
        errors.clear();
    }

    /**
     * Records an operational error with bounded memory usage.
     * <p>
     * Follows the same pattern as PersistenceService and other services.
     * Maintains at most MAX_ERRORS (100) in memory by removing oldest errors.
     *
     * @param code    Error code for categorization
     * @param message Human-readable error message
     * @param details Additional context about the error
     */
    private void recordError(String code, String message, String details) {
        errors.add(new OperationalError(Instant.now(), code, message, details));
        
        // Prevent unbounded memory growth
        while (errors.size() > MAX_ERRORS) {
            errors.pollFirst();
        }
    }

    @Override
    public boolean isHealthy() {
        return getCurrentState() != IService.State.ERROR;
    }
}