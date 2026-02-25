package org.evochora.datapipeline.services.indexers;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.IRetryTracker;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareMetadataReader;
import org.evochora.datapipeline.api.resources.queues.IDeadLetterQueueResource;
import org.evochora.datapipeline.api.resources.storage.ChunkFieldFilter;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
import org.evochora.datapipeline.services.indexers.components.DlqComponent;
import org.evochora.datapipeline.services.indexers.components.IdempotencyComponent;
import org.evochora.datapipeline.services.indexers.components.MetadataReadingComponent;

import com.typesafe.config.Config;

/**
 * Abstract base class for batch indexers that process TickDataChunks from batch notifications.
 * <p>
 * Extends {@link AbstractIndexer} with batch-specific functionality:
 * <ul>
 *   <li>Subscribes to batch-topic for BatchInfo notifications</li>
 *   <li>Reads TickDataChunks from storage via streaming ({@code forEachChunk}/{@code forEachRawChunk})</li>
 *   <li>Optional components: metadata reading, DLQ, idempotency</li>
 *   <li>Template methods {@link #processChunk} and {@link #commitProcessedChunks} for database writes</li>
 * </ul>
 * <p>
 * <strong>Streaming Processing:</strong> Chunks are processed one at a time via
 * {@link #processChunk}. Commits are triggered every {@code insertBatchSize} chunks
 * or on timeout. Each parsed chunk is GC-eligible immediately after {@code processChunk}
 * returns. Peak heap usage is O(chunkSize), not O(n &times; chunkSize).
 * <p>
 * <strong>Component System:</strong> Subclasses declare which components to use via
 * {@link #getRequiredComponents()}. Components are created automatically by the
 * final {@link #createComponents()} method.
 * <p>
 * <strong>Thread Safety:</strong> This class is <strong>NOT thread-safe</strong>.
 * Each service instance must run in exactly one thread. Components are also
 * not thread-safe and created per-instance.
 * <p>
 * <strong>Minimal Subclass Implementation:</strong>
 * <pre>
 * public class MyIndexer&lt;ACK&gt; extends AbstractBatchIndexer&lt;ACK&gt; {
 *     // Required: Chunk processing logic
 *     protected void processChunk(TickDataChunk chunk) throws Exception {
 *         // Write to database using MERGE (not INSERT!)
 *     }
 *
 *     // Required: Commit accumulated writes
 *     protected void commitProcessedChunks() throws Exception {
 *         // Execute JDBC batches and commit
 *     }
 * }
 * </pre>
 *
 * @param <ACK> The acknowledgment token type (implementation-specific, e.g., H2's AckToken)
 */
public abstract class AbstractBatchIndexer<ACK> extends AbstractIndexer<BatchInfo, ACK> {

    private final BatchIndexerComponents components;
    private final int topicPollTimeoutMs;

    // Metrics (shared by all batch indexers)
    private final AtomicLong batchesProcessed = new AtomicLong(0);
    private final AtomicLong ticksProcessed = new AtomicLong(0);
    private final AtomicLong batchesMovedToDlq = new AtomicLong(0);

    // Streaming processing state
    private final StreamingAckTracker<ACK> streamingTracker;
    private final int streamingInsertBatchSize;
    private final long streamingFlushTimeoutMs;
    private int streamingUncommittedChunks;
    private long streamingLastCommitTime;

    /**
     * Creates a new batch indexer.
     * <p>
     * Automatically calls {@link #createComponents()} to initialize components
     * based on {@link #getRequiredComponents()}.
     *
     * @param name Service name (must not be null/blank)
     * @param options Configuration for this indexer (must not be null)
     * @param resources Resources for this indexer (must not be null)
     */
    protected AbstractBatchIndexer(String name,
                                   Config options,
                                   Map<String, List<IResource>> resources) {
        super(name, options, resources);

        // Template method: Let subclass configure components
        this.components = createComponents();

        // Initialize streaming processing
        this.streamingInsertBatchSize = options.hasPath("insertBatchSize")
            ? options.getInt("insertBatchSize") : 5;
        this.streamingFlushTimeoutMs = options.hasPath("flushTimeoutMs")
            ? options.getLong("flushTimeoutMs") : 5000L;
        this.streamingTracker = new StreamingAckTracker<>();
        this.streamingLastCommitTime = System.currentTimeMillis();
        this.topicPollTimeoutMs = (int) this.streamingFlushTimeoutMs;
    }

    /**
     * Component types available for batch indexers.
     * <p>
     * Used by {@link #getRequiredComponents()} to declare which components to use.
     */
    public enum ComponentType {
        /** Metadata reading component (polls DB for simulation metadata). */
        METADATA,

        /** Idempotency component (skip duplicate storage reads). */
        IDEMPOTENCY,

        /** DLQ component (move poison messages to DLQ after max retries). */
        DLQ
    }

    /**
     * Template method: Declare which components are REQUIRED for this indexer.
     * <p>
     * Required components MUST have their resources configured. If resources are missing,
     * the service will fail to start with an exception.
     * <p>
     * <strong>Default:</strong> Returns METADATA (standard batch indexer setup).
     * <p>
     * <strong>Examples:</strong>
     * <pre>
     * // Use default (no override needed for standard case!)
     *
     * // No components at all (direct Topic processing)
     * &#64;Override
     * protected Set&lt;ComponentType&gt; getRequiredComponents() {
     *     return EnumSet.noneOf(ComponentType.class);
     * }
     * </pre>
     *
     * <strong>Thread Safety:</strong> Called once during construction from the single service thread.
     *
     * @return set of required component types (never null)
     */
    protected Set<ComponentType> getRequiredComponents() {
        return EnumSet.of(ComponentType.METADATA);
    }

    /**
     * Template method: Declare which components are OPTIONAL for this indexer.
     * <p>
     * Optional components are created only if their resources are configured. If resources
     * are missing, the component is silently skipped (graceful degradation, no error).
     * <p>
     * <strong>Default:</strong> Returns empty set (no optional components).
     * <p>
     * IDEMPOTENCY and DLQ are available as optional components.
     * <p>
     * <strong>Examples:</strong>
     * <pre>
     * // Use default (no override needed if no optional components!)
     *
     * // With optional DLQ for poison message handling
     * &#64;Override
     * protected Set&lt;ComponentType&gt; getOptionalComponents() {
     *     return EnumSet.of(ComponentType.DLQ);
     * }
     *
     * // With optional idempotency + DLQ
     * &#64;Override
     * protected Set&lt;ComponentType&gt; getOptionalComponents() {
     *     return EnumSet.of(ComponentType.IDEMPOTENCY, ComponentType.DLQ);
     * }
     * </pre>
     *
     * <strong>Thread Safety:</strong> Called once during construction from the single service thread.
     *
     * @return set of optional component types (never null)
     */
    protected Set<ComponentType> getOptionalComponents() {
        return EnumSet.noneOf(ComponentType.class);
    }

    /**
     * Creates components based on {@link #getRequiredComponents()} and {@link #getOptionalComponents()}.
     * <p>
     * <strong>FINAL:</strong> Subclasses must NOT override this method.
     * Instead, override {@link #getRequiredComponents()} and {@link #getOptionalComponents()}.
     * <p>
     * <strong>Required components:</strong> Resources MUST be configured, service fails to start if missing.
     * <p>
     * <strong>Optional components:</strong> Resources MAY be configured, graceful degradation if missing.
     * <p>
     * All components use standardized config parameters:
     * <ul>
     *   <li>Metadata: {@code metadataPollIntervalMs}, {@code metadataMaxPollDurationMs}</li>
     *   <li>DLQ: {@code maxRetries}</li>
     * </ul>
     *
     * @return component configuration (may be null if no components requested)
     */
    protected final BatchIndexerComponents createComponents() {
        Set<ComponentType> required = getRequiredComponents();
        Set<ComponentType> optional = getOptionalComponents();

        if (required.isEmpty() && optional.isEmpty()) return null;

        var builder = BatchIndexerComponents.builder();

        // Component 1: Metadata Reading
        // REQUIRED component - exception if resource missing
        if (required.contains(ComponentType.METADATA)) {
            IResourceSchemaAwareMetadataReader metadataReader = getRequiredResource("metadata", IResourceSchemaAwareMetadataReader.class);
            int pollIntervalMs = indexerOptions.getInt("metadataPollIntervalMs");
            int maxPollDurationMs = indexerOptions.getInt("metadataMaxPollDurationMs");
            builder.withMetadata(new MetadataReadingComponent(
                metadataReader, pollIntervalMs, maxPollDurationMs));
        }
        // OPTIONAL metadata component - graceful skip if resource missing
        else if (optional.contains(ComponentType.METADATA)) {
            getOptionalResource("metadata", IResourceSchemaAwareMetadataReader.class).ifPresent(metadataReader -> {
                int pollIntervalMs = indexerOptions.getInt("metadataPollIntervalMs");
                int maxPollDurationMs = indexerOptions.getInt("metadataMaxPollDurationMs");
                builder.withMetadata(new MetadataReadingComponent(
                    metadataReader, pollIntervalMs, maxPollDurationMs));
            });
        }

        // Component 2: Idempotency
        // REQUIRED component - exception if resource missing
        if (required.contains(ComponentType.IDEMPOTENCY)) {
            @SuppressWarnings("unchecked")
            IIdempotencyTracker<String> tracker = (IIdempotencyTracker<String>) getRequiredResource("idempotency", IIdempotencyTracker.class);
            String indexerClass = this.getClass().getSimpleName();
            builder.withIdempotency(new IdempotencyComponent(tracker, indexerClass));
        }
        // OPTIONAL idempotency component - graceful skip if resource missing
        else if (optional.contains(ComponentType.IDEMPOTENCY)) {
            getOptionalResource("idempotency", IIdempotencyTracker.class).ifPresent(rawTracker -> {
                @SuppressWarnings("unchecked")
                IIdempotencyTracker<String> tracker = (IIdempotencyTracker<String>) rawTracker;
                String indexerClass = this.getClass().getSimpleName();
                builder.withIdempotency(new IdempotencyComponent(tracker, indexerClass));
            });
        }

        // Component 3: DLQ
        // DLQ requires BOTH retryTracker AND dlq resources to be present
        // REQUIRED component - exception if resources missing
        if (required.contains(ComponentType.DLQ)) {
            IRetryTracker retryTracker = getRequiredResource("retryTracker", IRetryTracker.class);
            @SuppressWarnings("unchecked")
            IDeadLetterQueueResource<BatchInfo> dlq = (IDeadLetterQueueResource<BatchInfo>) getRequiredResource("dlq", IDeadLetterQueueResource.class);
            int maxRetries = indexerOptions.hasPath("maxRetries")
                ? indexerOptions.getInt("maxRetries")
                : 3;  // Default: 3 retries
            builder.withDlq(new DlqComponent<>(retryTracker, dlq, maxRetries, this.serviceName));
        }
        // OPTIONAL DLQ component - graceful skip if resources missing
        else if (optional.contains(ComponentType.DLQ)) {
            var retryTrackerOpt = getOptionalResource("retryTracker", IRetryTracker.class);
            var dlqOpt = getOptionalResource("dlq", IDeadLetterQueueResource.class);

            if (retryTrackerOpt.isPresent() && dlqOpt.isPresent()) {
                @SuppressWarnings("unchecked")
                IDeadLetterQueueResource<BatchInfo> dlq = (IDeadLetterQueueResource<BatchInfo>) dlqOpt.get();
                int maxRetries = indexerOptions.hasPath("maxRetries")
                    ? indexerOptions.getInt("maxRetries")
                    : 3;  // Default: 3 retries
                builder.withDlq(new DlqComponent<>(
                    retryTrackerOpt.get(),
                    dlq,
                    maxRetries,
                    this.serviceName
                ));
            } else {
                // Warn if partial configuration (both resources required for DLQ)
                if (retryTrackerOpt.isEmpty() && dlqOpt.isPresent()) {
                    log.warn("DLQ resource configured but retryTracker missing - DLQ component not created (poison messages will rotate indefinitely)");
                } else if (retryTrackerOpt.isPresent() && dlqOpt.isEmpty()) {
                    log.warn("RetryTracker configured but DLQ resource missing - DLQ component not created (poison messages will rotate indefinitely)");
                }
                // Both missing: silent (expected for minimal config)
            }
        }

        return builder.build();
    }

    @Override
    protected final void indexRun(String runId) throws Exception {
        try {
            // Step 1: Wait for metadata (if component exists)
            if (components != null && components.metadata != null) {
                components.metadata.loadMetadata(runId);
                log.debug("Metadata loaded for run: {}", runId);
            }

            // Step 2: Prepare tables (template method hook for subclasses)
            // Called AFTER metadata is loaded, so subclasses can use getMetadata()
            // for schema-dependent table creation (e.g., dimensions for EnvironmentIndexer)
            prepareTables(runId);

            // Step 3: Topic loop
            // Check both isStopRequested() (graceful) and isInterrupted() (forced)
            while (!isStopRequested() && !Thread.currentThread().isInterrupted()) {
                TopicMessage<BatchInfo, ACK> msg = topic.poll(topicPollTimeoutMs, TimeUnit.MILLISECONDS);

                if (msg == null) {
                    // Timeout-based commit
                    if (streamingUncommittedChunks > 0
                        && (System.currentTimeMillis() - streamingLastCommitTime) >= streamingFlushTimeoutMs) {
                        setShutdownPhase(ShutdownPhase.PROCESSING);
                        Thread.interrupted();
                        try {
                            streamingCommitAndAck();
                        } catch (Exception e) {
                            log.warn("Streaming commit failed (uncommitted chunks discarded, will be reprocessed on redelivery): {}", e.getMessage());
                            recordError("STREAMING_COMMIT_FAILED", "Streaming commit failed",
                                "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                            streamingTracker.clear();
                            streamingUncommittedChunks = 0;
                            streamingLastCommitTime = System.currentTimeMillis();
                        } finally {
                            setShutdownPhase(ShutdownPhase.WAITING);
                        }
                    }
                    continue;
                }

                setShutdownPhase(ShutdownPhase.PROCESSING);
                Thread.interrupted();
                try {
                    processBatchMessage(msg);
                } finally {
                    setShutdownPhase(ShutdownPhase.WAITING);
                }
            }
        } finally {
            // Final commit of remaining data (always executed, even on interrupt!)
            if (streamingUncommittedChunks > 0) {
                setShutdownPhase(ShutdownPhase.PROCESSING);
                boolean wasInterrupted = Thread.interrupted();
                try {
                    streamingCommitAndAck();
                } catch (Exception e) {
                    log.warn("Final streaming commit failed during shutdown: {}", e.getMessage());
                } finally {
                    setShutdownPhase(ShutdownPhase.WAITING);
                    if (wasInterrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            // Hook for subclass cleanup (called after final commit)
            try {
                onShutdown();
            } catch (Exception e) {
                log.warn("Error during indexer shutdown cleanup: {}", e.getMessage());
                log.debug("Shutdown cleanup error details:", e);
            }
        }
    }

    /**
     * Template method hook for cleanup operations when the indexer shuts down.
     * <p>
     * Called automatically in the finally block after final commit completes.
     * Subclasses can override this to perform cleanup (e.g., closing files, flushing buffers).
     * <p>
     * <strong>Error Handling:</strong> Exceptions are caught and logged as warnings.
     * They do not prevent shutdown from completing.
     *
     * @throws Exception if cleanup fails (will be caught and logged)
     */
    protected void onShutdown() throws Exception {
        // Default: no-op (subclasses override)
    }

    /**
     * Processes a single batch notification message.
     * <p>
     * Reads TickDataChunks from storage one at a time, processes each via
     * {@link #processChunk}, and acknowledges message after successful processing.
     * <p>
     * <strong>Error Handling:</strong> Transient errors (storage read failures, database
     * write failures) are logged as WARN and tracked. The batch is NOT acknowledged,
     * causing topic redelivery after claimTimeout. The indexer continues processing
     * other batches, allowing recovery from transient failures without service restart.
     *
     * @param msg Topic message containing BatchInfo
     * @throws InterruptedException if service is shutting down
     */
    private void processBatchMessage(TopicMessage<BatchInfo, ACK> msg) throws InterruptedException {
        BatchInfo batch = msg.payload();
        String batchId = batch.getStoragePath();

        log.debug("Received BatchInfo: storagePath={}, ticks=[{}-{}]",
            batch.getStoragePath(), batch.getTickStart(), batch.getTickEnd());

        // Idempotency check (skip storage read if already processed)
        if (components != null && components.idempotency != null) {
            if (components.idempotency.isProcessed(batchId)) {
                log.debug("Skipping duplicate batch (performance optimization): {}", batchId);
                topic.ack(msg);
                batchesProcessed.incrementAndGet();
                return;
            }
        }

        try {
            StoragePath storagePath = StoragePath.of(batch.getStoragePath());

            // Streaming path: process one chunk at a time via forEachChunk.
            // DLQ retry reset and idempotency marking happen in streamingCommitAndAck()
            // after the batch is actually committed.
            processStreamingBatch(batch, batchId, storagePath, msg);

        } catch (InterruptedException e) {
            // Normal shutdown - re-throw to stop service
            log.debug("Interrupted while processing batch: {}", batchId);
            throw e;

        } catch (Exception e) {
            // Transient error - log, track, but DON'T stop indexer
            log.warn("Failed to process batch (will be redelivered after claimTimeout): {}: {}", batchId, e.getMessage());
            recordError("BATCH_PROCESSING_FAILED", "Batch processing failed",
                       "BatchId: " + batchId + ", Error: " + e.getClass().getSimpleName() + ": " + e.getMessage());

            // DLQ check (if component configured)
            if (components != null && components.dlq != null) {
                if (components.dlq.shouldMoveToDlq(batchId)) {
                    log.warn("Moving batch to DLQ after max retries: {}", batchId);
                    try {
                        @SuppressWarnings("unchecked")
                        DlqComponent<BatchInfo, ACK> dlqComponent = (DlqComponent<BatchInfo, ACK>) components.dlq;
                        dlqComponent.moveToDlq(msg, e, batchId);
                        topic.ack(msg);  // ACK original - now in DLQ
                        batchesProcessed.incrementAndGet();  // Count as processed
                        batchesMovedToDlq.incrementAndGet();  // Track DLQ moves
                        return;  // Successfully moved to DLQ
                    } catch (InterruptedException ie) {
                        log.debug("Interrupted while moving batch to DLQ: {}", batchId);
                        Thread.currentThread().interrupt();  // Restore interrupt status
                        return;  // Exit gracefully on shutdown
                    }
                }
            }

            // NO throw - indexer continues processing other batches!
            // NO ack - batch remains unclaimed, topic will reassign after claimTimeout
        }
    }

    /**
     * Processes a batch using the streaming path.
     * <p>
     * Reads chunks one at a time via {@code forEachChunk}, calling {@link #processChunk}
     * for each. Commits are triggered every {@code insertBatchSize} chunks. Batch completion
     * and ACK tracking are managed by {@link StreamingAckTracker}.
     *
     * @param batch     The batch info from the topic message
     * @param batchId   The batch identifier (storage path)
     * @param path      The resolved storage path
     * @param msg       The topic message to ACK after all chunks are committed
     * @throws Exception if reading, processing, or committing fails
     */
    private void processStreamingBatch(BatchInfo batch, String batchId, StoragePath path,
                                       TopicMessage<BatchInfo, ACK> msg) throws Exception {
        streamingTracker.registerBatch(batchId, msg);

        try {
            readAndProcessChunks(path, batchId);

            streamingTracker.completeBatch(batchId);
            ackCompletedBatches();

            log.debug("Streamed batch: {}, ticks=[{}-{}]",
                batch.getStoragePath(), batch.getTickStart(), batch.getTickEnd());
        } catch (Exception e) {
            streamingTracker.removeBatch(batchId);
            throw e;
        }
    }

    /**
     * Reads chunks from storage and processes them one at a time.
     * <p>
     * Default implementation uses {@link #forEachChunk} with the configured
     * {@link #getChunkFieldFilter()}, calling {@link #processChunk} per chunk
     * and {@link #onChunkStreamed} for tracker/commit bookkeeping.
     * <p>
     * Subclasses can override to use a different read strategy (e.g., raw-byte
     * streaming via {@code forEachRawChunk} for pass-through storage).
     *
     * @param path    The resolved storage path
     * @param batchId The batch identifier for tracker bookkeeping
     * @throws Exception if reading, processing, or committing fails
     */
    protected void readAndProcessChunks(StoragePath path, String batchId) throws Exception {
        storage.forEachChunk(path, getChunkFieldFilter(), chunk -> {
            processChunk(chunk);
            onChunkStreamed(batchId, chunk.getTickCount());
        });
    }

    /**
     * Records a streamed chunk for tracker and commit bookkeeping.
     * <p>
     * Must be called once per chunk in {@link #readAndProcessChunks} after the chunk
     * has been processed. Tracks chunk completion in {@link StreamingAckTracker} and
     * triggers a commit when {@code insertBatchSize} uncommitted chunks have accumulated.
     *
     * @param batchId   The batch identifier
     * @param tickCount Number of ticks in the processed chunk
     * @throws Exception if commit fails
     */
    protected final void onChunkStreamed(String batchId, int tickCount) throws Exception {
        streamingTracker.onChunkProcessed(batchId, tickCount);
        streamingUncommittedChunks++;

        if (streamingUncommittedChunks >= streamingInsertBatchSize) {
            streamingCommitAndAck();
        }

        Thread.yield();
    }

    /**
     * Commits processed chunks and acknowledges completed batches.
     * <p>
     * Called when the uncommitted chunk count reaches {@code insertBatchSize}, on poll
     * timeout, or during shutdown. After committing, drains all fully completed batches
     * from the tracker and ACKs their topic messages.
     *
     * @throws Exception if commit or ACK fails
     */
    private void streamingCommitAndAck() throws Exception {
        commitProcessedChunks();
        streamingTracker.onCommit();
        ackCompletedBatches();

        streamingUncommittedChunks = 0;
        streamingLastCommitTime = System.currentTimeMillis();
    }

    /**
     * Drains and ACKs all fully committed and completed batches from the streaming tracker.
     * <p>
     * A batch is eligible for ACK when all its chunks have been both processed (streamed
     * from storage) and committed (written to database). Called after every commit in
     * {@link #streamingCommitAndAck()}, and also after {@link StreamingAckTracker#completeBatch}
     * to handle the case where all chunks were already committed before the batch finished
     * streaming (chunk count is exact multiple of {@code insertBatchSize}).
     */
    @SuppressWarnings("unchecked")
    private void ackCompletedBatches() {
        for (StreamingAckTracker.CompletedBatch<ACK> completed : streamingTracker.drainCompleted()) {
            TopicMessage<BatchInfo, ACK> batchMsg = (TopicMessage<BatchInfo, ACK>) completed.message();
            topic.ack(batchMsg);
            batchesProcessed.incrementAndGet();
            ticksProcessed.addAndGet(completed.ticksProcessed());

            if (components != null && components.idempotency != null) {
                components.idempotency.markProcessed(completed.batchId());
            }
            if (components != null && components.dlq != null) {
                components.dlq.resetRetryCount(completed.batchId());
            }
        }
    }

    /**
     * Gets the simulation metadata.
     * <p>
     * This method provides access to metadata loaded by the MetadataReadingComponent.
     * Only available after the component has successfully loaded metadata.
     * <p>
     * <strong>Usage:</strong> Typically called in {@link #prepareTables(String)} to extract
     * environment properties, organism configurations, or other metadata needed for indexing.
     *
     * @return The simulation metadata
     * @throws IllegalStateException if metadata component is not configured or metadata not yet loaded
     */
    protected final SimulationMetadata getMetadata() {
        if (components == null || components.metadata == null) {
            throw new IllegalStateException(
                "Metadata component not available. Override getRequiredComponents() to include IndexerComponent.METADATA.");
        }
        return components.metadata.getMetadata();
    }

    /**
     * Template method hook for table preparation before batch processing.
     * <p>
     * Called automatically by {@link #indexRun(String)} after metadata is loaded
     * (if {@link ComponentType#METADATA} is enabled) but before topic processing begins.
     * <p>
     * <strong>Default implementation:</strong> Does nothing (no-op).
     * <p>
     * <strong>Usage:</strong> Override to create tables with metadata-dependent schemas.
     * Use {@link #getMetadata()} to access loaded metadata.
     * <p>
     * <strong>Idempotency:</strong> Must use CREATE TABLE IF NOT EXISTS for safety.
     * Multiple indexer instances may call this concurrently.
     *
     * @param runId The simulation run ID (schema already set by AbstractIndexer)
     * @throws Exception if table preparation fails
     */
    protected void prepareTables(String runId) throws Exception {
        // Default: no-op (subclasses override to create tables)
    }

    /**
     * Returns the field filter for wire-level protobuf field skipping during chunk parsing.
     * <p>
     * Override in subclasses to skip heavy fields that this indexer does not need.
     * Skipped fields are discarded at the {@link com.google.protobuf.CodedInputStream} level
     * without allocating Java objects, saving hundreds of MB per chunk.
     * <p>
     * <strong>Default:</strong> {@link ChunkFieldFilter#ALL} (no fields skipped).
     *
     * <strong>Thread Safety:</strong> Called from the single service thread only.
     *
     * @return The field filter to apply during chunk parsing
     */
    protected ChunkFieldFilter getChunkFieldFilter() {
        return ChunkFieldFilter.ALL;
    }

    /**
     * Returns the configured insert batch size for streaming processing.
     * <p>
     * This controls how many chunks are accumulated before a commit is triggered.
     *
     * <strong>Thread Safety:</strong> Called from the single service thread only.
     *
     * @return The insert batch size (default: 5)
     */
    protected int getInsertBatchSize() {
        return streamingInsertBatchSize;
    }

    /**
     * Processes a single chunk during streaming processing.
     * <p>
     * Called once per chunk during {@code forEachChunk} iteration. Implementations should
     * write the chunk's data to the database without committing — commits are handled by
     * {@link #commitProcessedChunks()} based on {@code insertBatchSize}.
     *
     * <strong>Thread Safety:</strong> Called from the single service thread only.
     *
     * @param chunk The filtered and transformed chunk to process
     * @throws Exception if processing fails
     */
    protected abstract void processChunk(TickDataChunk chunk) throws Exception;

    /**
     * Commits all chunks processed since the last commit.
     * <p>
     * Called when {@code insertBatchSize} chunks have been processed, on timeout, or
     * during shutdown. Implementations should commit all accumulated database writes.
     *
     * <strong>Thread Safety:</strong> Called from the single service thread only.
     *
     * @throws Exception if commit fails
     */
    protected abstract void commitProcessedChunks() throws Exception;

    /**
     * Adds batch indexer metrics to the metrics map.
     * <p>
     * Subclasses can override to add their own metrics, but must call super.
     *
     * @param metrics Metrics map to populate
     */
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);

        metrics.put("batches_processed", batchesProcessed.get());
        metrics.put("ticks_processed", ticksProcessed.get());
        metrics.put("batches_moved_to_dlq", batchesMovedToDlq.get());
    }

    /**
     * Tracks batch completion and commit progress for streaming processing.
     * <p>
     * Manages the relationship between chunk processing, commits, and batch ACKs.
     * A batch can only be ACKed when all its chunks are both processed AND committed.
     * <p>
     * <strong>Thread Safety:</strong> Not thread-safe. Used only from the single service thread.
     *
     * @param <ACK> The acknowledgment token type
     */
    static class StreamingAckTracker<ACK> {

        private final List<PendingBatch<ACK>> pending = new ArrayList<>();

        /**
         * Registers a new batch for tracking.
         *
         * @param batchId The batch identifier
         * @param msg     The topic message to ACK when the batch is fully committed
         */
        void registerBatch(String batchId, TopicMessage<?, ACK> msg) {
            pending.add(new PendingBatch<>(batchId, msg));
        }

        /**
         * Records that a chunk from the specified batch has been processed.
         *
         * @param batchId   The batch identifier
         * @param tickCount Number of ticks in the processed chunk
         */
        void onChunkProcessed(String batchId, int tickCount) {
            PendingBatch<ACK> batch = findBatch(batchId);
            batch.chunksProcessed++;
            batch.ticksProcessed += tickCount;
        }

        /**
         * Marks a batch as complete (all chunks have been streamed from storage).
         *
         * @param batchId The batch identifier
         */
        void completeBatch(String batchId) {
            findBatch(batchId).complete = true;
        }

        /**
         * Records that a commit has occurred, advancing the committed chunk count
         * for all pending batches to match their processed chunk count.
         * <p>
         * <strong>Invariant:</strong> This assumes a single {@code commitProcessedChunks()} call
         * atomically commits data for all pending batches (single JDBC connection, single commit).
         * If the commit model ever changes to per-batch commits, this method must be revised.
         */
        void onCommit() {
            for (PendingBatch<ACK> b : pending) {
                b.chunksCommitted = b.chunksProcessed;
            }
        }

        /**
         * Removes all pending batches from the tracker.
         * <p>
         * Called after a commit failure to discard stale state. Without this,
         * redelivered messages would create duplicate entries that block
         * {@link #drainCompleted()}.
         */
        void clear() {
            pending.clear();
        }

        /**
         * Removes a batch from tracking (used on processing failure).
         *
         * @param batchId The batch identifier to remove
         */
        void removeBatch(String batchId) {
            Iterator<PendingBatch<ACK>> it = pending.iterator();
            while (it.hasNext()) {
                if (it.next().batchId.equals(batchId)) {
                    it.remove();
                    return;
                }
            }
        }

        /**
         * Drains completed batches from the front of the pending list.
         * <p>
         * A batch is complete when all chunks have been streamed ({@code complete == true})
         * and all processed chunks have been committed ({@code chunksCommitted >= chunksProcessed}).
         * Stops at the first incomplete batch to preserve processing order.
         *
         * @return List of completed batches (removed from pending)
         */
        List<CompletedBatch<ACK>> drainCompleted() {
            List<CompletedBatch<ACK>> completed = new ArrayList<>();
            Iterator<PendingBatch<ACK>> it = pending.iterator();
            while (it.hasNext()) {
                PendingBatch<ACK> b = it.next();
                if (b.complete && b.chunksCommitted >= b.chunksProcessed) {
                    completed.add(new CompletedBatch<>(b.batchId, b.message, b.ticksProcessed));
                    it.remove();
                } else {
                    break;
                }
            }
            return completed;
        }

        /**
         * Completed batch information for ACK processing.
         *
         * @param batchId        The batch identifier
         * <strong>Thread Safety:</strong> Immutable record, inherently thread-safe.
         *
         * @param message        The topic message to ACK
         * @param ticksProcessed Total ticks processed in this batch
         * @param <ACK>          The acknowledgment token type
         */
        record CompletedBatch<ACK>(String batchId, TopicMessage<?, ACK> message, int ticksProcessed) {}

        private PendingBatch<ACK> findBatch(String batchId) {
            for (int i = pending.size() - 1; i >= 0; i--) {
                if (pending.get(i).batchId.equals(batchId)) {
                    return pending.get(i);
                }
            }
            throw new IllegalStateException("Unknown batch: " + batchId);
        }

        private static class PendingBatch<ACK> {
            final String batchId;
            final TopicMessage<?, ACK> message;
            int chunksProcessed;
            int chunksCommitted;
            boolean complete;
            int ticksProcessed;

            PendingBatch(String batchId, TopicMessage<?, ACK> msg) {
                this.batchId = batchId;
                this.message = msg;
            }
        }
    }

    /**
     * Component configuration for batch indexers.
     * <p>
     * <strong>Thread Safety:</strong> Components are <strong>NOT thread-safe</strong>.
     * Each service instance creates its own component instances. Never share
     * components between service instances or threads.
     * <p>
     * Use builder pattern for extensibility:
     * <pre>
     * BatchIndexerComponents.builder()
     *     .withMetadata(...)
     *     .withIdempotency(...)
     *     .withDlq(...)
     *     .build()
     * </pre>
     */
    public static class BatchIndexerComponents {
        /** Metadata reading component (optional). */
        public final MetadataReadingComponent metadata;

        /** Idempotency component (optional). */
        public final IdempotencyComponent idempotency;

        /** DLQ component (optional). */
        public final DlqComponent<BatchInfo, Object> dlq;

        private BatchIndexerComponents(MetadataReadingComponent metadata,
                                       IdempotencyComponent idempotency,
                                       DlqComponent<BatchInfo, Object> dlq) {
            this.metadata = metadata;
            this.idempotency = idempotency;
            this.dlq = dlq;
        }

        /**
         * Creates a new builder for BatchIndexerComponents.
         *
         * @return new builder instance
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Builder for BatchIndexerComponents.
         * <p>
         * Enables fluent API for component configuration and ensures
         * {@code final} fields in BatchIndexerComponents.
         */
        public static class Builder {
            private MetadataReadingComponent metadata;
            private IdempotencyComponent idempotency;
            private DlqComponent<BatchInfo, Object> dlq;

            /**
             * Configures metadata reading component.
             *
             * @param c Metadata reading component (may be null)
             * @return this builder for chaining
             */
            public Builder withMetadata(MetadataReadingComponent c) {
                this.metadata = c;
                return this;
            }

            /**
             * Configures idempotency component.
             *
             * @param c Idempotency component (may be null)
             * @return this builder for chaining
             */
            public Builder withIdempotency(IdempotencyComponent c) {
                this.idempotency = c;
                return this;
            }

            /**
             * Configures DLQ component.
             *
             * @param c DLQ component (may be null)
             * @return this builder for chaining
             */
            public Builder withDlq(DlqComponent<BatchInfo, Object> c) {
                this.dlq = c;
                return this;
            }

            /**
             * Builds the component configuration.
             * <p>
             * <strong>Thread Safety:</strong> Not thread-safe. Builder is used during construction only.
             *
             * @return new BatchIndexerComponents instance
             */
            public BatchIndexerComponents build() {
                return new BatchIndexerComponents(metadata, idempotency, dlq);
            }
        }
    }
}
