package org.evochora.datapipeline.services.indexers;

import com.google.protobuf.Message;
import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.ISchemaAwareDatabase;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.topics.ISimulationRunAwareTopic;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.services.AbstractService;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * An abstract base class for indexer services that process data from a simulation run.
 * <p>
 * All indexers subscribe to a topic for event-driven notification and read data from storage.
 * This eliminates polling and enables instant processing when new data is available.
 * <p>
 * <strong>Resources:</strong>
 * <ul>
 *   <li>{@code storage} - Required: Storage backend for reading data files</li>
 *   <li>{@code topic} - Required: Topic for receiving notifications (must be named "topic")</li>
 * </ul>
 *
 * @param <T> The message type read from the topic (e.g., MetadataInfo, BatchInfo)
 * @param <ACK> The acknowledgment token type (implementation-specific, e.g., H2's AckToken)
 */
public abstract class AbstractIndexer<T extends Message, ACK> extends AbstractService {

    protected final IBatchStorageRead storage;
    protected final ITopicReader<T, ACK> topic;
    protected final Config indexerOptions;

    private final String configuredRunId;
    private final int pollIntervalMs;
    private final int maxPollDurationMs;
    private final Instant indexerStartTime;

    protected Instant getIndexerStartTime() {
        return indexerStartTime;
    }

    protected AbstractIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.storage = getRequiredResource("storage", IBatchStorageRead.class);
        this.topic = getOptionalResource("topic", ITopicReader.class).orElse(null);
        this.indexerOptions = options;
        this.configuredRunId = options.hasPath("runId") ? options.getString("runId") : null;
        this.pollIntervalMs = options.hasPath("pollIntervalMs") ? options.getInt("pollIntervalMs") : 1000;
        this.maxPollDurationMs = options.hasPath("maxPollDurationMs") ? options.getInt("maxPollDurationMs") : 300000;
        this.indexerStartTime = Instant.now();
        
        // Warn if topic is not configured
        if (this.topic == null) {
            log.warn("{} initialized WITHOUT topic - event-driven indexing disabled! Service will fail.", 
                this.getClass().getSimpleName());
        }
    }

    protected String discoverRunId() throws InterruptedException, TimeoutException {
        String runId = null;
        
        if (configuredRunId != null) {
            log.info("Using configured runId: {}", configuredRunId);
            runId = configuredRunId;
        } else {
            log.debug("Discovering runId from storage (timestamp-based, after {})", indexerStartTime);
            long startTime = System.currentTimeMillis();

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    List<String> runIds = storage.listRunIds(indexerStartTime);
                    if (!runIds.isEmpty()) {
                        runId = runIds.get(0);
                        log.debug("Discovered runId from storage: {}", runId);
                        break;
                    }

                    if (System.currentTimeMillis() - startTime > maxPollDurationMs) {
                        throw new TimeoutException("No simulation run appeared within " + maxPollDurationMs + "ms.");
                    }

                    Thread.sleep(pollIntervalMs);
                } catch (IOException e) {
                    log.debug("Error listing run IDs from storage, retrying: {}", e.getMessage());
                    Thread.sleep(pollIntervalMs);
                }
            }
            
            if (runId == null) {
                throw new InterruptedException("Run discovery interrupted.");
            }
        }
        
        // Prepare schema (MetadataIndexer creates it, others do nothing)
        try {
            prepareSchema(runId);
        } catch (Exception e) {
            log.error("Failed to prepare schema for run: {}", runId);
            throw new RuntimeException("Failed to prepare schema for run: " + runId, e);
        }
        
        // Set schema for all database resources of this indexer
        setSchemaForAllDatabaseResources(runId);
        
        return runId;
    }
    
    /**
     * Template method hook for schema preparation before setting schema.
     * <p>
     * Default implementation does nothing (assumes schema already exists).
     * MetadataIndexer overrides this to create the schema via createSimulationRun().
     * <p>
     * Called automatically by discoverRunId() before setSchemaForAllDatabaseResources().
     *
     * @param runId The simulation run ID
     * @throws Exception if schema preparation fails
     */
    protected void prepareSchema(String runId) throws Exception {
        // Default: no-op (schema already exists)
    }

    /**
     * Sets the schema for all schema-aware resources of this indexer.
     * <p>
     * Called automatically by discoverRunId() after prepareSchema().
     * Iterates through this indexer's resources and calls setSimulationRun() on:
     * <ul>
     *   <li>{@link ISchemaAwareDatabase} instances (coordinator, metadata reader, etc.)</li>
     *   <li>{@link ISimulationRunAwareTopic} instances (topic readers)</li>
     * </ul>
     * <p>
     * Each indexer instance only sets schema for its own resources, not for other indexers.
     *
     * @param runId The simulation run ID
     */
    private void setSchemaForAllDatabaseResources(String runId) {
        for (List<IResource> resourceList : resources.values()) {
            for (IResource resource : resourceList) {
                if (resource instanceof ISchemaAwareDatabase) {
                    ((ISchemaAwareDatabase) resource).setSimulationRun(runId);
                    log.debug("Set schema for database resource: {}", resource.getResourceName());
                }
                if (resource instanceof ISimulationRunAwareTopic) {
                    ((ISimulationRunAwareTopic) resource).setSimulationRun(runId);
                    log.debug("Set simulation run for topic resource: {}", resource.getResourceName());
                }
            }
        }
    }

    protected abstract void indexRun(String runId) throws Exception;

    @Override
    protected void run() throws InterruptedException {
        String runId = null;
        
        // Phase 1: Discover run ID
        try {
            runId = discoverRunId();
        } catch (InterruptedException e) {
            log.debug("Run discovery interrupted during shutdown");
            throw e;
        } catch (TimeoutException e) {
            log.error("Run discovery timeout after {}ms", maxPollDurationMs);
            throw new RuntimeException("Run discovery timeout", e);  // Fatal → ERROR state
        } catch (Exception e) {
            log.error("Failed to discover run: {}", e.getMessage());
            throw new RuntimeException("Run discovery failed", e);  // Fatal → ERROR state
        }
        
        // Phase 2: Index the run
        try {
            indexRun(runId);
        } catch (InterruptedException e) {
            log.debug("Indexing interrupted during shutdown for run: {}", runId);
            throw e;
        } catch (TimeoutException e) {
            log.error("Indexing timeout for run: {}", runId);
            throw new RuntimeException("Indexing timeout", e);  // Fatal → ERROR state
        } catch (Exception e) {
            log.error("Indexing failed for run: {}", runId);
            throw new RuntimeException("Indexing failed", e);  // Fatal → ERROR state
        }
    }
}