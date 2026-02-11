package org.evochora.datapipeline.services.indexers;

import java.util.EnumSet;
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
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
import org.evochora.datapipeline.services.indexers.components.DlqComponent;
import org.evochora.datapipeline.services.indexers.components.IdempotencyComponent;
import org.evochora.datapipeline.services.indexers.components.MetadataReadingComponent;
import org.evochora.datapipeline.services.indexers.components.ChunkBufferingComponent;

import com.typesafe.config.Config;

/**
 * Abstract base class for batch indexers that process TickDataChunks from batch notifications.
 * <p>
 * Extends {@link AbstractIndexer} with batch-specific functionality:
 * <ul>
 *   <li>Subscribes to batch-topic for BatchInfo notifications</li>
 *   <li>Reads TickDataChunks from storage (length-delimited format)</li>
 *   <li>Optional components: metadata reading, buffering, DLQ, idempotency</li>
 *   <li>Template method {@link #flushChunks(List)} for database writes</li>
 * </ul>
 * <p>
 * <strong>Chunk-based Processing:</strong> After delta compression, data arrives as
 * {@link TickDataChunk} objects. Each chunk is self-contained (starts with a snapshot)
 * and typically contains ~100 ticks. The {@code insertBatchSize} parameter specifies
 * the number of chunks to buffer before triggering a flush.
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
 *     // Optional: Override if not using default (METADATA)
 *     protected Set&lt;ComponentType&gt; getRequiredComponents() {
 *         return EnumSet.of(ComponentType.METADATA);
 *     }
 *     
 *     // Required: Chunk processing logic
 *     protected void flushChunks(List&lt;TickDataChunk&gt; chunks) throws Exception {
 *         // Write to database using MERGE (not INSERT!)
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
        
        // Automatically set topicPollTimeout to flushTimeout if buffering enabled
        if (components != null && components.buffering != null) {
            this.topicPollTimeoutMs = (int) components.buffering.getFlushTimeoutMs();
        } else {
            this.topicPollTimeoutMs = options.hasPath("topicPollTimeoutMs") 
                ? options.getInt("topicPollTimeoutMs") 
                : 5000;
        }
    }
    
    /**
     * Component types available for batch indexers.
     * <p>
     * Used by {@link #getRequiredComponents()} to declare which components to use.
     */
    public enum ComponentType {
        /** Metadata reading component (polls DB for simulation metadata). */
        METADATA,
        
        /** Chunk buffering component (buffers chunks for batch inserts). */
        BUFFERING,
        
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
     * <strong>Default:</strong> Returns METADATA + BUFFERING (standard batch indexer setup).
     * <p>
     * <strong>Examples:</strong>
     * <pre>
     * // Use default (no override needed for standard case!)
     * 
     * // Minimal indexer (no buffering)
     * &#64;Override
     * protected Set&lt;ComponentType&gt; getRequiredComponents() {
     *     return EnumSet.of(ComponentType.METADATA);
     * }
     * 
     * // No components at all (direct Topic processing)
     * &#64;Override
     * protected Set&lt;ComponentType&gt; getRequiredComponents() {
     *     return EnumSet.noneOf(ComponentType.class);
     * }
     * </pre>
     *
     * @return set of required component types (never null)
     */
    protected Set<ComponentType> getRequiredComponents() {
        // Default: METADATA + BUFFERING (standard batch indexer)
        return EnumSet.of(ComponentType.METADATA, ComponentType.BUFFERING);
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
     * @return set of optional component types (never null)
     */
    protected Set<ComponentType> getOptionalComponents() {
        // Default: No optional components
        // Subclasses can override to enable IDEMPOTENCY, DLQ, etc.
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
     *   <li>Buffering: {@code insertBatchSize}, {@code flushTimeoutMs}</li>
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
        
        // Component 2: Chunk Buffering
        // REQUIRED component - uses sensible defaults if config missing
        if (required.contains(ComponentType.BUFFERING)) {
            int insertBatchSize = indexerOptions.hasPath("insertBatchSize")
                ? indexerOptions.getInt("insertBatchSize")
                : 5; // Default: 5 chunks per buffer (~500 ticks at 100 ticks/chunk)
            long flushTimeoutMs = indexerOptions.hasPath("flushTimeoutMs")
                ? indexerOptions.getLong("flushTimeoutMs")
                : 5000L; // Default: 5000ms flush timeout (aligned with docs)
            builder.withBuffering(new ChunkBufferingComponent(insertBatchSize, flushTimeoutMs));
        }
        // OPTIONAL buffering component - graceful skip if config missing
        else if (optional.contains(ComponentType.BUFFERING)) {
            if (indexerOptions.hasPath("insertBatchSize") && indexerOptions.hasPath("flushTimeoutMs")) {
                int insertBatchSize = indexerOptions.getInt("insertBatchSize");
                long flushTimeoutMs = indexerOptions.getLong("flushTimeoutMs");
                builder.withBuffering(new ChunkBufferingComponent(insertBatchSize, flushTimeoutMs));
            }
        }
        
        // Component 3: Idempotency
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
        
        // Component 4: DLQ
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
                    // Check buffering component for timeout-based flush
                    if (components != null && components.buffering != null 
                        && components.buffering.shouldFlush()) {
                        flushAndAcknowledge();
                    }
                    continue;
                }
                
                processBatchMessage(msg);
            }
        } finally {
            // Final flush of remaining buffered ticks (always executed, even on interrupt!)
            if (components != null && components.buffering != null 
                && components.buffering.getBufferSize() > 0) {
                // Clear interrupt flag temporarily to allow final flush to complete.
                // H2 Database's internal locking mechanism fails if thread is interrupted
                // (MVMap.tryLock() uses Thread.sleep() which throws InterruptedException).
                boolean wasInterrupted = Thread.interrupted();
                try {
                    flushAndAcknowledge();
                } catch (Exception e) {
                    throw e;
                } finally {
                    // Restore interrupt flag for proper shutdown handling in AbstractService
                    if (wasInterrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            
            // Hook for subclass cleanup (called after final flush)
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
     * Called automatically in the finally block after final flush completes.
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
     * Reads TickDataChunks from storage, processes chunks (buffered or passthrough),
     * and acknowledges message after successful processing.
     * <p>
     * <strong>Error Handling:</strong> Transient errors (storage read failures, database
     * write failures) are logged as WARN and tracked. The batch is NOT acknowledged,
     * causing topic redelivery after claimTimeout. The indexer continues processing
     * other batches, allowing recovery from transient failures without service restart.
     * <p>
     * <strong>Buffer Recovery:</strong> If buffering is enabled and {@code flushChunks()}
     * fails, the buffer is already empty (chunks removed by {@code buffering.flush()}).
     * Batch redelivery will re-read chunks from storage. MERGE ensures no duplicates.
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
            // Read chunks from storage (GENERIC for all batch indexers!)
            // Storage handles length-delimited format automatically
            StoragePath storagePath = StoragePath.of(batch.getStoragePath());
            List<TickDataChunk> chunks = storage.readChunkBatch(storagePath);
            
            // Count total ticks across all chunks for metrics
            int totalTicks = chunks.stream().mapToInt(TickDataChunk::getTickCount).sum();
            
            if (components != null && components.buffering != null) {
                // WITH buffering: Add to buffer, ACK after flush
                components.buffering.addChunksFromBatch(chunks, batchId, msg);
                
                log.debug("Buffered {} chunks ({} ticks) from {}, buffer size: {}", 
                    chunks.size(), totalTicks, batch.getStoragePath(), 
                    components.buffering.getBufferSize());
                
                // Flush if needed
                if (components.buffering.shouldFlush()) {
                    flushAndAcknowledge();
                }
            } else {
                // WITHOUT buffering: Batch-passthrough (one flush per topic message)
                // Each topic message = one PersistenceService batch = one flush
                // Guarantees consistent file boundaries for file-based indexers (e.g., Parquet)
                flushChunks(chunks);
                
                // ACK after batch is processed
                topic.ack(msg);
                
                // Safe to mark immediately after ACK (no buffering = no cross-batch risk)
                if (components != null && components.idempotency != null) {
                    components.idempotency.markProcessed(batchId);
                }
                
                // Track metrics
                batchesProcessed.incrementAndGet();
                ticksProcessed.addAndGet(totalTicks);
                
                log.debug("Processed {} chunks ({} ticks) from {} (batch-passthrough, no buffering)", 
                         chunks.size(), totalTicks, batch.getStoragePath());
            }
            
            // Success - reset retry count if DLQ component exists
            if (components != null && components.dlq != null) {
                components.dlq.resetRetryCount(batchId);
            }
            
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
     * Flushes buffered chunks and acknowledges completed batches.
     * <p>
     * Called when buffer is full or timeout occurs. Flushes chunks to database,
     * acknowledges only fully completed batches, and updates metrics.
     * <p>
     * <strong>Buffer State:</strong> The {@code buffering.flush()} call removes chunks
     * from the buffer BEFORE calling {@code flushChunks()}. If {@code flushChunks()}
     * throws an exception, the buffer is already empty and chunks must be re-read
     * from storage on batch redelivery.
     * <p>
     * Marks batches as processed AFTER ACK (critical for correctness!).
     *
     * @throws Exception if flush or ACK fails
     */
    private void flushAndAcknowledge() throws Exception {
        ChunkBufferingComponent.FlushResult<ACK> result = components.buffering.flush();
        if (result.chunks().isEmpty()) {
            return;
        }
        
        // 1. Flush chunks to DB (MERGE ensures idempotency)
        flushChunks(result.chunks());
        
        // 2. ACK completed batches
        for (TopicMessage<?, ACK> completedMsg : result.completedMessages()) {
            @SuppressWarnings("unchecked")
            TopicMessage<BatchInfo, ACK> batchMsg = (TopicMessage<BatchInfo, ACK>) completedMsg;
            topic.ack(batchMsg);
        }
        
        // 3. CRITICAL: Mark processed ONLY AFTER ACK (safe!)
        // This prevents data loss: Read → Buffer → Flush → ACK → markProcessed()
        if (components != null && components.idempotency != null) {
            for (String batchId : result.completedBatchIds()) {
                components.idempotency.markProcessed(batchId);
            }
        }
        
        // 4. Update metrics (count ticks across all chunks)
        batchesProcessed.addAndGet(result.completedMessages().size());
        int totalTicks = result.chunks().stream().mapToInt(TickDataChunk::getTickCount).sum();
        ticksProcessed.addAndGet(totalTicks);

        // Yield to other threads/processes after flush to prevent system freezing
        Thread.yield();
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
     * Flush chunks to database/log.
     * <p>
     * Each chunk is self-contained with a snapshot and deltas. Subclasses decide how to process:
     * <ul>
     *   <li>EnvironmentIndexer: Store chunks as BLOBs directly</li>
     *   <li>OrganismIndexer: Extract organisms from snapshot + deltas</li>
     *   <li>AnalyticsIndexer: Decompress chunks to TickData for plugin processing</li>
     * </ul>
     * <p>
     * <strong>CRITICAL:</strong> Must use MERGE (not INSERT) for idempotency when writing to database!
     * <p>
     * <strong>Thread Safety:</strong> Called from single service thread only.
     *
     * @param chunks Chunks to flush (typically 1-10 chunks per flush with default settings)
     * @throws Exception if flush fails
     */
    protected abstract void flushChunks(List<TickDataChunk> chunks) throws Exception;
    
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
     *     .withBuffering(...)
     *     .withIdempotency(...)
     *     .withDlq(...)
     *     .build()
     * </pre>
     */
    public static class BatchIndexerComponents {
        /** Metadata reading component (optional). */
        public final MetadataReadingComponent metadata;
        
        /** Chunk buffering component (optional). */
        public final ChunkBufferingComponent buffering;
        
        /** Idempotency component (optional). */
        public final IdempotencyComponent idempotency;
        
        /** DLQ component (optional). */
        public final DlqComponent<BatchInfo, Object> dlq;
        
        private BatchIndexerComponents(MetadataReadingComponent metadata,
                                       ChunkBufferingComponent buffering,
                                       IdempotencyComponent idempotency,
                                       DlqComponent<BatchInfo, Object> dlq) {
            this.metadata = metadata;
            this.buffering = buffering;
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
            private ChunkBufferingComponent buffering;
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
             * Configures chunk buffering component.
             *
             * @param c Chunk buffering component (may be null)
             * @return this builder for chaining
             */
            public Builder withBuffering(ChunkBufferingComponent c) {
                this.buffering = c;
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
             *
             * @return new BatchIndexerComponents instance
             */
            public BatchIndexerComponents build() {
                return new BatchIndexerComponents(metadata, buffering, idempotency, dlq);
            }
        }
    }
}

