package org.evochora.datapipeline.services.indexers;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareEnvironmentDataWriter;
import org.evochora.runtime.model.EnvironmentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * Indexes environment cell states from TickDataChunks for efficient spatial queries.
 * <p>
 * This indexer:
 * <ul>
 *   <li>Reads TickDataChunk batches from storage (via topic notifications)</li>
 *   <li>Stores chunks as BLOBs in the database (no decompression)</li>
 *   <li>Writes to database with MERGE for 100% idempotency</li>
 *   <li>Supports dimension-agnostic schema (1D to N-D)</li>
 * </ul>
 * <p>
 * <strong>Delta Compression:</strong> This indexer stores chunks directly without
 * decompression to maximize storage savings. Decompression is deferred to query time
 * in the EnvironmentController, which uses DeltaCodec to reconstruct individual ticks.
 * <p>
 * <strong>Resources Required:</strong>
 * <ul>
 *   <li>{@code storage} - IBatchStorageRead for reading TickDataChunk batches</li>
 *   <li>{@code topic} - ITopicReader for batch notifications</li>
 *   <li>{@code metadata} - IMetadataReader for simulation metadata</li>
 *   <li>{@code database} - IEnvironmentDataWriter for writing chunks</li>
 * </ul>
 * <p>
 * <strong>Components Used:</strong>
 * <ul>
 *   <li>MetadataReadingComponent - waits for metadata before processing</li>
 *   <li>ChunkBufferingComponent - buffers chunks for efficient batch writes</li>
 * </ul>
 * <p>
 * <strong>Competing Consumers:</strong> Multiple instances can run in parallel
 * using the same consumer group. Topic distributes batches across instances,
 * and MERGE ensures idempotent writes even with concurrent access.
 *
 * @param <ACK> The acknowledgment token type (implementation-specific)
 */
public class EnvironmentIndexer<ACK> extends AbstractBatchIndexer<ACK> implements IMemoryEstimatable {
    
    private static final Logger log = LoggerFactory.getLogger(EnvironmentIndexer.class);
    
    private final IResourceSchemaAwareEnvironmentDataWriter database;
    private final int insertBatchSize;
    private EnvironmentProperties envProps;
    
    /**
     * Creates a new environment indexer.
     * <p>
     * Uses default components (METADATA + BUFFERING) from AbstractBatchIndexer.
     *
     * @param name Service name (must not be null/blank)
     * @param options Configuration for this indexer (must not be null)
     * @param resources Resources for this indexer (must not be null)
     */
    public EnvironmentIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.database = getRequiredResource("database", IResourceSchemaAwareEnvironmentDataWriter.class);
        this.insertBatchSize = options.hasPath("insertBatchSize") ? options.getInt("insertBatchSize") : 5;
    }
    
    // Required components: METADATA + BUFFERING (inherited from AbstractBatchIndexer)

    @Override
    protected Set<ComponentType> getOptionalComponents() {
        return EnumSet.of(ComponentType.DLQ);
    }
    
    /**
     * Prepares database tables for environment data storage.
     * <p>
     * Extracts environment properties from metadata and creates the environment_chunks table.
     * <strong>Idempotent:</strong> Safe to call multiple times (uses CREATE TABLE IF NOT EXISTS).
     *
     * @param runId The simulation run ID (schema already set by AbstractIndexer)
     * @throws Exception if table preparation fails
     */
    @Override
    protected void prepareTables(String runId) throws Exception {
        // Load metadata (provided by MetadataReadingComponent via getMetadata())
        SimulationMetadata metadata = getMetadata();
        
        // Extract environment properties for coordinate conversion
        this.envProps = extractEnvironmentProperties(metadata);
        
        // Create database table (idempotent)
        int dimensions = envProps.getWorldShape().length;
        database.createEnvironmentDataTable(dimensions);
        
        log.debug("Environment tables prepared: {} dimensions", dimensions);
    }
    
    /**
     * Flushes buffered chunks to the database.
     * <p>
     * Writes chunks directly as BLOBs without decompression for maximum storage savings.
     * <p>
     * <strong>Idempotency:</strong> MERGE ensures duplicate writes are safe.
     *
     * @param chunks Chunks to flush (typically 1-10 chunks per flush)
     * @throws Exception if flush fails
     */
    @Override
    protected void flushChunks(List<TickDataChunk> chunks) throws Exception {
        if (chunks.isEmpty()) {
            log.debug("No chunks to flush");
            return;
        }

        // Write ALL chunks directly to database (no decompression)
        database.writeEnvironmentChunks(chunks);
        
        // Calculate total ticks for logging
        int totalTicks = chunks.stream()
            .mapToInt(chunk -> 1 + chunk.getDeltasCount())  // 1 snapshot + N deltas
            .sum();
        
        log.debug("Flushed {} chunks ({} ticks total)", chunks.size(), totalTicks);
    }
    
    /**
     * Extracts EnvironmentProperties from SimulationMetadata.
     * <p>
     * Parses world shape (dimensions) and topology (toroidal) from metadata.
     *
     * @param metadata Simulation metadata containing environment configuration
     * @return EnvironmentProperties for coordinate conversion
     */
    private EnvironmentProperties extractEnvironmentProperties(SimulationMetadata metadata) {
        // Extract world shape from metadata
        int[] worldShape = metadata.getEnvironment().getShapeList().stream()
            .mapToInt(Integer::intValue)
            .toArray();
        
        // Extract topology - check if ALL dimensions are toroidal
        // (In practice, all dimensions have same topology for now)
        boolean isToroidal = !metadata.getEnvironment().getToroidalList().isEmpty() 
            && metadata.getEnvironment().getToroidal(0);
        
        return new EnvironmentProperties(worldShape, isToroidal);
    }
    
    // ==================== IMemoryEstimatable ====================
    
    /**
     * {@inheritDoc}
     * <p>
     * Estimates memory for the EnvironmentIndexer chunk buffer at worst-case.
     * <p>
     * <strong>Calculation:</strong> insertBatchSize (chunks) × bytesPerChunk
     * <p>
     * The buffer holds List&lt;TickDataChunk&gt; where each chunk contains a snapshot
     * and deltas. Each chunk is estimated at ~25MB for a 1600×1200 environment.
     */
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // Each chunk contains snapshot + deltas
        long bytesPerChunk = params.estimateBytesPerChunk();
        long totalBytes = (long) insertBatchSize * bytesPerChunk;
        
        String explanation = String.format("%d insertBatchSize (chunks) × %s/chunk (%d ticks/chunk)",
            insertBatchSize,
            SimulationParameters.formatBytes(bytesPerChunk),
            params.ticksPerChunk());
        
        return List.of(new MemoryEstimate(
            serviceName,
            totalBytes,
            explanation,
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}
