package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
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
public class OrganismIndexer<ACK> extends AbstractBatchIndexer<ACK> implements IMemoryEstimatable {

    private static final Logger log = LoggerFactory.getLogger(OrganismIndexer.class);

    private final IResourceSchemaAwareOrganismDataWriter database;
    private final int insertBatchSize;

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
        this.insertBatchSize = options.hasPath("insertBatchSize") ? options.getInt("insertBatchSize") : 25;
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
    
    // ==================== IMemoryEstimatable ====================
    
    /**
     * {@inheritDoc}
     * <p>
     * Estimates memory for the OrganismIndexer tick buffer at worst-case.
     * <p>
     * <strong>Calculation:</strong> insertBatchSize × bytesPerOrganismTick (100% organisms)
     * <p>
     * The buffer holds List<TickData> where each tick contains OrganismState for all organisms.
     * At worst-case, maxOrganisms are alive simultaneously.
     */
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // Each tick contains organism states at 100% capacity
        // ~500 bytes per organism for full state (registers, stacks, code reference, position)
        long bytesPerTick = params.estimateOrganismBytesPerTick();
        long totalBytes = (long) insertBatchSize * bytesPerTick;
        
        // Add TickData wrapper overhead (~200 bytes per tick for protobuf metadata)
        long wrapperOverhead = (long) insertBatchSize * 200;
        totalBytes += wrapperOverhead;
        
        String explanation = String.format("%d insertBatchSize × %s/tick (100%% organisms = %d × ~500B)",
            insertBatchSize,
            SimulationParameters.formatBytes(bytesPerTick),
            params.maxOrganisms());
        
        return List.of(new MemoryEstimate(
            serviceName,
            totalBytes,
            explanation,
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}
