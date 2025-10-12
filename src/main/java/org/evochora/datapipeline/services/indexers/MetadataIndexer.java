package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.IMetadataWriter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An indexer service responsible for reading simulation metadata from storage
 * and writing it to a database.
 */
public class MetadataIndexer extends AbstractIndexer {

    private final IMetadataWriter database;
    private final int metadataFilePollIntervalMs;
    private final int metadataFileMaxPollDurationMs;
    
    // Metrics
    private final AtomicLong metadataIndexed = new AtomicLong(0);
    private final AtomicLong metadataFailed = new AtomicLong(0);

    public MetadataIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.database = getRequiredResource("database", IMetadataWriter.class);
        this.metadataFilePollIntervalMs = options.hasPath("metadataFilePollIntervalMs") ? options.getInt("metadataFilePollIntervalMs") : 1000;
        this.metadataFileMaxPollDurationMs = options.hasPath("metadataFileMaxPollDurationMs") ? options.getInt("metadataFileMaxPollDurationMs") : 60000;
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
            log.error("Failed to index metadata for run: {}", runId);
            throw e;  // Fatal error → let AbstractService handle ERROR state
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
                    log.error("Metadata file did not appear within {}ms: {}", metadataFileMaxPollDurationMs, key);
                    throw new TimeoutException("Metadata file did not appear within " + metadataFileMaxPollDurationMs + "ms: " + key);
                }
                Thread.sleep(metadataFilePollIntervalMs);
            }
        }
        
        log.debug("Interrupted while polling for metadata file: {}", key);
        throw new InterruptedException("Metadata polling interrupted.");
    }

    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);
        
        metrics.put("metadata_indexed", metadataIndexed.get());
        metrics.put("metadata_failed", metadataFailed.get());
    }

    /**
     * MetadataIndexer uses a lower error limit (100 instead of default 10000)
     * since it's a short-lived service that processes a single metadata file.
     */
    @Override
    protected int getMaxErrors() {
        return 100;
    }
}