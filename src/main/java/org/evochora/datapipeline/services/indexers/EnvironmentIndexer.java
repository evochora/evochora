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
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.utils.MetadataConfigHelper;
import org.evochora.runtime.model.EnvironmentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * Indexes environment cell states from TickDataChunks for efficient spatial queries.
 * <p>
 * This indexer uses streaming raw-byte pass-through: chunks are read as raw protobuf
 * bytes and written directly to the database without parsing or re-serialization.
 * This eliminates the parse/serialize round-trip and reduces peak heap from ~10.5 GB
 * to ~25 MB (one raw chunk at a time).
 * <p>
 * <strong>Processing flow:</strong>
 * <ol>
 *   <li>Reads raw protobuf bytes via {@code storage.forEachRawChunk()}</li>
 *   <li>Passes bytes directly to {@code database.writeRawChunk()}</li>
 *   <li>Commits batch via {@code database.commitRawChunks()}</li>
 * </ol>
 * <p>
 * <strong>Delta Compression:</strong> Chunks are stored as-is without decompression.
 * Decompression is deferred to query time in the EnvironmentController, which uses
 * DeltaCodec to reconstruct individual ticks.
 * <p>
 * <strong>Organisms:</strong> Raw bytes include organism data. The read path
 * ({@code RowPerChunkStrategy.parseChunkForEnvironment}) strips organisms at the
 * wire level during queries.
 * <p>
 * <strong>Resources Required:</strong>
 * <ul>
 *   <li>{@code storage} - IBatchStorageRead for reading TickDataChunk batches</li>
 *   <li>{@code topic} - ITopicReader for batch notifications</li>
 *   <li>{@code metadata} - IMetadataReader for simulation metadata</li>
 *   <li>{@code database} - IEnvironmentDataWriter for writing chunks</li>
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
    private EnvironmentProperties envProps;

    /**
     * Creates a new environment indexer.
     * <p>
     * Uses streaming raw-byte processing (no chunk buffering).
     *
     * @param name Service name (must not be null/blank)
     * @param options Configuration for this indexer (must not be null)
     * @param resources Resources for this indexer (must not be null)
     */
    public EnvironmentIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.database = getRequiredResource("database", IResourceSchemaAwareEnvironmentDataWriter.class);
    }

    @Override
    protected Set<ComponentType> getRequiredComponents() {
        return EnumSet.of(ComponentType.METADATA);
    }

    @Override
    protected Set<ComponentType> getOptionalComponents() {
        return EnumSet.of(ComponentType.DLQ);
    }

    @Override
    protected boolean useStreamingProcessing() {
        return true;
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
        SimulationMetadata metadata = getMetadata();
        this.envProps = extractEnvironmentProperties(metadata);

        int dimensions = envProps.getWorldShape().length;
        database.createEnvironmentDataTable(dimensions);

        log.debug("Environment tables prepared: {} dimensions", dimensions);
    }

    /**
     * Reads raw chunks from storage and writes them directly to the database.
     * <p>
     * Overrides the default template method to use raw-byte pass-through:
     * chunks are never parsed into Java objects. Each raw chunk is passed
     * directly to the database strategy for compression and file writing.
     *
     * @param path Storage path of the batch file
     * @param batchId Batch identifier for ACK tracking
     * @throws Exception if read or write fails
     */
    @Override
    protected void readAndProcessChunks(StoragePath path, String batchId) throws Exception {
        storage.forEachRawChunk(path, rawChunk -> {
            database.writeRawChunk(rawChunk.firstTick(), rawChunk.lastTick(),
                                   rawChunk.tickCount(), rawChunk.data());
            onChunkStreamed(batchId, rawChunk.tickCount());
        });
    }

    /**
     * Commits all raw chunks accumulated since the last commit.
     *
     * @throws Exception if commit fails
     */
    @Override
    protected void commitProcessedChunks() throws Exception {
        database.commitRawChunks();
    }

    /**
     * Not used in streaming mode.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    protected void flushChunks(List<TickDataChunk> chunks) throws Exception {
        throw new UnsupportedOperationException(
            "EnvironmentIndexer uses streaming raw-byte processing, not buffered flushChunks");
    }

    /**
     * Extracts EnvironmentProperties from SimulationMetadata.
     *
     * @param metadata Simulation metadata containing environment configuration
     * @return EnvironmentProperties for coordinate conversion
     */
    private EnvironmentProperties extractEnvironmentProperties(SimulationMetadata metadata) {
        return new EnvironmentProperties(
            MetadataConfigHelper.getEnvironmentShape(metadata),
            MetadataConfigHelper.isEnvironmentToroidal(metadata)
        );
    }

    // ==================== IMemoryEstimatable ====================

    /**
     * {@inheritDoc}
     * <p>
     * Estimates memory for the EnvironmentIndexer at worst-case.
     * <p>
     * With streaming raw-byte processing, peak memory is determined by two buffers
     * that coexist during compression: the uncompressed raw protobuf bytes (input)
     * and the compressed output. Worst-case assumes compression achieves no reduction
     * (random data), so both buffers are the same size.
     */
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        long bytesPerRawChunk = params.estimateSerializedBytesPerChunk();
        long peakBytes = 2 * bytesPerRawChunk;

        String explanation = String.format(
            "1 raw chunk ≤ %s + compression buffer ≤ %s (streaming, no parse)",
            SimulationParameters.formatBytes(bytesPerRawChunk),
            SimulationParameters.formatBytes(bytesPerRawChunk));

        return List.of(
            new MemoryEstimate(serviceName, peakBytes, explanation,
                               MemoryEstimate.Category.SERVICE_BATCH)
        );
    }
}
