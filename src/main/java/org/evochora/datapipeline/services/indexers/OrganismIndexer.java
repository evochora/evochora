package org.evochora.datapipeline.services.indexers;

import java.sql.SQLException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evochora.datapipeline.api.contracts.MutationEvent;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.StoredMutationEvent;
import org.evochora.datapipeline.api.contracts.StoredMutationEvents;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.ChunkFieldFilter;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareOrganismDataWriter;
import org.evochora.datapipeline.utils.MetadataConfigHelper;
import org.evochora.runtime.model.EnvironmentProperties;
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
     * The world the run took place in, needed to turn the flat index of a mutated cell into a
     * coordinate. Derived from the run metadata in {@link #prepareTables(String)}.
     */
    private EnvironmentProperties environmentProperties;

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
     * Prepares organism tables in the current run schema and derives the world of the run.
     *
     * @param runId Simulation run ID (schema already set by AbstractIndexer)
     * @throws Exception if preparation fails
     */
    @Override
    protected void prepareTables(String runId) throws Exception {
        this.environmentProperties = MetadataConfigHelper.environmentProperties(getMetadata());
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
        writeTick(chunk.getSnapshot());

        // Delta ticks (converted to TickData — the database needs the tick number, the organisms
        // and the running organism total, which it stores per tick rather than deriving it)
        for (TickDelta delta : chunk.getDeltasList()) {
            TickData deltaAsTick = TickData.newBuilder()
                .setTickNumber(delta.getTickNumber())
                .addAllOrganisms(delta.getOrganismsList())
                .setTotalOrganismsCreated(delta.getTotalOrganismsCreated())
                .build();
            writeTick(deltaAsTick);
        }
    }

    /**
     * Writes one tick, together with the mutation events of the organisms that carry any.
     *
     * @param tick the tick to write
     * @throws SQLException if the write fails
     */
    private void writeTick(TickData tick) throws SQLException {
        database.writeOrganismTick(tick, birthMutations(tick));
    }

    /**
     * Converts the mutation events of this tick's organisms into the form the organism table
     * holds, one serialized message per organism that carries any.
     * <p>
     * The conversion belongs here and not in the database: it needs the world of the run, which
     * the indexer reads from the metadata, and the storage strategy knows only its options, a
     * connection and the tick data. What crosses that seam is bytes.
     *
     * @param tick the tick whose organisms are examined
     * @return the serialized events per organism id, empty when no organism of the tick carries any
     * @throws IllegalStateException if an organism carries events before the world of the run is
     *         known, which would leave every offset undefined
     */
    private Map<Integer, byte[]> birthMutations(TickData tick) {
        Map<Integer, byte[]> converted = null;
        for (OrganismState org : tick.getOrganismsList()) {
            if (org.getBirthMutationsCount() == 0) {
                continue;
            }
            if (environmentProperties == null) {
                throw new IllegalStateException("Organism " + org.getOrganismId()
                    + " carries mutation events, but the world of the run is unknown because the "
                    + "tables were never prepared, so no offset can be computed for them");
            }
            if (converted == null) {
                converted = new HashMap<>();
            }
            converted.put(org.getOrganismId(),
                storedMutationEvents(environmentProperties, org).toByteArray());
        }
        return converted == null ? Map.of() : converted;
    }

    /**
     * Places the mutation events of one organism relative to its own origin.
     * <p>
     * The runtime reports a written cell as the absolute flat index the plugin held. The stored
     * form carries the offset from the organism's initial position instead, because a mutation an
     * ancestor received sits at the same offset from every descendant's origin, which is what lets
     * the events of a whole lineage be laid over one displayed body. The offset is taken along the
     * shortest way around the world, by the rule the genome hash is built with, so that the
     * positions of an event and of the hash it accompanies agree.
     *
     * @param environmentProperties the world the run took place in
     * @param org the organism state carrying the events and its initial position
     * @return the events with their cells placed relative to the organism
     * @throws IllegalStateException if the organism states an origin that does not fit that world
     */
    static StoredMutationEvents storedMutationEvents(EnvironmentProperties environmentProperties,
                                                     OrganismState org) {
        int dimensions = environmentProperties.getDimensions();
        Vector origin = org.getInitialPosition();
        if (origin.getComponentsCount() != dimensions) {
            throw new IllegalStateException("Organism " + org.getOrganismId()
                + " states an initial position with " + origin.getComponentsCount()
                + " components in a " + dimensions + "-dimensional world, so no offset can be "
                + "computed for its mutation events");
        }

        StoredMutationEvents.Builder events = StoredMutationEvents.newBuilder()
            .setDimensions(dimensions);
        int[] cellCoordinate = new int[dimensions];
        for (MutationEvent event : org.getBirthMutationsList()) {
            StoredMutationEvent.Builder stored = StoredMutationEvent.newBuilder()
                .setPluginClass(event.getPluginClass())
                .setKind(event.getKind())
                .addAllOldValues(event.getOldValuesList())
                .addAllNewValues(event.getNewValuesList())
                .addAllParams(event.getParamsList())
                .addAllDv(event.getDvList());
            for (int i = 0; i < event.getCellsCount(); i++) {
                environmentProperties.flatIndexToCoordinates(event.getCells(i), cellCoordinate);
                for (int d = 0; d < dimensions; d++) {
                    stored.addRelativeCoordinates(EnvironmentProperties.relativeOffset(
                        cellCoordinate[d],
                        origin.getComponents(d),
                        environmentProperties.getDimensionSize(d),
                        environmentProperties.isToroidal()));
                }
            }
            events.addEvents(stored);
        }
        return events.build();
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
        log.info("OrganismIndexer started: metadata=[pollInterval={}ms, maxPollDuration={}ms]",
                options.hasPath("metadataPollIntervalMs") ? options.getInt("metadataPollIntervalMs") : "default",
                options.hasPath("metadataMaxPollDurationMs") ? options.getInt("metadataMaxPollDurationMs") : "default");
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
