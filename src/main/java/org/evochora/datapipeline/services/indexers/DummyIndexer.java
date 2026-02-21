package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.IResource;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test indexer for validating batch indexing infrastructure.
 * <p>
 * <strong>Scope:</strong>
 * <ul>
 *   <li>Extends {@link AbstractBatchIndexer} for batch processing</li>
 *   <li>Uses MetadataReadingComponent (waits for metadata before processing)</li>
 *   <li>Processes chunks from batch-topic</li>
 *   <li>Reads TickDataChunks from storage (length-delimited format)</li>
 *   <li>Logs chunk and tick counts (no database writes)</li>
 * </ul>
 * <p>
 * <strong>Purpose:</strong> Validate AbstractBatchIndexer infrastructure and component
 * system before implementing production indexers (EnvironmentIndexer, OrganismIndexer).
 * <p>
 * <strong>Thread Safety:</strong> This class is <strong>NOT thread-safe</strong>.
 * Each service instance must run in exactly one thread.
 *
 * @param <ACK> The acknowledgment token type (implementation-specific, e.g., H2's AckToken)
 */
public class DummyIndexer<ACK> extends AbstractBatchIndexer<ACK> {

    /**
     * Creates a new DummyIndexer.
     *
     * @param name Service name (must not be null/blank)
     * @param options Configuration for this indexer (must not be null)
     * @param resources Resources for this indexer (must not be null)
     */
    public DummyIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
    }

    @Override
    protected Set<ComponentType> getRequiredComponents() {
        return EnumSet.of(ComponentType.METADATA);
    }

    @Override
    protected void processChunk(TickDataChunk chunk) {
        log.debug("Processed chunk: ticks=[{}-{}], tickCount={} (DummyIndexer: no DB writes)",
            chunk.getFirstTick(), chunk.getLastTick(), chunk.getTickCount());
    }

    @Override
    protected void commitProcessedChunks() {
        log.debug("Committed processed chunks (DummyIndexer: no-op)");
    }

    @Override
    protected void logStarted() {
        log.info("DummyIndexer started: metadata=[pollInterval={}ms, maxPollDuration={}ms], topicPollTimeout={}ms",
            indexerOptions.hasPath("metadataPollIntervalMs") ? indexerOptions.getInt("metadataPollIntervalMs") : "default",
            indexerOptions.hasPath("metadataMaxPollDurationMs") ? indexerOptions.getInt("metadataMaxPollDurationMs") : "default",
            indexerOptions.hasPath("topicPollTimeoutMs") ? indexerOptions.getInt("topicPollTimeoutMs") : 5000);
    }
}
