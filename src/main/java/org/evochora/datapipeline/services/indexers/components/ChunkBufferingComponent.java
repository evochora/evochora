package org.evochora.datapipeline.services.indexers.components;

import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Component for buffering chunks across batches to enable efficient bulk inserts.
 * <p>
 * Tracks which chunks belong to which storage batch and returns ACK tokens only for
 * batches that have been fully flushed to the database. This ensures that
 * no batch is acknowledged before ALL its chunks are persisted.
 * <p>
 * <strong>Chunk-based Buffering:</strong> After delta compression, data arrives as
 * {@link TickDataChunk} objects. Each chunk is self-contained (starts with a snapshot)
 * and typically contains ~100 ticks. The {@code insertBatchSize} parameter specifies
 * the number of chunks to buffer before triggering a flush.
 * <p>
 * <strong>Thread Safety:</strong> This component is <strong>NOT thread-safe</strong>
 * and must not be accessed concurrently by multiple threads. It is designed for
 * single-threaded use within one service instance.
 * <p>
 * <strong>Usage Pattern:</strong> Each {@code AbstractBatchIndexer} instance creates
 * its own {@code ChunkBufferingComponent} in {@code createComponents()}. Components
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
 * ChunkBufferingComponent, but all share the same H2TopicReader and IMetadataReader.
 */
public class ChunkBufferingComponent {
    
    private final int insertBatchSize;
    private final long flushTimeoutMs;
    private final List<TickDataChunk> buffer = new ArrayList<>();
    private final List<String> batchIds = new ArrayList<>(); // Parallel to buffer!
    private final Map<String, BatchFlushState> pendingBatches = new LinkedHashMap<>();
    private long lastFlushMs = System.currentTimeMillis();
    
    /**
     * Tracks flush state for a single storage batch.
     * <p>
     * Keeps track of how many chunks from this batch have been flushed
     * and the TopicMessage that needs to be ACKed when all chunks are flushed.
     */
    static class BatchFlushState {
        final Object message; // TopicMessage - generic!
        final int totalChunks;
        int chunksFlushed = 0;
        
        BatchFlushState(Object message, int totalChunks) {
            this.message = message;
            this.totalChunks = totalChunks;
        }
        
        boolean isComplete() {
            return chunksFlushed >= totalChunks;
        }
    }
    
    /**
     * Creates a new chunk buffering component.
     *
     * @param insertBatchSize Number of chunks to buffer before triggering flush (must be positive)
     * @param flushTimeoutMs Maximum milliseconds to wait before flushing partial buffer (must be positive)
     * @throws IllegalArgumentException if insertBatchSize or flushTimeoutMs is not positive
     */
    public ChunkBufferingComponent(int insertBatchSize, long flushTimeoutMs) {
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
     * Adds chunks from a storage batch and tracks the message for later ACK.
     * <p>
     * Chunks are added to the buffer in order, and their batch origin is tracked
     * in parallel. The TopicMessage is stored for ACK once all chunks from this
     * batch have been flushed.
     *
     * @param chunks List of chunks to buffer (must not be null or empty)
     * @param batchId Unique batch identifier (usually storageKey, must not be null)
     * @param message TopicMessage to ACK when batch is fully flushed (must not be null)
     * @param <ACK> ACK token type
     * @throws IllegalArgumentException if any parameter is null or chunks is empty
     */
    public <ACK> void addChunksFromBatch(List<TickDataChunk> chunks, String batchId, TopicMessage<?, ACK> message) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be null or empty");
        }
        if (batchId == null) {
            throw new IllegalArgumentException("batchId must not be null");
        }
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        
        // Track batch for ACK
        if (!pendingBatches.containsKey(batchId)) {
            pendingBatches.put(batchId, new BatchFlushState(message, chunks.size()));
        }
        
        // Add chunks to buffer with batch tracking
        for (TickDataChunk chunk : chunks) {
            buffer.add(chunk);
            batchIds.add(batchId);
        }
    }
    
    /**
     * Checks if buffer should be flushed based on size or timeout.
     * <p>
     * Flush triggers:
     * <ul>
     *   <li>Size: buffer.size() >= insertBatchSize (number of chunks)</li>
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
     * Flushes buffered chunks and returns completed batches for ACK.
     * <p>
     * Extracts up to {@code insertBatchSize} chunks from buffer, updates batch
     * flush counts, and returns ACK tokens for batches that are now fully flushed.
     * <p>
     * <strong>Critical:</strong> Only batches where ALL chunks have been flushed
     * are included in the returned completedMessages list. Partially flushed
     * batches remain in pendingBatches until completion.
     * <p>
     * <strong>Destructive Operation:</strong> This method REMOVES chunks from the buffer.
     * If the caller fails to persist the returned chunks (e.g., database write fails),
     * the chunks are lost from the buffer and must be re-read from storage on batch
     * redelivery. This design ensures clean buffer state and avoids complex rollback logic.
     * <p>
     * Also returns completedBatchIds for idempotency tracking.
     *
     * @param <ACK> ACK token type
     * @return FlushResult containing chunks to flush and completed messages to ACK
     */
    public <ACK> FlushResult<ACK> flush() {
        if (buffer.isEmpty()) {
            return new FlushResult<>(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
        
        int chunksToFlush = Math.min(buffer.size(), insertBatchSize);
        
        // Extract chunks and their batch IDs
        List<TickDataChunk> chunksForFlush = new ArrayList<>(buffer.subList(0, chunksToFlush));
        List<String> batchIdsForFlush = new ArrayList<>(batchIds.subList(0, chunksToFlush));
        
        // Remove from buffer
        buffer.subList(0, chunksToFlush).clear();
        batchIds.subList(0, chunksToFlush).clear();
        
        // Count chunks per batch
        Map<String, Integer> batchChunkCounts = new HashMap<>();
        for (String batchId : batchIdsForFlush) {
            batchChunkCounts.merge(batchId, 1, Integer::sum);
        }
        
        // Update batch flush counts and collect completed batches
        List<TopicMessage<?, ACK>> completedMessages = new ArrayList<>();
        List<String> completedBatchIds = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : batchChunkCounts.entrySet()) {
            String batchId = entry.getKey();
            int chunksFlushed = entry.getValue();
            
            BatchFlushState state = pendingBatches.get(batchId);
            state.chunksFlushed += chunksFlushed;
            
            if (state.isComplete()) {
                // Batch is fully flushed → can be ACKed!
                @SuppressWarnings("unchecked")
                TopicMessage<?, ACK> msg = (TopicMessage<?, ACK>) state.message;
                completedMessages.add(msg);
                completedBatchIds.add(batchId);
                pendingBatches.remove(batchId);
            }
        }
        
        lastFlushMs = System.currentTimeMillis();
        
        return new FlushResult<>(chunksForFlush, completedMessages, completedBatchIds);
    }
    
    /**
     * Returns the current buffer size (number of chunks).
     *
     * @return Number of chunks currently buffered
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
     * Contains chunks to be flushed and TopicMessages to be acknowledged.
     * Only batches that are fully flushed are included in completedMessages.
     * <p>
     * The batch IDs correspond to the messages in completedMessages (parallel lists).
     *
     * @param <ACK> ACK token type
     */
    public static class FlushResult<ACK> {
        private final List<TickDataChunk> chunks;
        private final List<TopicMessage<?, ACK>> completedMessages;
        private final List<String> completedBatchIds;
        
        /**
         * Creates a flush result.
         *
         * @param chunks Chunks to flush (must not be null)
         * @param completedMessages Messages to ACK (must not be null)
         * @param completedBatchIds Batch IDs for completed batches (must not be null)
         */
        public FlushResult(List<TickDataChunk> chunks, 
                          List<TopicMessage<?, ACK>> completedMessages,
                          List<String> completedBatchIds) {
            this.chunks = List.copyOf(chunks);
            this.completedMessages = List.copyOf(completedMessages);
            this.completedBatchIds = List.copyOf(completedBatchIds);
        }
        
        /**
         * Returns the chunks to flush.
         *
         * @return Immutable list of chunks
         */
        public List<TickDataChunk> chunks() {
            return chunks;
        }
        
        /**
         * Returns the completed messages to ACK.
         * <p>
         * Only includes batches where ALL chunks have been flushed.
         *
         * @return Immutable list of TopicMessages to acknowledge
         */
        public List<TopicMessage<?, ACK>> completedMessages() {
            return completedMessages;
        }
        
        /**
         * Returns the batch IDs for completed batches.
         * <p>
         * Used by IdempotencyComponent to mark batches as processed
         * AFTER successful ACK. This list is parallel to completedMessages.
         * <p>
         * Only includes batches where ALL chunks have been flushed.
         *
         * @return Immutable list of batch IDs
         */
        public List<String> completedBatchIds() {
            return completedBatchIds;
        }
    }
}
