package org.evochora.datapipeline.services.indexers.components;

import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Component for optional idempotency tracking (performance optimization).
 * <p>
 * Wraps {@link IIdempotencyTracker} with indexer-specific context and provides convenient
 * methods for duplicate detection. This is a <strong>PERFORMANCE optimization only</strong> - 
 * correctness is guaranteed by MERGE statements in indexers.
 * <p>
 * <strong>Thread Safety:</strong> This component is <strong>NOT thread-safe</strong>
 * and must not be accessed concurrently by multiple threads. It is designed for
 * single-threaded use within one service instance.
 * <p>
 * <strong>Competing Consumer Pattern:</strong> Multiple service instances (competing
 * consumers) each have their own {@code IdempotencyComponent} instance. The underlying
 * {@link IIdempotencyTracker} resource IS thread-safe and coordinates duplicate
 * detection across all consumers.
 * <p>
 * <strong>Usage Pattern:</strong> Each {@code AbstractBatchIndexer} instance creates
 * its own {@code IdempotencyComponent} in {@code createComponents()}. Components are
 * never shared between service instances or threads.
 * <p>
 * <strong>Important:</strong> This component is optional. MERGE statements
 * provide 100% correctness without it. Only use if performance monitoring shows
 * storage reads as bottleneck.
 * <p>
 * <strong>Critical Safety:</strong> {@link #markProcessed(String)} must ONLY be called
 * AFTER successful ACK to prevent data loss with buffering. See implementation in
 * {@code AbstractBatchIndexer.flushAndAcknowledge()}.
 */
public class IdempotencyComponent {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyComponent.class);
    
    private final IIdempotencyTracker<String> tracker;
    private final String indexerClass;
    
    /**
     * Creates idempotency component.
     * <p>
     * The indexer class name is used to scope the idempotency tracking
     * (e.g., "DummyIndexer", "EnvironmentIndexer"). This allows different
     * indexers to track the same batch IDs independently.
     *
     * @param tracker Database capability for idempotency tracking (must not be null)
     * @param indexerClass Class name of the indexer for tracking scope (must not be null/blank)
     * @throws IllegalArgumentException if tracker is null or indexerClass is null/blank
     */
    public IdempotencyComponent(IIdempotencyTracker<String> tracker, String indexerClass) {
        if (tracker == null) {
            throw new IllegalArgumentException("Tracker must not be null");
        }
        if (indexerClass == null || indexerClass.isBlank()) {
            throw new IllegalArgumentException("Indexer class must not be null or blank");
        }
        this.tracker = tracker;
        this.indexerClass = indexerClass;
    }
    
    /**
     * Checks if batch was already processed.
     * <p>
     * This is a performance optimization to skip storage reads for duplicate batches.
     * Even if this returns false, MERGE statements ensure no duplicates in database.
     * <p>
     * If the tracker fails (e.g. database error), returns false as safe default.
     * MERGE will handle duplicates anyway.
     *
     * @param batchId Batch identifier (usually storageKey, must not be null)
     * @return true if batch was already processed, false otherwise or on error
     * @throws IllegalArgumentException if batchId is null
     */
    public boolean isProcessed(String batchId) {
        if (batchId == null) {
            throw new IllegalArgumentException("batchId must not be null");
        }
        
        try {
            // Use indexerClass as scope to allow different indexers to track independently
            String scopedKey = indexerClass + ":" + batchId;
            return tracker.isProcessed(scopedKey);
        } catch (Exception e) {
            // If tracker fails, assume NOT processed (safe default)
            // MERGE will handle duplicates anyway
            log.debug("Idempotency check failed for batch {}, assuming not processed: {}", 
                     batchId, e.getMessage());
            return false;
        }
    }
    
    /**
     * Marks batch as processed.
     * <p>
     * <strong>CRITICAL:</strong> Should ONLY be called after ticks are flushed to database
     * AND after message is ACKed. This ensures future redeliveries can be skipped
     * (performance optimization).
     * <p>
     * <strong>Correct order:</strong> Read → Buffer → Flush → ACK → markProcessed()
     * <p>
     * <strong>Wrong order:</strong> Read → Buffer → markProcessed() → Crash → Data loss!
     * <p>
     * If marking fails (e.g. database error), logs but continues. This is not critical
     * because MERGE ensures correctness even without tracking.
     *
     * @param batchId Batch identifier (usually storageKey, must not be null)
     * @throws IllegalArgumentException if batchId is null
     */
    public void markProcessed(String batchId) {
        if (batchId == null) {
            throw new IllegalArgumentException("batchId must not be null");
        }
        
        try {
            // Use indexerClass as scope to allow different indexers to track independently
            String scopedKey = indexerClass + ":" + batchId;
            tracker.markProcessed(scopedKey);
        } catch (Exception e) {
            // If marking fails, log but continue (not critical)
            // MERGE ensures correctness even without tracking
            log.debug("Failed to mark batch {} as processed: {}", batchId, e.getMessage());
        }
    }
}

