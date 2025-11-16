package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareOrganismDataWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Indexer for organism data (static and per-tick state) based on TickData.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Consumes BatchInfo messages from batch-topic via AbstractBatchIndexer.</li>
 *   <li>Reads TickData batches from storage.</li>
 *   <li>Writes organism tables via {@link IResourceSchemaAwareOrganismDataWriter}.</li>
 * </ul>
 * <p>
 * Read-path (HTTP API, visualizer) is implemented in later phases; this indexer
 * focuses exclusively on the write path.
 *
 * @param <ACK> Topic acknowledgment token type
 */
public class OrganismIndexer<ACK> extends AbstractBatchIndexer<ACK> {

    private static final Logger log = LoggerFactory.getLogger(OrganismIndexer.class);

    private final IResourceSchemaAwareOrganismDataWriter database;

    /**
     * Creates a new OrganismIndexer.
     *
     * @param name      Service name
     * @param options   Indexer configuration
     * @param resources Bound resources (storage, topic, metadata, database, etc.)
     */
    public OrganismIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.database = getRequiredResource("database", IResourceSchemaAwareOrganismDataWriter.class);
    }

    /**
     * Prepares organism tables in the current run schema.
     *
     * @param runId Simulation run ID (schema already set by AbstractIndexer)
     * @throws Exception if preparation fails
     */
    @Override
    protected void prepareTables(String runId) throws Exception {
        database.createOrganismTables();
        log.debug("Organism tables prepared for run '{}'", runId);
    }

    /**
     * Flushes buffered ticks to the organism tables.
     * <p>
     * All ticks are written in a single JDBC batch by the underlying database
     * implementation. MERGE ensures idempotent upserts.
     *
     * @param ticks Ticks to flush
     * @throws Exception if write fails
     */
    @Override
    protected void flushTicks(List<TickData> ticks) throws Exception {
        if (ticks.isEmpty()) {
            log.debug("No ticks to flush for OrganismIndexer");
            return;
        }

        database.writeOrganismStates(ticks);

        int totalOrganisms = ticks.stream()
                .mapToInt(TickData::getOrganismsCount)
                .sum();

        log.debug("Flushed {} organisms from {} ticks", totalOrganisms, ticks.size());
    }

    @Override
    protected void logStarted() {
        log.info("OrganismIndexer started: metadata=[pollInterval={}ms, maxPollDuration={}ms], topicPollTimeout={}ms",
                indexerOptions.hasPath("metadataPollIntervalMs") ? indexerOptions.getInt("metadataPollIntervalMs") : "default",
                indexerOptions.hasPath("metadataMaxPollDurationMs") ? indexerOptions.getInt("metadataMaxPollDurationMs") : "default",
                indexerOptions.hasPath("topicPollTimeoutMs") ? indexerOptions.getInt("topicPollTimeoutMs") : 5000);
    }
}


