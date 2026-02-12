package org.evochora.datapipeline.services.indexers;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareOrganismDataWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * Indexer for organism data (static and per-tick state) based on TickDataChunks.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Consumes BatchInfo messages from batch-topic via AbstractBatchIndexer.</li>
 *   <li>Reads TickDataChunk batches from storage.</li>
 *   <li>Extracts organism states from both snapshots and deltas.</li>
 *   <li>Writes organism tables via {@link IResourceSchemaAwareOrganismDataWriter}.</li>
 * </ul>
 * <p>
 * <strong>Chunk Processing:</strong> Organisms are always complete in both snapshots
 * and deltas (they change almost entirely every tick), so this indexer extracts
 * organism data from all ticks within each chunk.
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
        this.insertBatchSize = options.hasPath("insertBatchSize") ? options.getInt("insertBatchSize") : 5;
    }

    @Override
    protected Set<ComponentType> getOptionalComponents() {
        return EnumSet.of(ComponentType.DLQ);
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
     * Flushes buffered chunks to the organism tables.
     * <p>
     * Extracts organism states from both snapshots and deltas in each chunk.
     * All ticks are written in a single JDBC batch by the underlying database
     * implementation. MERGE ensures idempotent upserts.
     *
     * @param chunks Chunks to flush (typically 1-10 chunks per flush)
     * @throws Exception if write fails
     */
    @Override
    protected void flushChunks(List<TickDataChunk> chunks) throws Exception {
        if (chunks.isEmpty()) {
            log.debug("No chunks to flush for OrganismIndexer");
            return;
        }

        // Extract all ticks (snapshots + deltas) from chunks
        List<TickData> allTicks = new ArrayList<>();
        for (TickDataChunk chunk : chunks) {
            // Add snapshot
            allTicks.add(chunk.getSnapshot());
            
            // Add deltas (converted to TickData-like structure for database writer)
            // Note: OrganismStates are identical in structure for both snapshot and delta
            for (TickDelta delta : chunk.getDeltasList()) {
                // Create a minimal TickData for organism extraction
                // The database writer only needs tickNumber and organisms
                TickData deltaAsTick = TickData.newBuilder()
                    .setTickNumber(delta.getTickNumber())
                    .addAllOrganisms(delta.getOrganismsList())
                    .build();
                allTicks.add(deltaAsTick);
            }
        }

        database.writeOrganismStates(allTicks);

        int totalOrganisms = allTicks.stream()
                .mapToInt(TickData::getOrganismsCount)
                .sum();
        int totalTicks = allTicks.size();

        log.debug("Flushed {} organisms from {} chunks ({} ticks)", 
                  totalOrganisms, chunks.size(), totalTicks);
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
     * Estimates memory for the OrganismIndexer chunk buffer at worst-case.
     * <p>
     * <strong>Calculation:</strong> insertBatchSize (chunks) × bytesPerChunk
     * <p>
     * The buffer holds List&lt;TickDataChunk&gt; where each chunk contains organism states
     * for all ticks within the chunk.
     */
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // Each chunk contains organism states for all ticks
        long bytesPerChunk = params.estimateBytesPerChunk();
        long totalBytes = (long) insertBatchSize * bytesPerChunk;
        
        String explanation = String.format("%d insertBatchSize (chunks) × %s/chunk (%d samples/chunk, %d ticks, %d organisms)",
            insertBatchSize,
            SimulationParameters.formatBytes(bytesPerChunk),
            params.samplesPerChunk(),
            params.simulationTicksPerChunk(),
            params.maxOrganisms());
        
        return List.of(new MemoryEstimate(
            serviceName,
            totalBytes,
            explanation,
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}
