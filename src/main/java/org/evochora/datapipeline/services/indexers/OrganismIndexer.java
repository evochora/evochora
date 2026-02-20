package org.evochora.datapipeline.services.indexers;

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
import org.evochora.datapipeline.api.resources.storage.ChunkFieldFilter;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareOrganismDataWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * Streaming indexer for organism data (static and per-tick state).
 * <p>
 * <strong>Streaming Session Lifecycle:</strong>
 * <ol>
 *   <li>{@link #processChunk} — extracts ticks from snapshot+deltas, calls
 *       {@code writeOrganismTick} per tick (JDBC addBatch, no commit)</li>
 *   <li>{@link #commitProcessedChunks} — delegates to {@code commitOrganismWrites}
 *       (executeBatch + commit), resets deduplication state</li>
 * </ol>
 * <p>
 * Each parsed chunk is GC-eligible immediately after {@code processChunk} returns.
 * Peak heap is dominated by JDBC batch buffers with compressed BLOBs (~5 MB),
 * not by buffered parsed chunks.
 * <p>
 * <strong>Wire-level filtering:</strong> {@link ChunkFieldFilter#SKIP_CELLS} avoids
 * parsing ~550 MB of environment cell data per snapshot at the protobuf wire level.
 *
 * @param <ACK> Topic acknowledgment token type
 */
public class OrganismIndexer<ACK> extends AbstractBatchIndexer<ACK> implements IMemoryEstimatable {

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

    @Override
    protected Set<ComponentType> getRequiredComponents() {
        return EnumSet.of(ComponentType.METADATA);
    }

    @Override
    protected Set<ComponentType> getOptionalComponents() {
        return EnumSet.of(ComponentType.DLQ);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Skips cell/environment data at the wire level. The OrganismIndexer only needs organism
     * states, saving ~550 MB of heap per snapshot.
     */
    @Override
    protected ChunkFieldFilter getChunkFieldFilter() {
        return ChunkFieldFilter.SKIP_CELLS;
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
     * Processes a single chunk by extracting ticks and writing each to the database.
     * <p>
     * Extracts organism data from the snapshot and all deltas, creating a lightweight
     * {@link TickData} for each and calling {@code writeOrganismTick}. The database
     * strategy performs {@code addBatch()} internally — no commit happens here.
     *
     * @param chunk The filtered chunk (cells already stripped by SKIP_CELLS)
     * @throws Exception if write fails
     */
    @Override
    protected void processChunk(TickDataChunk chunk) throws Exception {
        // Snapshot tick
        database.writeOrganismTick(chunk.getSnapshot());

        // Delta ticks (converted to TickData — database only needs tickNumber + organisms)
        for (TickDelta delta : chunk.getDeltasList()) {
            TickData deltaAsTick = TickData.newBuilder()
                .setTickNumber(delta.getTickNumber())
                .addAllOrganisms(delta.getOrganismsList())
                .build();
            database.writeOrganismTick(deltaAsTick);
        }
    }

    /**
     * Commits all organism data accumulated since the last commit.
     * <p>
     * Delegates to {@code commitOrganismWrites} which executes JDBC batches,
     * commits the transaction, and resets deduplication state.
     *
     * @throws Exception if commit fails
     */
    @Override
    protected void commitProcessedChunks() throws Exception {
        database.commitOrganismWrites();
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
     * Estimates memory for the OrganismIndexer streaming session.
     * <p>
     * With streaming, no parsed chunks are buffered. Peak heap consists of:
     * <ul>
     *   <li>JDBC batch buffers: {@code insertBatchSize × samplesPerChunk × estimatedBytesPerTickBlob}</li>
     *   <li>One parsed chunk transient: organisms-only (SKIP_CELLS) per tick</li>
     * </ul>
     */
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        int batchSize = getInsertBatchSize();

        // JDBC batch buffers: compressed BLOBs accumulated between commits
        // Each tick produces one compressed BLOB (~20 KB for SingleBlobOrgStrategy)
        long estimatedBlobBytesPerTick = 20L * 1024; // Conservative upper bound
        long batchBufferBytes = (long) batchSize * params.samplesPerChunk() * estimatedBlobBytesPerTick;

        String batchExplanation = String.format(
            "%d insertBatchSize × %d samples/chunk × %s/tick BLOB (JDBC batch buffers)",
            batchSize,
            params.samplesPerChunk(),
            SimulationParameters.formatBytes(estimatedBlobBytesPerTick));

        // One parsed chunk transient: organisms-only (SKIP_CELLS strips cells at wire level)
        long bytesPerSample = params.estimateOrganismBytesPerTick() + SimulationParameters.TICKDATA_WRAPPER_OVERHEAD;
        long transientChunkBytes = (long) params.samplesPerChunk() * bytesPerSample;

        String transientExplanation = String.format(
            "One parsed chunk transient: %d samples/chunk × %s/sample (organisms-only, SKIP_CELLS)",
            params.samplesPerChunk(),
            SimulationParameters.formatBytes(bytesPerSample));

        return List.of(
            new MemoryEstimate(serviceName, batchBufferBytes, batchExplanation, MemoryEstimate.Category.SERVICE_BATCH),
            new MemoryEstimate(serviceName + " (chunk transient)", transientChunkBytes, transientExplanation, MemoryEstimate.Category.SERVICE_BATCH)
        );
    }
}
