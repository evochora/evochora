# Phase 2.5.5: Batch Processing Loop and AbstractBatchProcessingIndexer

**Part of:** [14_BATCH_COORDINATION_AND_DUMMYINDEXER.md](./14_BATCH_COORDINATION_AND_DUMMYINDEXER.md)

**Status:** Ready for implementation

---

## Goal

Implement intelligent batch processing loop with active gap filling in `AbstractBatchProcessingIndexer`.

Consolidates all previous phases into a reusable base class for batch-processing indexers. Implements 2-phase loop: prioritize filling oldest pending gaps, then discover new batches from storage.

## Scope

**In Scope:**
1. `AbstractBatchProcessingIndexer` base class
2. Intelligent 2-phase batch processing loop
3. Active gap filling with tick-range filtered storage queries
4. Gap timeout detection and permanent gap marking
5. Batch completion tracking (mark completed after flush)
6. DummyIndexer v5 (final) - full infrastructure testing
7. Unit tests for AbstractBatchProcessingIndexer
8. Integration tests for DummyIndexer v5 (sequential, out-of-order, gap filling, permanent gaps)

**Out of Scope:**
- Actual data processing implementations (EnvironmentIndexer, etc.)

## Success Criteria

1. AbstractBatchProcessingIndexer provides complete batch processing infrastructure
2. 2-phase loop: Phase 1 fills gaps, Phase 2 discovers new batches
3. Gap timeout detection works correctly
4. Permanent gaps marked exactly once with warning log
5. DummyIndexer v5 successfully fills gaps and marks permanent gaps
6. All tests pass with proper log validation
7. No connection leaks (verified in tests)
8. Metrics tracked with O(1) operations

## Prerequisites

- Phase 2.5.1: Metadata Reading (completed)
- Phase 2.5.2: Batch Coordination (completed)
- Phase 2.5.3: Tick Buffering (completed)
- Phase 2.5.4: Gap Detection (completed)
- DummyIndexer v4

## Package Structure

```
org.evochora.datapipeline.services.indexers/
  - AbstractBatchProcessingIndexer.java   # NEW - Base class for batch indexers
  - DummyIndexer.java                     # EXTEND - v5: Full implementation (final)
```

## AbstractBatchProcessingIndexer

```java
package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.BatchAlreadyClaimedException;
import org.evochora.datapipeline.api.resources.database.GapInfo;
import org.evochora.datapipeline.api.resources.database.IBatchCoordinator;
import org.evochora.datapipeline.api.resources.database.IBatchCoordinatorReady;
import org.evochora.datapipeline.api.resources.database.IMetadataReader;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.services.indexers.components.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract base class for indexers that process batch files with coordination.
 * <p>
 * Provides complete infrastructure from Phases 2.5.1-2.5.4:
 * <ul>
 *   <li>Metadata reading with polling</li>
 *   <li>Batch coordination (competing consumers)</li>
 *   <li>Tick buffering with configurable flush</li>
 *   <li>Gap detection and active gap filling</li>
 * </ul>
 * <p>
 * <strong>2-Phase Batch Processing Loop:</strong>
 * <ol>
 *   <li><strong>Phase 1 - Gap Filling:</strong> Prioritize filling oldest pending gap
 *       using tick-range filtered storage query</li>
 *   <li><strong>Phase 2 - New Batches:</strong> Discover new batches from storage
 *       using pagination, detect and record new gaps</li>
 * </ol>
 * <p>
 * Subclasses must implement:
 * <ul>
 *   <li>{@link #processBatch(List)} - Process buffered ticks</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Each indexer instance runs in single thread.
 */
public abstract class AbstractBatchProcessingIndexer extends AbstractIndexer {
    private static final Logger log = LoggerFactory.getLogger(AbstractBatchProcessingIndexer.class);
    
    // Components (optional - subclasses can choose which to use)
    protected MetadataReadingComponent metadata;
    protected BatchCoordinationComponent coordination;
    protected GapDetectionComponent gapDetection;
    protected TickBufferingComponent buffering;
    
    // Resources
    protected IBatchStorageRead storage;
    protected IBatchCoordinatorReady coordinatorReady;
    
    // Configuration
    protected int insertBatchSize;
    protected long flushTimeoutMs;
    protected long gapTimeoutMs;
    
    // State
    protected String continuationToken = null;
    protected final List<String> pendingCompletionBatches = new ArrayList<>();
    protected final ConcurrentHashMap<Long, Boolean> permanentGapsLogged = new ConcurrentHashMap<>();
    
    public AbstractBatchProcessingIndexer(String name, Config options, 
                                         Map<String, List<IResource>> resources) {
        super(name, options, resources);
    }
    
    /**
     * Initializes all components.
     * <p>
     * Called by subclass constructor after super(). Subclasses can override
     * to customize which components are initialized.
     */
    protected void initializeComponents() {
        // Metadata reading
        IMetadataReader metadataReader = getRequiredResource(IMetadataReader.class, "db-meta-read");
        int pollIntervalMs = getOptions().hasPath("pollIntervalMs") 
            ? getOptions().getInt("pollIntervalMs") : 1000;
        int maxPollDurationMs = getOptions().hasPath("maxPollDurationMs") 
            ? getOptions().getInt("maxPollDurationMs") : 300000;
        
        this.metadata = new MetadataReadingComponent(metadataReader, pollIntervalMs, maxPollDurationMs);
        
        // Batch coordination
        IBatchCoordinator coordinator = getRequiredResource(IBatchCoordinator.class, "db-coordinator");
        this.coordinatorReady = coordinator.setIndexerClass(this.getClass().getName());
        this.coordination = new BatchCoordinationComponent(coordinatorReady);
        
        // Tick buffering
        this.insertBatchSize = getOptions().hasPath("insertBatchSize") 
            ? getOptions().getInt("insertBatchSize") : 1000;
        this.flushTimeoutMs = getOptions().hasPath("flushTimeoutMs") 
            ? getOptions().getLong("flushTimeoutMs") : 5000;
        
        this.buffering = new TickBufferingComponent(
            insertBatchSize,
            flushTimeoutMs,
            this::flushBuffer
        );
        
        // Gap detection
        this.gapTimeoutMs = getOptions().hasPath("gapTimeoutMs") 
            ? getOptions().getLong("gapTimeoutMs") : 60000;
        
        this.gapDetection = new GapDetectionComponent(
            coordinatorReady,
            metadata,
            gapTimeoutMs
        );
        
        // Storage
        this.storage = getRequiredResource(IBatchStorageRead.class, "storage");
    }
    
    @Override
    protected void indexRun(String runId) throws Exception {
        // Load metadata
        metadata.loadMetadata(runId);
        
        // Process batches with 2-phase loop
        processBatchesWithGapFilling(runId);
        
        // Final flush
        buffering.flush();
    }
    
    /**
     * 2-phase batch processing loop.
     * <p>
     * <strong>Phase 1:</strong> Try to fill oldest pending gap<br>
     * <strong>Phase 2:</strong> Discover and process new batches from storage
     */
    private void processBatchesWithGapFilling(String runId) throws Exception {
        String batchPath = "tickdata/" + runId + "/";
        
        while (!Thread.currentThread().isInterrupted()) {
            boolean gapFilled = false;
            boolean newBatchProcessed = false;
            
            // ========== PHASE 1: Gap Filling ==========
            GapInfo oldestGap = coordinatorReady.getOldestPendingGap();
            
            if (oldestGap != null) {
                // Check gap timeout
                if (isGapTimedOut(oldestGap)) {
                    markGapPermanent(oldestGap);
                    continue; // Try next gap
                }
                
                // Search for batch within gap range
                var gapSearchResult = storage.listBatchFiles(
                    batchPath, 
                    null,  // No continuation token - search from start
                    1,     // Only need first match
                    oldestGap.startTick(),
                    oldestGap.endTick()
                );
                
                if (!gapSearchResult.batchFiles().isEmpty()) {
                    var batchFile = gapSearchResult.batchFiles().get(0);
                    
                    log.debug("Found batch in gap: {} fills gap [{}, {})",
                             batchFile.filename(), oldestGap.startTick(), oldestGap.endTick());
                    
                    // Try to claim and process
                    if (processOneBatch(batchPath, batchFile.filename(), 
                                       batchFile.tickStart(), batchFile.tickEnd())) {
                        // Split gap atomically
                        coordinatorReady.splitGap(
                            oldestGap.startTick(),
                            batchFile.tickStart(),
                            batchFile.tickEnd()
                        );
                        
                        gapFilled = true;
                    }
                }
            }
            
            // ========== PHASE 2: New Batch Discovery ==========
            if (!gapFilled) {
                // Discover next batch from storage
                var page = storage.listBatchFiles(batchPath, continuationToken, 1);
                
                if (page.batchFiles().isEmpty()) {
                    // No more batches available - poll again later
                    // Release connections before idle period (reduces pool pressure)
                    releaseAllConnections();
                    
                    Thread.sleep(1000);
                    continuationToken = null; // Reset for next iteration
                    continue;
                }
                
                var batchFile = page.batchFiles().get(0);
                continuationToken = page.continuationToken();
                
                // Check for gap before this batch
                if (gapDetection.checkAndRecordGapBefore(batchFile.tickStart(), batchFile.tickEnd())) {
                    log.debug("Gap detected before batch: {}", batchFile.filename());
                }
                
                // Try to claim and process
                if (processOneBatch(batchPath, batchFile.filename(), 
                                   batchFile.tickStart(), batchFile.tickEnd())) {
                    newBatchProcessed = true;
                }
            }
            
            // Check flush timeout
            buffering.checkTimeout();
            
            // If neither gap filled nor new batch processed, we're done
            if (!gapFilled && !newBatchProcessed && continuationToken == null) {
                break;
            }
        }
    }
    
    /**
     * Attempts to claim and process one batch.
     *
     * @param batchPath Base path for batches
     * @param filename Batch filename
     * @param tickStart Batch start tick
     * @param tickEnd Batch end tick
     * @return true if successfully claimed and processed, false if already claimed
     */
    private boolean processOneBatch(String batchPath, String filename, 
                                    long tickStart, long tickEnd) throws Exception {
        // Try to claim
        if (!coordination.tryClaim(filename, tickStart, tickEnd)) {
            log.debug("Batch already claimed by another indexer: {}", filename);
            return false;
        }
        
        log.debug("Claimed batch: {} (ticks {}-{})", filename, tickStart, tickEnd);
        
        // Read batch file
        byte[] data = storage.readBatchFile(batchPath + filename);
        
        // Parse protobuf
        org.evochora.datapipeline.api.contracts.TickDataBatch batch =
            org.evochora.datapipeline.api.contracts.TickDataBatch.parseFrom(data);
        
        // Buffer ticks
        for (org.evochora.datapipeline.api.contracts.TickData tick : batch.getTicksList()) {
            buffering.addTick(tick);
        }
        
        // Track for completion (mark completed after flush)
        pendingCompletionBatches.add(filename);
        
        log.debug("Buffered {} ticks from {} (buffer size: {})",
                 batch.getTicksCount(), filename, buffering.getBufferSize());
        
        return true;
    }
    
    /**
     * Flushes buffered ticks to database.
     * <p>
     * Called automatically by TickBufferingComponent when buffer full or timeout.
     * Delegates to subclass {@link #processBatch(List)}, then marks pending batches complete.
     */
    private void flushBuffer(List<TickData> ticks) {
        try {
            // Delegate to subclass for actual processing
            processBatch(ticks);
            
            // Mark all pending batches as completed
            for (String filename : pendingCompletionBatches) {
                coordination.markCompleted(filename);
                log.debug("Marked batch completed: {}", filename);
            }
            
            pendingCompletionBatches.clear();
            
        } catch (Exception e) {
            log.error("Failed to process batch", e);
            
            // Mark pending batches as failed
            for (String filename : pendingCompletionBatches) {
                coordination.markFailed(filename);
                log.warn("Marked batch failed: {}", filename);
            }
            
            pendingCompletionBatches.clear();
            
            throw new RuntimeException("Batch processing failed", e);
        }
    }
    
    /**
     * Checks if a gap has exceeded the timeout.
     *
     * @param gap Gap to check
     * @return true if timed out
     */
    private boolean isGapTimedOut(GapInfo gap) {
        // Check first_detected timestamp from database
        // For now, simplified - assume coordinator provides this info
        // (Implementation detail: coordinatorReady.getGapAge(gap.startTick()) or similar)
        return false; // Placeholder
    }
    
    /**
     * Marks a gap as permanent and logs warning (once).
     *
     * @param gap Gap to mark permanent
     */
    private void markGapPermanent(GapInfo gap) {
        // Only log warning once per gap
        if (permanentGapsLogged.putIfAbsent(gap.startTick(), true) == null) {
            log.warn("Permanent gap (data lost): [{}, {})", gap.startTick(), gap.endTick());
        }
        
        coordinatorReady.markGapPermanent(gap.startTick());
    }
    
    /**
     * Releases all cached database connections.
     * <p>
     * Called before idle periods (Thread.sleep during polling).
     * Connections will be re-acquired automatically on next operation.
     */
    private void releaseAllConnections() {
        if (coordination != null) {
            coordination.releaseConnection();
        }
        if (metadata != null) {
            metadata.releaseConnection();
        }
    }
    
    /**
     * Processes a batch of ticks.
     * <p>
     * Subclasses implement actual processing logic (e.g., insert into database).
     *
     * @param ticks Buffered ticks to process
     * @throws Exception if processing fails
     */
    protected abstract void processBatch(List<TickData> ticks) throws Exception;
}
```

## DummyIndexer v5 Implementation (Final)

```java
package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test indexer for validating complete batch processing infrastructure.
 * <p>
 * <strong>Complete Infrastructure Test:</strong>
 * <ul>
 *   <li>Phase 2.5.1: Metadata reading</li>
 *   <li>Phase 2.5.2: Batch coordination (competing consumers)</li>
 *   <li>Phase 2.5.3: Tick buffering</li>
 *   <li>Phase 2.5.4: Gap detection</li>
 *   <li>Phase 2.5.5: 2-phase batch loop with active gap filling</li>
 * </ul>
 * <p>
 * <strong>Processing Logic:</strong> Counts ticks, cells, and organisms (no database writes).
 */
public class DummyIndexer extends AbstractBatchProcessingIndexer implements IMonitorable {
    private static final Logger log = LoggerFactory.getLogger(DummyIndexer.class);
    
    private final AtomicLong runsProcessed = new AtomicLong(0);
    private final AtomicLong batchesProcessed = new AtomicLong(0);
    private final AtomicLong flushCount = new AtomicLong(0);
    private final AtomicLong ticksProcessed = new AtomicLong(0);
    private final AtomicLong cellsObserved = new AtomicLong(0);
    private final AtomicLong organismsObserved = new AtomicLong(0);
    
    public DummyIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        
        // Initialize all components from AbstractBatchProcessingIndexer
        initializeComponents();
    }
    
    @Override
    protected void indexRun(String runId) throws Exception {
        // Delegate to AbstractBatchProcessingIndexer
        super.indexRun(runId);
        
        runsProcessed.incrementAndGet();
        
        log.info("Processing completed: runId={}, ticks={}, cells={}, organisms={}, flushes={}", 
                 runId, ticksProcessed.get(), cellsObserved.get(), 
                 organismsObserved.get(), flushCount.get());
    }
    
    @Override
    protected void processBatch(List<TickData> ticks) throws Exception {
        flushCount.incrementAndGet();
        
        // Count ticks, cells, organisms
        for (TickData tick : ticks) {
            ticksProcessed.incrementAndGet();
            cellsObserved.addAndGet(tick.getCellsCount());
            organismsObserved.addAndGet(tick.getOrganismsCount());
        }
        
        log.debug("Processed {} ticks: {} cells, {} organisms",
                 ticks.size(), 
                 ticks.stream().mapToInt(TickData::getCellsCount).sum(),
                 ticks.stream().mapToInt(TickData::getOrganismsCount).sum());
        
        // No actual database writes - DummyIndexer just observes
    }
    
    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
            "runs_processed", runsProcessed.get(),
            "batches_processed", batchesProcessed.get(),
            "flush_count", flushCount.get(),
            "ticks_processed", ticksProcessed.get(),
            "cells_observed", cellsObserved.get(),
            "organisms_observed", organismsObserved.get()
        );
    }
    
    @Override
    public boolean isHealthy() {
        return getCurrentState() != State.ERROR;
    }
    
    @Override
    public List<OperationalError> getErrors() {
        return Collections.emptyList();
    }
    
    @Override
    public void clearErrors() {
        // No-op
    }
}
```

## Configuration

**Note:** Database resource `index-database` is already configured in `evochora.conf` - no changes needed.

### DummyIndexer v5

```hocon
dummy-indexer {
  className = "org.evochora.datapipeline.services.indexers.DummyIndexer"
  
  resources {
    storage = "storage-read:tick-storage"
    metadataReader = "db-meta-read:index-database"
    coordinator = "db-coordinator:index-database"
  }
  
  options {
    # Metadata polling
    pollIntervalMs = 1000
    maxPollDurationMs = 300000
    
    # Tick buffering
    insertBatchSize = 1000
    flushTimeoutMs = 5000
    
    # Gap detection and filling
    gapTimeoutMs = 60000  # 1 minute
  }
}
```

## Testing Requirements

All tests must follow project standards:

### General Requirements
- Tag with `@Tag("unit")` or `@Tag("integration")`
- Use `@ExtendWith(LogWatchExtension.class)`
- **Log Assertions:**
  - DEBUG and INFO logs are always allowed - never use `@AllowLog` for these
  - WARN and ERROR logs MUST be explicitly expected with `@ExpectLog`
  - Use specific message patterns with wildcards only for variable parts
  - Example: `@ExpectLog(logger = "...", level = WARN, message = "Permanent gap (data lost): [*, *)")`
  - **NEVER** use broad patterns like `message = "*"`
- **Never use `Thread.sleep`** - use Awaitility `await().atMost(...).until(...)` instead
- Leave no artifacts (in-memory H2, temp directories cleaned in `@AfterEach`)
- Use UUID-based database names for parallel test execution
- Verify no connection leaks in `@AfterEach`

### Unit Tests

**AbstractBatchProcessingIndexerTest:**
```java
@ExtendWith(LogWatchExtension.class)
@Tag("unit")
class AbstractBatchProcessingIndexerTest {
    
    @Test
    void testComponentInitialization() {
        // Create test subclass
        // Verify all components initialized
    }
    
    @Test
    void testProcessOneBatch_success() {
        // Mock claim success
        // Mock batch read
        // Verify ticks buffered
        // Verify batch added to pendingCompletion
    }
    
    @Test
    void testProcessOneBatch_alreadyClaimed() {
        // Mock claim failure
        // Verify batch skipped
        // Verify no buffering
    }
    
    @Test
    void testFlushBuffer_success() {
        // Add batches to pendingCompletion
        // Trigger flush
        // Verify processBatch called
        // Verify markCompleted called for all batches
        // Verify pendingCompletion cleared
    }
    
    @Test
    void testFlushBuffer_failure() {
        // Mock processBatch to throw exception
        // Add batches to pendingCompletion
        // Trigger flush
        // Verify markFailed called for all batches
        // Verify pendingCompletion cleared
    }
}
```

### Integration Tests

**DummyIndexerV5IntegrationTest:**
```java
@ExtendWith(LogWatchExtension.class)
@Tag("integration")
class DummyIndexerV5IntegrationTest {
    
    @Test
    void testFullPipeline_Sequential() throws Exception {
        // Create 10 sequential batches (no gaps)
        String runId = createTestRun(sequential = true, numBatches = 10);
        
        DummyIndexer indexer = createIndexer(runId);
        indexer.start();
        
        // Wait for completion
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.STOPPED);
        
        // Verify all 10 batches processed, 0 gaps
        assertEquals(10, indexer.getMetrics().get("batches_processed").intValue());
        assertEquals(0, queryPendingGapCount(runId));
    }
    
    @Test
    void testFullPipeline_OutOfOrderWithGapFilling() throws Exception {
        // Create batches out of order
        String runId = createTestRun();
        createBatch(runId, tickStart=0, tickEnd=990);
        createBatch(runId, tickStart=2000, tickEnd=2990);  // Gap!
        createBatch(runId, tickStart=4000, tickEnd=4990);
        
        DummyIndexer indexer = createIndexer(runId);
        indexer.start();
        
        // Wait for initial processing
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("batches_processed").intValue() >= 2);
        
        // Verify gap detected
        assertEquals(1, queryPendingGapCount(runId));
        
        // Now add missing batch
        createBatch(runId, tickStart=1000, tickEnd=1990);
        
        // Wait for gap fill
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("batches_processed").intValue() == 4);
        
        indexer.stop();
        
        // Verify gap filled
        assertEquals(0, queryPendingGapCount(runId));
    }
    
    @Test
    @ExpectLog(logger = "org.evochora.datapipeline.services.indexers.AbstractBatchProcessingIndexer",
               level = WARN, message = "Permanent gap (data lost): [*, *)")
    void testFullPipeline_PermanentGap() throws Exception {
        // Create batches with permanent gap
        String runId = createTestRun();
        createBatch(runId, tickStart=0, tickEnd=990);
        createBatch(runId, tickStart=2000, tickEnd=2990);  // Gap never filled
        
        DummyIndexer indexer = createIndexer(runId, gapTimeoutMs = 1000);
        indexer.start();
        
        // Wait for permanent gap detection
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> queryPermanentGapCount(runId) == 1);
        
        indexer.stop();
        
        // Verify warning logged exactly once, processing continued
        assertEquals(1, queryPermanentGapCount(runId));
        assertTrue(indexer.getMetrics().get("batches_processed").intValue() >= 2);
    }
    
    @Test
    void testFullPipeline_CompetingConsumers() throws Exception {
        // Create 50 batch files
        String runId = createTestRun(numBatches = 50);
        
        // Start 3 DummyIndexer instances
        List<DummyIndexer> indexers = startIndexers(runId, count = 3);
        
        // Wait for all batches processed
        await().atMost(30, TimeUnit.SECONDS)
            .until(() -> {
                long total = indexers.stream()
                    .mapToLong(idx -> idx.getMetrics().get("batches_processed").longValue())
                    .sum();
                return total == 50;
            });
        
        stopAll(indexers);
        
        // Verify no duplicates
        assertEquals(50, queryCompletedBatchCount(runId));
    }
    
    @Test
    void testFullPipeline_LargeDataset() throws Exception {
        // Create 1000 batch files
        String runId = createTestRun(numBatches = 1000);
        
        DummyIndexer indexer = createIndexer(runId);
        indexer.start();
        
        // Wait for completion (should be < 10 seconds)
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("batches_processed").intValue() == 1000);
        
        indexer.stop();
        
        // Verify no connection leaks
        assertEquals(0, testDatabase.getMetrics().get("h2_pool_active_connections"));
    }
    
    @Test
    void testFullPipeline_BufferingSmallerThanFiles() throws Exception {
        // Create 5 batches (100 ticks each)
        String runId = createTestRun(numBatches = 5, ticksPerBatch = 100);
        
        DummyIndexer indexer = createIndexer(runId, insertBatchSize = 50);
        indexer.start();
        
        // Wait for completion
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.STOPPED);
        
        // Verify 10 flushes (500 / 50)
        assertEquals(10, indexer.getMetrics().get("flush_count").intValue());
    }
    
    @Test
    void testFullPipeline_BufferingLargerThanFiles() throws Exception {
        // Create 5 batches (100 ticks each)
        String runId = createTestRun(numBatches = 5, ticksPerBatch = 100);
        
        DummyIndexer indexer = createIndexer(runId, insertBatchSize = 300);
        indexer.start();
        
        // Wait for completion
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.STOPPED);
        
        // Verify 2 flushes (300 + 200)
        assertEquals(2, indexer.getMetrics().get("flush_count").intValue());
    }
}
```

## Logging Strategy

### Principles

**❌ NEVER:**
- INFO in loops
- Multi-line logs
- Phase prefixes
- Verbose gap search logs

**✅ ALWAYS:**
- INFO very sparingly (completion summary only)
- DEBUG for batch operations
- Single line per event
- WARN for permanent gaps (once per gap)

### Log Levels

**INFO:**
- Processing completed with comprehensive summary

**DEBUG:**
- Gap filling operations
- Batch discovery
- Gap detection

**WARN:**
- Permanent gaps (once per gap)

**ERROR:**
- Processing failures

### Examples

```java
// ✅ GOOD - INFO only for completion
log.info("Processing completed: runId={}, ticks={}, cells={}, organisms={}, flushes={}", 
         runId, ticksProcessed, cellsObserved, organismsObserved, flushCount);

// ✅ GOOD - DEBUG for gap operations
log.debug("Found batch in gap: {} fills gap [{}, {})", filename, gapStart, gapEnd);
log.debug("Gap detected before batch: {}", filename);

// ✅ GOOD - WARN for permanent gap (once)
log.warn("Permanent gap (data lost): [{}, {})", gapStart, gapEnd);

// ❌ BAD - INFO in loop
log.info("Discovered new batch: {}", filename);

// ❌ BAD - Redundant info
log.info("All batches processed, no more gaps to fill");
```

## Monitoring Requirements

### DummyIndexer v5 Metrics

```java
metrics.put("runs_processed", runsProcessed.get());      // O(1)
metrics.put("batches_processed", batchesProcessed.get()); // O(1)
metrics.put("flush_count", flushCount.get());            // O(1)
metrics.put("ticks_processed", ticksProcessed.get());    // O(1)
metrics.put("cells_observed", cellsObserved.get());      // O(1)
metrics.put("organisms_observed", organismsObserved.get()); // O(1)
```

**Metrics Inheritance:**
- Components (coordination, buffering, gap detection) provide their own metrics
- Subclasses should aggregate component metrics as needed

## JavaDoc Requirements

All public classes, interfaces, and methods must have comprehensive JavaDoc:

**Required Elements:**
- Class/Interface purpose
- Thread safety guarantees
- Usage examples (for public APIs)
- Parameter descriptions (@param)
- Return value descriptions (@return)
- Exception documentation (@throws)
- Implementation notes where relevant
- **Capability mapping (for AbstractDatabaseResource methods):** 
  - All new `do*()` methods in AbstractDatabaseResource must document which capability interface they belong to using `<strong>Capability:</strong> {@link InterfaceName#methodName()}` in the JavaDoc
  - **Implementations (e.g., H2Database) should also include minimal JavaDoc with capability link** even with `@Override`, for immediate visibility without navigating to parent class
  - Example: `/** Implements {@link IBatchCoordinatorReady#markGapPermanent(long)}. */`

Examples shown in code sections above demonstrate proper JavaDoc structure.

## Implementation Checklist

- [ ] Create AbstractBatchProcessingIndexer
- [ ] Implement 2-phase batch processing loop
- [ ] Implement gap timeout detection
- [ ] Implement permanent gap marking with single warning
- [ ] Update DummyIndexer to v5 (extends AbstractBatchProcessingIndexer)
- [ ] Write unit tests for AbstractBatchProcessingIndexer
- [ ] Write integration tests for DummyIndexer v5 (sequential, out-of-order, gap filling, permanent gaps, competing consumers, large dataset, buffering variants)
- [ ] Verify all tests pass
- [ ] Verify no connection leaks
- [ ] Verify JavaDoc complete
- [ ] Performance test with 1000+ batches

## Architecture Notes

### Component-Based Design

`AbstractBatchProcessingIndexer` uses composition over inheritance:
- Each component (metadata, coordination, buffering, gap detection) is independent
- Subclasses can choose which components to use
- Future indexers can reuse infrastructure without tight coupling

### 2-Phase Loop Rationale

**Why Phase 1 (Gap Filling) Before Phase 2 (New Batches)?**
1. Ensures oldest gaps filled first (fairness)
2. Prevents gap accumulation
3. Maintains rough chronological ordering
4. Simplifies monitoring (gap count should trend down)

**Why Pagination Token Reset?**
- After processing all discovered batches, reset token to search for newly arrived batches
- Prevents missing batches that arrive while processing earlier pages

### Permanent Gap Handling

**Why Keep Permanent Gaps in Database?**
- Observability: Admins can query for lost data
- Debugging: Understand simulation issues
- Metrics: Track data loss over time

**Why Single Warning Log?**
- Avoid log spam
- `ConcurrentHashMap` tracks logged gaps per indexer instance
- Future enhancement: Could persist logged state to database for multi-instance coordination

---

**Previous Phase:** [14_4_GAP_DETECTION.md](./14_4_GAP_DETECTION.md)  
**Main Specification:** [14_BATCH_COORDINATION_AND_DUMMYINDEXER.md](./14_BATCH_COORDINATION_AND_DUMMYINDEXER.md)

---

## Summary: Complete 5-Phase Implementation

### Phase 2.5.1: Metadata Reading
- ✅ IMetadataReader capability
- ✅ MetadataReadingComponent (polling)
- ✅ DummyIndexer v1 (reads metadata)

### Phase 2.5.2: Batch Coordination
- ✅ IBatchCoordinator + IBatchCoordinatorReady (fluent API)
- ✅ coordinator_batches table (composite key)
- ✅ BatchCoordinationComponent
- ✅ DummyIndexer v2 (claims and completes batches)

### Phase 2.5.3: Tick Buffering
- ✅ TickBufferingComponent (flexible batch size, timeout)
- ✅ DummyIndexer v3 (buffers and flushes ticks)

### Phase 2.5.4: Gap Detection
- ✅ coordinator_gaps table (interval-based)
- ✅ GapDetectionComponent (detection, splitting)
- ✅ DummyIndexer v4 (detects and logs gaps)

### Phase 2.5.5: Batch Processing Loop
- ✅ AbstractBatchProcessingIndexer (2-phase loop)
- ✅ Active gap filling with tick-range queries
- ✅ Permanent gap marking
- ✅ DummyIndexer v5 (full infrastructure test)

**Result:** Complete, reusable infrastructure for all future batch-processing indexers!


