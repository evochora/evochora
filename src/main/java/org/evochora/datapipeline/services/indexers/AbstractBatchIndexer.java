package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
import org.evochora.datapipeline.services.indexers.components.MetadataReadingComponent;
import org.evochora.datapipeline.services.indexers.components.TickBufferingComponent;
import org.evochora.datapipeline.api.resources.database.IMetadataReader;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for batch indexers that process TickData from batch notifications.
 * <p>
 * Extends {@link AbstractIndexer} with batch-specific functionality:
 * <ul>
 *   <li>Subscribes to batch-topic for BatchInfo notifications</li>
 *   <li>Reads TickDataBatch from storage (length-delimited format)</li>
 *   <li>Optional components: metadata reading, buffering, DLQ, idempotency</li>
 *   <li>Template method {@link #flushTicks(List)} for database writes</li>
 * </ul>
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
 *     // Required: Database write logic
 *     protected void flushTicks(List&lt;TickData&gt; ticks) throws Exception {
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
        
        // Phase 14.2.6: Automatically set topicPollTimeout to flushTimeout if buffering enabled
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
        
        /** Tick buffering component (buffers ticks for batch inserts). Phase 14.2.6. */
        BUFFERING
        
        // Phase 14.2.8: DLQ will be added
    }
    
    /**
     * Template method: Declare which components this indexer uses.
     * <p>
     * Called once during construction. Subclasses can override to customize component usage.
     * <p>
     * <strong>Default:</strong> Returns METADATA only (Phase 14.2.5).
     * Phase 14.2.6+ default: METADATA + BUFFERING.
     * <p>
     * <strong>Examples:</strong>
     * <pre>
     * // Use default (no override needed for standard case!)
     * 
     * // Minimal: No components at all
     * &#64;Override
     * protected Set&lt;ComponentType&gt; getRequiredComponents() {
     *     return EnumSet.noneOf(ComponentType.class);
     * }
     * </pre>
     *
     * @return set of component types to use (never null)
     */
    protected Set<ComponentType> getRequiredComponents() {
        // Phase 14.2.6: Default is METADATA + BUFFERING
        return EnumSet.of(ComponentType.METADATA, ComponentType.BUFFERING);
    }
    
    /**
     * Creates components based on {@link #getRequiredComponents()}.
     * <p>
     * <strong>FINAL:</strong> Subclasses must NOT override this method.
     * Instead, override {@link #getRequiredComponents()} to customize components.
     * <p>
     * All components use standardized config parameters:
     * <ul>
     *   <li>Metadata: {@code metadataPollIntervalMs}, {@code metadataMaxPollDurationMs}</li>
     * </ul>
     * <p>
     * Phase 14.2.5 scope: Only METADATA component!
     * Phase 14.2.6+: BUFFERING component added.
     * Phase 14.2.8+: DLQ component added.
     *
     * @return component configuration (may be null if no components requested)
     */
    protected final BatchIndexerComponents createComponents() {
        Set<ComponentType> required = getRequiredComponents();
        if (required.isEmpty()) return null;
        
        var builder = BatchIndexerComponents.builder();
        
        // Component 1: Metadata Reading (Phase 14.2.5)
        if (required.contains(ComponentType.METADATA)) {
            IMetadataReader metadataReader = getRequiredResource("metadata", IMetadataReader.class);
            int pollIntervalMs = indexerOptions.getInt("metadataPollIntervalMs");
            int maxPollDurationMs = indexerOptions.getInt("metadataMaxPollDurationMs");
            builder.withMetadata(new MetadataReadingComponent(
                metadataReader, pollIntervalMs, maxPollDurationMs));
        }
        
        // Component 2: Tick Buffering (Phase 14.2.6)
        if (required.contains(ComponentType.BUFFERING)) {
            int insertBatchSize = indexerOptions.getInt("insertBatchSize");
            long flushTimeoutMs = indexerOptions.getLong("flushTimeoutMs");
            builder.withBuffering(new TickBufferingComponent(insertBatchSize, flushTimeoutMs));
        }
        
        // Phase 14.2.8: DLQ component handling will be added here
        
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
            
            // Step 2: Topic loop
            while (!Thread.currentThread().isInterrupted()) {
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
                try {
                    flushAndAcknowledge();
                } catch (Exception e) {
                    log.error("Final flush failed during shutdown");
                    throw e;
                }
            }
        }
    }
    
    /**
     * Processes a single batch notification message.
     * <p>
     * Reads TickDataBatch from storage, processes ticks (buffered or tick-by-tick),
     * and acknowledges message after successful processing.
     * <p>
     * Phase 14.2.5: Tick-by-tick processing only (no buffering).
     * Phase 14.2.6+: Conditional buffering logic added.
     *
     * @param msg Topic message containing BatchInfo
     * @throws Exception if processing fails (batch will be redelivered)
     */
    private void processBatchMessage(TopicMessage<BatchInfo, ACK> msg) throws Exception {
        BatchInfo batch = msg.payload();
        String batchId = batch.getStoragePath();
        
        log.debug("Received BatchInfo: storagePath={}, ticks=[{}-{}]", 
            batch.getStoragePath(), batch.getTickStart(), batch.getTickEnd());
        
        try {
            // Read from storage (GENERIC for all batch indexers!)
            // Storage handles length-delimited format automatically
            StoragePath storagePath = StoragePath.of(batch.getStoragePath());
            List<TickData> ticks = storage.readBatch(storagePath);
            
            if (components != null && components.buffering != null) {
                // WITH buffering: Add to buffer, ACK after flush
                components.buffering.addTicksFromBatch(ticks, batchId, msg);
                
                log.debug("Buffered {} ticks from {}, buffer size: {}", 
                    ticks.size(), batch.getStoragePath(), 
                    components.buffering.getBufferSize());
                
                // Flush if needed
                if (components.buffering.shouldFlush()) {
                    flushAndAcknowledge();
                }
            } else {
                // WITHOUT buffering: tick-by-tick processing
                for (TickData tick : ticks) {
                    flushTicks(List.of(tick));
                }
                
                // ACK after ALL ticks from batch are processed
                topic.ack(msg);
                
                // Track metrics
                batchesProcessed.incrementAndGet();
                ticksProcessed.addAndGet(ticks.size());
                
                log.debug("Processed {} ticks from {} (tick-by-tick, no buffering)", 
                         ticks.size(), batch.getStoragePath());
            }
            
        } catch (Exception e) {
            log.error("Failed to process batch: {}", batchId);
            throw e;  // NO acknowledge - redelivery!
        }
    }
    
    /**
     * Flushes buffered ticks and acknowledges completed batches.
     * <p>
     * Called when buffer is full or timeout occurs. Flushes ticks to database,
     * acknowledges only fully completed batches, and updates metrics.
     * <p>
     * Phase 14.2.6+: Used by buffering logic only.
     *
     * @throws Exception if flush or ACK fails
     */
    private void flushAndAcknowledge() throws Exception {
        TickBufferingComponent.FlushResult<ACK> result = components.buffering.flush();
        if (result.ticks().isEmpty()) {
            return;
        }
        
        // Call indexer-specific flush
        flushTicks(result.ticks());
        
        // ACK all completed batches
        for (TopicMessage<?, ACK> completedMsg : result.completedMessages()) {
            @SuppressWarnings("unchecked")
            TopicMessage<BatchInfo, ACK> batchMsg = (TopicMessage<BatchInfo, ACK>) completedMsg;
            topic.ack(batchMsg);
        }
        
        // Update metrics
        batchesProcessed.addAndGet(result.completedMessages().size());
        ticksProcessed.addAndGet(result.ticks().size());
    }
    
    /**
     * Flush ticks to database/log.
     * <p>
     * Phase 14.2.5: Called per tick (list will always contain exactly 1 tick).
     * Phase 14.2.6+: Called per buffer flush (list can contain multiple ticks).
     * <p>
     * <strong>CRITICAL:</strong> Must use MERGE (not INSERT) for idempotency when writing to database!
     * <p>
     * <strong>Thread Safety:</strong> Called from single service thread only.
     *
     * @param ticks Ticks to flush (Phase 14.2.5: always size=1)
     * @throws Exception if flush fails
     */
    protected abstract void flushTicks(List<TickData> ticks) throws Exception;
    
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
    }
    
    /**
     * Component configuration for batch indexers.
     * <p>
     * Phase 14.2.5: Only contains MetadataReadingComponent.
     * Phase 14.2.6+: TickBufferingComponent added.
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
     *     .build()
     * </pre>
     */
    public static class BatchIndexerComponents {
        /** Metadata reading component (optional). */
        public final MetadataReadingComponent metadata;
        
        /** Tick buffering component (optional, Phase 14.2.6). */
        public final TickBufferingComponent buffering;
        
        private BatchIndexerComponents(MetadataReadingComponent metadata,
                                       TickBufferingComponent buffering) {
            this.metadata = metadata;
            this.buffering = buffering;
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
            private TickBufferingComponent buffering;
            
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
             * Configures tick buffering component.
             *
             * @param c Tick buffering component (may be null)
             * @return this builder for chaining
             */
            public Builder withBuffering(TickBufferingComponent c) {
                this.buffering = c;
                return this;
            }
            
            /**
             * Builds the component configuration.
             *
             * @return new BatchIndexerComponents instance
             */
            public BatchIndexerComponents build() {
                return new BatchIndexerComponents(metadata, buffering);
            }
        }
    }
}

