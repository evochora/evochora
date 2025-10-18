package org.evochora.datapipeline.services.indexers.components;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Component for buffering ticks across batches to enable efficient bulk inserts.
 * <p>
 * Tracks which ticks belong to which batch and returns ACK tokens only for
 * batches that have been fully flushed to the database. This ensures that
 * no batch is acknowledged before ALL its ticks are persisted.
 * <p>
 * <strong>Thread Safety:</strong> This component is <strong>NOT thread-safe</strong>
 * and must not be accessed concurrently by multiple threads. It is designed for
 * single-threaded use within one service instance.
 * <p>
 * <strong>Usage Pattern:</strong> Each {@code AbstractBatchIndexer} instance creates
 * its own {@code TickBufferingComponent} in {@code createComponents()}. Components
 * are never shared between service instances or threads.
 * <p>
 * <strong>Design Rationale:</strong>
 * <ul>
 *   <li>Each service instance runs in exactly one thread</li>
 *   <li>Each service instance has its own component instances</li>
 *   <li>Underlying resources (DB, topics) are thread-safe and shared</li>
 *   <li>No need for synchronization overhead in components</li>
 * </ul>
 * <p>
 * <strong>Example:</strong> 3x DummyIndexer (competing consumers) each has own
 * TickBufferingComponent, but all share the same H2TopicReader and IMetadataReader.
 */
public class TickBufferingComponent {
    
    private final int insertBatchSize;
    private final long flushTimeoutMs;
    private final List<TickData> buffer = new ArrayList<>();
    private final List<String> batchIds = new ArrayList<>(); // Parallel to buffer!
    private final Map<String, BatchFlushState> pendingBatches = new LinkedHashMap<>();
    private long lastFlushMs = System.currentTimeMillis();
    
    /**
     * Tracks flush state for a single batch.
     * <p>
     * Keeps track of how many ticks from this batch have been flushed
     * and the TopicMessage that needs to be ACKed when all ticks are flushed.
     */
    static class BatchFlushState {
        final Object message; // TopicMessage - generic!
        final int totalTicks;
        int ticksFlushed = 0;
        
        BatchFlushState(Object message, int totalTicks) {
            this.message = message;
            this.totalTicks = totalTicks;
        }
        
        boolean isComplete() {
            return ticksFlushed >= totalTicks;
        }
    }
    
    /**
     * Creates a new tick buffering component.
     *
     * @param insertBatchSize Number of ticks to buffer before triggering flush (must be positive)
     * @param flushTimeoutMs Maximum milliseconds to wait before flushing partial buffer (must be positive)
     * @throws IllegalArgumentException if insertBatchSize or flushTimeoutMs is not positive
     */
    public TickBufferingComponent(int insertBatchSize, long flushTimeoutMs) {
        if (insertBatchSize <= 0) {
            throw new IllegalArgumentException("insertBatchSize must be positive");
        }
        if (flushTimeoutMs <= 0) {
            throw new IllegalArgumentException("flushTimeoutMs must be positive");
        }
        this.insertBatchSize = insertBatchSize;
        this.flushTimeoutMs = flushTimeoutMs;
    }
    
    /**
     * Adds ticks from a batch and tracks the message for later ACK.
     * <p>
     * Ticks are added to the buffer in order, and their batch origin is tracked
     * in parallel. The TopicMessage is stored for ACK once all ticks from this
     * batch have been flushed.
     *
     * @param ticks List of ticks to buffer (must not be null or empty)
     * @param batchId Unique batch identifier (usually storageKey, must not be null)
     * @param message TopicMessage to ACK when batch is fully flushed (must not be null)
     * @param <ACK> ACK token type
     * @throws IllegalArgumentException if any parameter is null or ticks is empty
     */
    public <ACK> void addTicksFromBatch(List<TickData> ticks, String batchId, TopicMessage<?, ACK> message) {
        if (ticks == null || ticks.isEmpty()) {
            throw new IllegalArgumentException("ticks must not be null or empty");
        }
        if (batchId == null) {
            throw new IllegalArgumentException("batchId must not be null");
        }
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        
        // Track batch for ACK
        if (!pendingBatches.containsKey(batchId)) {
            pendingBatches.put(batchId, new BatchFlushState(message, ticks.size()));
        }
        
        // Add ticks to buffer with batch tracking
        for (TickData tick : ticks) {
            buffer.add(tick);
            batchIds.add(batchId);
        }
    }
    
    /**
     * Checks if buffer should be flushed based on size or timeout.
     * <p>
     * Flush triggers:
     * <ul>
     *   <li>Size: buffer.size() >= insertBatchSize</li>
     *   <li>Timeout: buffer is not empty AND (currentTime - lastFlush) >= flushTimeoutMs</li>
     * </ul>
     *
     * @return true if buffer should be flushed, false otherwise
     */
    public boolean shouldFlush() {
        if (buffer.size() >= insertBatchSize) {
            return true;
        }
        if (!buffer.isEmpty() && (System.currentTimeMillis() - lastFlushMs) >= flushTimeoutMs) {
            return true;
        }
        return false;
    }
    
    /**
     * Flushes buffered ticks and returns completed batches for ACK.
     * <p>
     * Extracts up to {@code insertBatchSize} ticks from buffer, updates batch
     * flush counts, and returns ACK tokens for batches that are now fully flushed.
     * <p>
     * <strong>Critical:</strong> Only batches where ALL ticks have been flushed
     * are included in the returned completedMessages list. Partially flushed
     * batches remain in pendingBatches until completion.
     *
     * @param <ACK> ACK token type
     * @return FlushResult containing ticks to flush and completed messages to ACK
     */
    public <ACK> FlushResult<ACK> flush() {
        if (buffer.isEmpty()) {
            return new FlushResult<>(Collections.emptyList(), Collections.emptyList());
        }
        
        int ticksToFlush = Math.min(buffer.size(), insertBatchSize);
        
        // Extract ticks and their batch IDs
        List<TickData> ticksForFlush = new ArrayList<>(buffer.subList(0, ticksToFlush));
        List<String> batchIdsForFlush = new ArrayList<>(batchIds.subList(0, ticksToFlush));
        
        // Remove from buffer
        buffer.subList(0, ticksToFlush).clear();
        batchIds.subList(0, ticksToFlush).clear();
        
        // Count ticks per batch
        Map<String, Integer> batchTickCounts = new HashMap<>();
        for (String batchId : batchIdsForFlush) {
            batchTickCounts.merge(batchId, 1, Integer::sum);
        }
        
        // Update batch flush counts and collect completed batches
        List<TopicMessage<?, ACK>> completedMessages = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : batchTickCounts.entrySet()) {
            String batchId = entry.getKey();
            int ticksFlushed = entry.getValue();
            
            BatchFlushState state = pendingBatches.get(batchId);
            state.ticksFlushed += ticksFlushed;
            
            if (state.isComplete()) {
                // Batch is fully flushed → can be ACKed!
                @SuppressWarnings("unchecked")
                TopicMessage<?, ACK> msg = (TopicMessage<?, ACK>) state.message;
                completedMessages.add(msg);
                pendingBatches.remove(batchId);
            }
        }
        
        lastFlushMs = System.currentTimeMillis();
        
        return new FlushResult<>(ticksForFlush, completedMessages);
    }
    
    /**
     * Returns the current buffer size.
     *
     * @return Number of ticks currently buffered
     */
    public int getBufferSize() {
        return buffer.size();
    }
    
    /**
     * Returns the flush timeout in milliseconds.
     * <p>
     * Used by AbstractBatchIndexer to auto-set topicPollTimeout.
     *
     * @return Flush timeout in milliseconds
     */
    public long getFlushTimeoutMs() {
        return flushTimeoutMs;
    }
    
    /**
     * Result of a flush operation.
     * <p>
     * Contains ticks to be flushed and TopicMessages to be acknowledged.
     * Only batches that are fully flushed are included in completedMessages.
     *
     * @param <ACK> ACK token type
     */
    public static class FlushResult<ACK> {
        private final List<TickData> ticks;
        private final List<TopicMessage<?, ACK>> completedMessages;
        
        /**
         * Creates a flush result.
         *
         * @param ticks Ticks to flush (must not be null)
         * @param completedMessages Messages to ACK (must not be null)
         */
        public FlushResult(List<TickData> ticks, List<TopicMessage<?, ACK>> completedMessages) {
            this.ticks = List.copyOf(ticks);
            this.completedMessages = List.copyOf(completedMessages);
        }
        
        /**
         * Returns the ticks to flush.
         *
         * @return Immutable list of ticks
         */
        public List<TickData> ticks() {
            return ticks;
        }
        
        /**
         * Returns the completed messages to ACK.
         * <p>
         * Only includes batches where ALL ticks have been flushed.
         *
         * @return Immutable list of TopicMessages to acknowledge
         */
        public List<TopicMessage<?, ACK>> completedMessages() {
            return completedMessages;
        }
    }
}

