package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.database.IMetadataDatabase;
import org.evochora.datapipeline.api.services.IService;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An indexer service responsible for reading simulation metadata from storage
 * and writing it to a database.
 */
public class MetadataIndexer extends AbstractIndexer implements IMonitorable {

    private final IMetadataDatabase database;
    private final int metadataFilePollIntervalMs;
    private final int metadataFileMaxPollDurationMs;
    private final AtomicLong metadataIndexed = new AtomicLong(0);
    private final AtomicLong metadataFailed = new AtomicLong(0);

    public MetadataIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.database = getRequiredResource("database", IMetadataDatabase.class);
        this.metadataFilePollIntervalMs = options.hasPath("metadataFilePollIntervalMs") ? options.getInt("metadataFilePollIntervalMs") : 1000;
        this.metadataFileMaxPollDurationMs = options.hasPath("metadataFileMaxPollDurationMs") ? options.getInt("metadataFileMaxPollDurationMs") : 60000;
    }

    @Override
    protected void indexRun(String runId) throws Exception {
        log.info("Indexing metadata for run: {}", runId);
        String metadataKey = runId + "/metadata.pb";
        SimulationMetadata metadata = pollForMetadataFile(metadataKey);
        database.createSimulationRun(runId);
        database.setSimulationRun(runId);
        database.insertMetadata(metadata);
        metadataIndexed.incrementAndGet();
        log.info("Successfully indexed metadata for run: {}", runId);
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
                    throw new TimeoutException("Metadata file did not appear within " + metadataFileMaxPollDurationMs + "ms: " + key);
                }
                Thread.sleep(metadataFilePollIntervalMs);
            }
        }
        throw new InterruptedException("Metadata polling interrupted.");
    }

    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
                "metadata_indexed", metadataIndexed.get(),
                "metadata_failed", metadataFailed.get()
        );
    }

    @Override
    public List<OperationalError> getErrors() {
        return Collections.emptyList();
    }

    @Override
    public void clearErrors() {
        // No-op.
    }

    @Override
    public boolean isHealthy() {
        return getCurrentState() != IService.State.ERROR;
    }
}