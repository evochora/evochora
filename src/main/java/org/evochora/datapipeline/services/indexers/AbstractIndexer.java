package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.services.AbstractService;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * An abstract base class for indexer services that process data from a simulation run.
 */
public abstract class AbstractIndexer extends AbstractService {

    protected final IBatchStorageRead storage;
    protected final Config indexerOptions;

    private final String configuredRunId;
    private final int pollIntervalMs;
    private final int maxPollDurationMs;
    private final Instant indexerStartTime;

    protected AbstractIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.storage = getRequiredResource("storage", IBatchStorageRead.class);
        this.indexerOptions = options;
        this.configuredRunId = options.hasPath("runId") ? options.getString("runId") : null;
        this.pollIntervalMs = options.hasPath("pollIntervalMs") ? options.getInt("pollIntervalMs") : 1000;
        this.maxPollDurationMs = options.hasPath("maxPollDurationMs") ? options.getInt("maxPollDurationMs") : 300000;
        this.indexerStartTime = Instant.now();
    }

    protected String discoverRunId() throws InterruptedException, TimeoutException {
        if (configuredRunId != null) {
            log.info("Using configured runId: {}", configuredRunId);
            return configuredRunId;
        }

        log.info("Discovering runId from storage (timestamp-based, after {})", indexerStartTime);
        long startTime = System.currentTimeMillis();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<String> runIds = storage.listRunIds(indexerStartTime);
                if (!runIds.isEmpty()) {
                    String discoveredRunId = runIds.get(0);
                    log.info("Discovered runId from storage: {}", discoveredRunId);
                    return discoveredRunId;
                }

                if (System.currentTimeMillis() - startTime > maxPollDurationMs) {
                    throw new TimeoutException("No simulation run appeared within " + maxPollDurationMs + "ms.");
                }

                Thread.sleep(pollIntervalMs);
            } catch (IOException e) {
                log.warn("Error listing run IDs from storage, retrying: {}", e.getMessage());
                Thread.sleep(pollIntervalMs);
            }
        }
        throw new InterruptedException("Run discovery interrupted.");
    }

    protected abstract void indexRun(String runId) throws Exception;

    @Override
    protected void run() throws InterruptedException {
        try {
            String runId = discoverRunId();
            indexRun(runId);
        } catch (TimeoutException e) {
            log.error("Failed to discover run: {}", e.getMessage());
            throw new RuntimeException(e);
        } catch (Exception e) {
            log.error("Indexing failed", e);
            throw new RuntimeException(e);
        }
    }
}