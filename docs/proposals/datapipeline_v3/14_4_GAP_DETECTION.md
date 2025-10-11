# Phase 2.5.4: Gap Detection Component

**Part of:** [14_BATCH_COORDINATION_AND_DUMMYINDEXER.md](./14_BATCH_COORDINATION_AND_DUMMYINDEXER.md)

**Status:** Ready for implementation

---

## Goal

Implement gap detection component to handle out-of-order batch arrival and missing data.

When batch files arrive out of order (due to competing consumer PersistenceService instances), gaps in tick sequences must be detected, tracked, and filled when corresponding batches become available.

## Scope

**In Scope:**
1. `GapInfo` record for representing missing tick ranges
2. `coordinator_gaps` database table with interval-based gap tracking
3. `AbstractDatabaseResource` extensions for gap operations
4. `H2Database` implementation of gap operations (including atomic split)
5. `GapDetectionComponent` for gap detection, searching, and management
6. DummyIndexer v4 - detects and logs gaps (passive observation only)
7. Unit tests for all components
8. Integration tests for DummyIndexer v4

**Out of Scope:**
- Active gap filling with batch processing loop (Phase 2.5.5)
- AbstractBatchProcessingIndexer (Phase 2.5.5)

## Success Criteria

1. `coordinator_gaps` table tracks missing tick ranges with `gap_start_tick` and `gap_end_tick`
2. Gaps detected based on `tick_end` of last completed batch and `samplingInterval`
3. Gap splitting algorithm works atomically when batch found within existing gap
4. Permanent gaps marked after timeout and logged only once
5. DummyIndexer v4 successfully detects and logs gaps
6. All tests pass with proper log validation
7. No connection leaks (verified in tests)
8. Metrics tracked with O(1) operations

## Prerequisites

- Phase 2.5.1: Metadata Reading (completed)
- Phase 2.5.2: Batch Coordination (completed)
- Phase 2.5.3: Tick Buffering (completed)
- DummyIndexer v3

## Package Structure

```
org.evochora.datapipeline.api.resources.database/
  - GapInfo.java                          # NEW - Gap representation record

org.evochora.datapipeline.resources.database/
  - AbstractDatabaseResource.java         # EXTEND - Add gap operations
  - H2Database.java                       # EXTEND - Implement gap operations

org.evochora.datapipeline.services.indexers/
  - DummyIndexer.java                     # EXTEND - v4: Gap detection (passive)
  └── components/
      - GapDetectionComponent.java        # NEW - Gap detection and management
```

## Database Schema

### coordinator_gaps Table

```sql
CREATE TABLE IF NOT EXISTS coordinator_gaps (
    indexer_class VARCHAR(500) NOT NULL,      -- Fully qualified class name
    gap_start_tick BIGINT NOT NULL,           -- Inclusive start of missing range
    gap_end_tick BIGINT NOT NULL,             -- Exclusive end of missing range
    first_detected TIMESTAMP NOT NULL,        -- When gap was first detected
    status VARCHAR(20) NOT NULL,              -- 'pending', 'permanent'
    
    PRIMARY KEY (indexer_class, gap_start_tick),
    CONSTRAINT check_gap_status CHECK (status IN ('pending', 'permanent'))
);
```

**Design Notes:**
- Tracks missing tick *ranges* (not individual batches)
- `gap_start_tick` and `gap_end_tick` define interval (start inclusive, end exclusive)
- Primary key on `(indexer_class, gap_start_tick)` - each indexer tracks its own gaps
- `status='pending'`: Still searching for missing batches
- `status='permanent'`: Timeout exceeded, batch likely lost

**Table Creation:**
- Lazy creation with cached check (same pattern as coordinator_batches)
- `ConcurrentHashMap<String, Boolean> coordinatorGapsInitialized` in AbstractDatabaseResource

## GapInfo Record

```java
package org.evochora.datapipeline.api.resources.database;

/**
 * Represents a missing tick range (gap) in the indexer's processed data.
 * <p>
 * Gap range is [startTick, endTick) - start inclusive, end exclusive.
 */
public record GapInfo(
    long startTick,    // Inclusive start of gap
    long endTick,      // Exclusive end of gap
    String status      // 'pending' or 'permanent'
) {
    /**
     * Checks if this gap contains the specified tick range.
     *
     * @param tickStart Start of range to check
     * @param tickEnd End of range to check
     * @return true if range fully contained within gap
     */
    public boolean contains(long tickStart, long tickEnd) {
        return tickStart >= startTick && tickEnd <= endTick;
    }
    
    /**
     * Checks if this gap overlaps the specified tick range.
     *
     * @param tickStart Start of range to check
     * @param tickEnd End of range to check
     * @return true if range overlaps gap
     */
    public boolean overlaps(long tickStart, long tickEnd) {
        return tickStart < endTick && tickEnd > startTick;
    }
    
    /**
     * Gets the size of this gap in ticks.
     *
     * @return Number of ticks in gap
     */
    public long size() {
        return endTick - startTick;
    }
}
```

## AbstractDatabaseResource Extensions

```java
// In AbstractDatabaseResource.java

// Cached table initialization check for gaps
private final ConcurrentHashMap<String, Boolean> coordinatorGapsInitialized = new ConcurrentHashMap<>();

/**
 * Ensures coordinator_gaps table exists for the current schema.
 *
 * @param connection Database connection
 * @throws Exception if table creation fails
 */
protected abstract void doEnsureCoordinatorGapsTable(Object connection) throws Exception;

/**
 * Records a new gap in the database.
 * <p>
 * Performs lazy table creation with cached check before INSERT.
 *
 * @param connection Database connection
 * @param indexerClass Fully qualified class name
 * @param gapStart Inclusive start of gap
 * @param gapEnd Exclusive end of gap
 * @throws Exception if insert fails
 */
protected abstract void doRecordGap(Object connection, String indexerClass, 
                                    long gapStart, long gapEnd) throws Exception;

/**
 * Retrieves the oldest pending gap (lowest gap_start_tick).
 * <p>
 * Used to prioritize filling oldest gaps first.
 *
 * @param connection Database connection
 * @param indexerClass Fully qualified class name
 * @return GapInfo or null if no pending gaps
 * @throws Exception if query fails
 */
protected abstract GapInfo doGetOldestPendingGap(Object connection, String indexerClass) 
        throws Exception;

/**
 * Splits a gap atomically when a batch is found within it.
 * <p>
 * Algorithm:
 * <ol>
 *   <li>SELECT FOR UPDATE on existing gap (pessimistic lock)</li>
 *   <li>DELETE old gap</li>
 *   <li>INSERT up to 2 new gaps (before and/or after found batch)</li>
 * </ol>
 * <p>
 * Example: Gap [1000, 3000), found batch [1500, 2000)
 * <ul>
 *   <li>Delete [1000, 3000)</li>
 *   <li>Insert [1000, 1500) - before batch</li>
 *   <li>Insert [2000, 3000) - after batch</li>
 * </ul>
 *
 * @param connection Database connection
 * @param indexerClass Fully qualified class name
 * @param gapStart Original gap start
 * @param batchTickStart Start of found batch
 * @param batchTickEnd End of found batch
 * @throws Exception if split fails
 */
protected abstract void doSplitGap(Object connection, String indexerClass,
                                   long gapStart, long batchTickStart, long batchTickEnd) 
        throws Exception;

/**
 * Marks a gap as permanent (lost data).
 * <p>
 * Updates status to 'permanent'. Gap remains in database for observability
 * but is excluded from active search.
 *
 * @param connection Database connection
 * @param indexerClass Fully qualified class name
 * @param gapStart Gap start tick
 * @throws Exception if update fails
 */
protected abstract void doMarkGapPermanent(Object connection, String indexerClass, 
                                          long gapStart) throws Exception;
```

## H2Database Implementation

```java
// In H2Database.java

@Override
protected void doEnsureCoordinatorGapsTable(Object connection) throws Exception {
    Connection conn = (Connection) connection;
    Statement stmt = conn.createStatement();
    
    stmt.execute("""
        CREATE TABLE IF NOT EXISTS coordinator_gaps (
            indexer_class VARCHAR(500) NOT NULL,
            gap_start_tick BIGINT NOT NULL,
            gap_end_tick BIGINT NOT NULL,
            first_detected TIMESTAMP NOT NULL,
            status VARCHAR(20) NOT NULL,
            
            PRIMARY KEY (indexer_class, gap_start_tick),
            CONSTRAINT check_gap_status CHECK (status IN ('pending', 'permanent'))
        )
        """);
    
    tablesCreated.incrementAndGet();
}

@Override
protected void doRecordGap(Object connection, String indexerClass, 
                          long gapStart, long gapEnd) throws Exception {
    Connection conn = (Connection) connection;
    
    // Lazy table creation with cached check
    String currentSchema = conn.getSchema();
    coordinatorGapsInitialized.computeIfAbsent(currentSchema, schema -> {
        try {
            doEnsureCoordinatorGapsTable(connection);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create coordinator_gaps table", e);
        }
    });
    
    PreparedStatement stmt = conn.prepareStatement("""
        INSERT INTO coordinator_gaps 
            (indexer_class, gap_start_tick, gap_end_tick, first_detected, status) 
        VALUES (?, ?, ?, CURRENT_TIMESTAMP, 'pending')
        """);
    
    stmt.setString(1, indexerClass);
    stmt.setLong(2, gapStart);
    stmt.setLong(3, gapEnd);
    
    stmt.executeUpdate();
    queriesExecuted.incrementAndGet();
}

@Override
protected GapInfo doGetOldestPendingGap(Object connection, String indexerClass) throws Exception {
    Connection conn = (Connection) connection;
    
    PreparedStatement stmt = conn.prepareStatement("""
        SELECT gap_start_tick, gap_end_tick, status 
        FROM coordinator_gaps 
        WHERE indexer_class = ? AND status = 'pending'
        ORDER BY gap_start_tick ASC
        LIMIT 1
        """);
    
    stmt.setString(1, indexerClass);
    ResultSet rs = stmt.executeQuery();
    
    queriesExecuted.incrementAndGet();
    
    if (rs.next()) {
        return new GapInfo(
            rs.getLong("gap_start_tick"),
            rs.getLong("gap_end_tick"),
            rs.getString("status")
        );
    }
    
    return null;
}

@Override
protected void doSplitGap(Object connection, String indexerClass,
                         long gapStart, long batchTickStart, long batchTickEnd) 
        throws Exception {
    Connection conn = (Connection) connection;
    
    // Step 1: Lock the gap row (pessimistic locking)
    PreparedStatement lockStmt = conn.prepareStatement("""
        SELECT gap_start_tick, gap_end_tick 
        FROM coordinator_gaps 
        WHERE indexer_class = ? AND gap_start_tick = ?
        FOR UPDATE
        """);
    
    lockStmt.setString(1, indexerClass);
    lockStmt.setLong(2, gapStart);
    ResultSet rs = lockStmt.executeQuery();
    
    queriesExecuted.incrementAndGet();
    
    if (!rs.next()) {
        // Gap already closed or doesn't exist
        return;
    }
    
    long originalGapEnd = rs.getLong("gap_end_tick");
    
    // Step 2: Delete old gap
    PreparedStatement deleteStmt = conn.prepareStatement("""
        DELETE FROM coordinator_gaps 
        WHERE indexer_class = ? AND gap_start_tick = ?
        """);
    
    deleteStmt.setString(1, indexerClass);
    deleteStmt.setLong(2, gapStart);
    deleteStmt.executeUpdate();
    
    queriesExecuted.incrementAndGet();
    
    // Step 3: Insert new gaps (before and/or after batch)
    PreparedStatement insertStmt = conn.prepareStatement("""
        INSERT INTO coordinator_gaps 
            (indexer_class, gap_start_tick, gap_end_tick, first_detected, status) 
        VALUES (?, ?, ?, CURRENT_TIMESTAMP, 'pending')
        """);
    
    // Gap before batch (if exists)
    if (gapStart < batchTickStart) {
        insertStmt.setString(1, indexerClass);
        insertStmt.setLong(2, gapStart);
        insertStmt.setLong(3, batchTickStart);
        insertStmt.executeUpdate();
        queriesExecuted.incrementAndGet();
    }
    
    // Gap after batch (if exists)
    if (batchTickEnd < originalGapEnd) {
        insertStmt.setString(1, indexerClass);
        insertStmt.setLong(2, batchTickEnd);
        insertStmt.setLong(3, originalGapEnd);
        insertStmt.executeUpdate();
        queriesExecuted.incrementAndGet();
    }
}

@Override
protected void doMarkGapPermanent(Object connection, String indexerClass, 
                                 long gapStart) throws Exception {
    Connection conn = (Connection) connection;
    
    PreparedStatement stmt = conn.prepareStatement("""
        UPDATE coordinator_gaps 
        SET status = 'permanent' 
        WHERE indexer_class = ? AND gap_start_tick = ? AND status = 'pending'
        """);
    
    stmt.setString(1, indexerClass);
    stmt.setLong(2, gapStart);
    
    stmt.executeUpdate();
    queriesExecuted.incrementAndGet();
}
```

## GapDetectionComponent

```java
package org.evochora.datapipeline.services.indexers.components;

import org.evochora.datapipeline.api.resources.database.GapInfo;
import org.evochora.datapipeline.api.resources.database.IBatchCoordinatorReady;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Component for detecting and managing gaps in processed tick data.
 * <p>
 * Handles:
 * <ul>
 *   <li>Gap detection based on tick_end of last completed batch</li>
 *   <li>Gap recording in database (coordinator_gaps table)</li>
 *   <li>Gap splitting when batch found within existing gap</li>
 *   <li>Permanent gap marking after timeout</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Should be used by single indexer instance only.
 */
public class GapDetectionComponent {
    private static final Logger log = LoggerFactory.getLogger(GapDetectionComponent.class);
    
    private final IBatchCoordinatorReady coordinator;
    private final MetadataReadingComponent metadata;
    private final int samplingInterval;
    private final long gapTimeoutMs;
    
    // Track which permanent gaps we've already logged (to avoid spam)
    private final ConcurrentHashMap<Long, Boolean> permanentGapsLogged = new ConcurrentHashMap<>();
    
    /**
     * Creates gap detection component.
     *
     * @param coordinator Ready coordinator (after setIndexerClass)
     * @param metadata Metadata component for samplingInterval access
     * @param gapTimeoutMs Time after which gap considered permanent (milliseconds)
     */
    public GapDetectionComponent(IBatchCoordinatorReady coordinator,
                                MetadataReadingComponent metadata,
                                long gapTimeoutMs) {
        this.coordinator = coordinator;
        this.metadata = metadata;
        this.samplingInterval = metadata.getSamplingInterval();
        this.gapTimeoutMs = gapTimeoutMs;
    }
    
    /**
     * Checks if there's a gap before the specified batch.
     * <p>
     * Compares batch tick_start with expected next tick (maxCompletedTickEnd + samplingInterval).
     * If gap exists, records it in database.
     *
     * @param batchTickStart Start tick of current batch
     * @param batchTickEnd End tick of current batch
     * @return true if gap detected and recorded, false otherwise
     */
    public boolean checkAndRecordGapBefore(long batchTickStart, long batchTickEnd) {
        long maxCompleted = coordinator.getMaxCompletedTickEnd();
        
        if (maxCompleted == -1) {
            // First batch - check if starts at expected first tick (sampling interval multiple)
            if (batchTickStart == 0 || batchTickStart % samplingInterval == 0) {
                return false; // No gap
            }
            
            // Gap from 0 to batchTickStart
            log.debug("Gap before first batch: [0, {})", batchTickStart);
            recordGap(0, batchTickStart);
            return true;
        }
        
        // Expected next tick after last completed batch
        long expectedNextTick = maxCompleted + samplingInterval;
        
        if (batchTickStart == expectedNextTick) {
            return false; // No gap
        }
        
        if (batchTickStart > expectedNextTick) {
            // Gap: (maxCompleted, batchTickStart)
            log.debug("Gap detected: [{}, {})", maxCompleted, batchTickStart);
            recordGap(maxCompleted, batchTickStart);
            return true;
        }
        
        // batchTickStart < expectedNextTick - batch overlaps or fills gap
        return false;
    }
    
    /**
     * Checks if the specified batch fills an existing gap.
     * <p>
     * If batch falls within a tracked gap, atomically splits the gap.
     *
     * @param gapInfo Gap to check
     * @param batchTickStart Start tick of batch
     * @param batchTickEnd End tick of batch
     * @return true if batch fills (part of) the gap, false otherwise
     */
    public boolean checkGapFilled(GapInfo gapInfo, long batchTickStart, long batchTickEnd) {
        if (gapInfo.overlaps(batchTickStart, batchTickEnd)) {
            log.debug("Batch fills gap: batch[{}, {}) within gap[{}, {})",
                     batchTickStart, batchTickEnd, gapInfo.startTick(), gapInfo.endTick());
            
            splitGap(gapInfo.startTick(), batchTickStart, batchTickEnd);
            return true;
        }
        
        return false;
    }
    
    /**
     * Gets the oldest pending gap.
     *
     * @return GapInfo or null if no pending gaps
     */
    public GapInfo getOldestPendingGap() {
        // Delegated to coordinator (which calls database)
        return null; // Phase 2.5.4: Passive observation only
    }
    
    /**
     * Checks gap timeout and marks as permanent if exceeded.
     * <p>
     * Should be called periodically to detect stale gaps.
     *
     * @param gap Gap to check
     */
    public void checkGapTimeout(GapInfo gap) {
        // Phase 2.5.4: Not implemented (passive observation only)
        // Phase 2.5.5: Will check first_detected timestamp and mark permanent
    }
    
    /**
     * Marks a gap as permanent (lost data).
     * <p>
     * Logs warning only once per gap.
     *
     * @param gapStart Gap start tick
     */
    public void markGapPermanent(long gapStart) {
        // Phase 2.5.4: Not implemented (passive observation only)
        // Phase 2.5.5: Will update database and log warning
    }
    
    private void recordGap(long gapStart, long gapEnd) {
        try {
            // Call coordinator to record gap in database
            // (Coordinator has recordGap method added to IBatchCoordinatorReady)
            // No log - gap detection already logged
            
        } catch (Exception e) {
            log.error("Failed to record gap [{}, {}): {}", gapStart, gapEnd, e.getMessage());
        }
    }
    
    private void splitGap(long originalGapStart, long batchTickStart, long batchTickEnd) {
        try {
            // Call coordinator to split gap atomically
            // No log - gap filling already logged
            
        } catch (Exception e) {
            log.error("Failed to split gap: {}", e.getMessage());
        }
    }
}
```

## IBatchCoordinatorReady Extensions

For Phase 2.5.4, we need to add gap-related methods to the coordinator interface:

```java
// Add to IBatchCoordinatorReady interface:

/**
 * Records a new gap in the database.
 *
 * @param gapStart Inclusive start of gap
 * @param gapEnd Exclusive end of gap
 */
void recordGap(long gapStart, long gapEnd);

/**
 * Retrieves the oldest pending gap.
 *
 * @return GapInfo or null if no pending gaps
 */
GapInfo getOldestPendingGap();

/**
 * Splits a gap when a batch is found within it.
 *
 * @param originalGapStart Original gap start
 * @param batchTickStart Start of found batch
 * @param batchTickEnd End of found batch
 */
void splitGap(long originalGapStart, long batchTickStart, long batchTickEnd);

/**
 * Marks a gap as permanent.
 *
 * @param gapStart Gap start tick
 */
void markGapPermanent(long gapStart);
```

## DummyIndexer v4 Implementation

```java
package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.database.IBatchCoordinator;
import org.evochora.datapipeline.api.resources.database.IBatchCoordinatorReady;
import org.evochora.datapipeline.api.resources.database.IMetadataReader;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.services.indexers.components.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test indexer for validating gap detection infrastructure.
 * <p>
 * <strong>Phase 2.5.1:</strong> Metadata reading<br>
 * <strong>Phase 2.5.2:</strong> Batch claiming and completion<br>
 * <strong>Phase 2.5.3:</strong> Tick buffering<br>
 * <strong>Phase 2.5.4:</strong> Gap detection (passive observation)
 * <ul>
 *   <li>Detects gaps before batches</li>
 *   <li>Logs gaps as they're discovered</li>
 *   <li>Does NOT actively fill gaps (added in Phase 2.5.5)</li>
 * </ul>
 */
public class DummyIndexer extends AbstractIndexer implements IMonitorable {
    private static final Logger log = LoggerFactory.getLogger(DummyIndexer.class);
    
    private final MetadataReadingComponent metadataComponent;
    private final BatchCoordinationComponent coordinationComponent;
    private final TickBufferingComponent bufferingComponent;
    private final GapDetectionComponent gapDetectionComponent;
    private final IBatchStorageRead storage;
    
    private final int insertBatchSize;
    
    private final AtomicLong runsProcessed = new AtomicLong(0);
    private final AtomicLong batchesClaimed = new AtomicLong(0);
    private final AtomicLong batchesProcessed = new AtomicLong(0);
    private final AtomicLong ticksObserved = new AtomicLong(0);
    private final AtomicLong flushCount = new AtomicLong(0);
    private final AtomicLong ticksFlushed = new AtomicLong(0);
    private final AtomicLong gapsDetected = new AtomicLong(0);
    
    public DummyIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        
        // Setup metadata reading component (Phase 2.5.1)
        IMetadataReader metadataReader = getRequiredResource(IMetadataReader.class, "db-meta-read");
        int pollIntervalMs = options.hasPath("pollIntervalMs") ? options.getInt("pollIntervalMs") : 1000;
        int maxPollDurationMs = options.hasPath("maxPollDurationMs") ? options.getInt("maxPollDurationMs") : 300000;
        
        this.metadataComponent = new MetadataReadingComponent(metadataReader, pollIntervalMs, maxPollDurationMs);
        
        // Setup batch coordination component (Phase 2.5.2)
        IBatchCoordinator coordinator = getRequiredResource(IBatchCoordinator.class, "db-coordinator");
        IBatchCoordinatorReady coordinatorReady = coordinator.setIndexerClass(this.getClass().getName());
        this.coordinationComponent = new BatchCoordinationComponent(coordinatorReady);
        
        // Setup tick buffering component (Phase 2.5.3)
        this.insertBatchSize = options.hasPath("insertBatchSize") ? options.getInt("insertBatchSize") : 1000;
        long flushTimeoutMs = options.hasPath("flushTimeoutMs") ? options.getLong("flushTimeoutMs") : 5000;
        
        this.bufferingComponent = new TickBufferingComponent(
            insertBatchSize, 
            flushTimeoutMs, 
            this::flushTicks
        );
        
        // Setup gap detection component (Phase 2.5.4)
        long gapTimeoutMs = options.hasPath("gapTimeoutMs") ? options.getLong("gapTimeoutMs") : 60000; // 1 minute
        
        this.gapDetectionComponent = new GapDetectionComponent(
            coordinatorReady,
            metadataComponent,
            gapTimeoutMs
        );
        
        // Storage for batch discovery
        this.storage = getRequiredResource(IBatchStorageRead.class, "storage");
    }
    
    @Override
    protected void indexRun(String runId) throws Exception {
        // Load metadata
        metadataComponent.loadMetadata(runId);
        
        log.debug("Metadata available: runId={}, samplingInterval={}", 
                 runId, metadataComponent.getSamplingInterval());
        
        runsProcessed.incrementAndGet();
        
        // Process batches
        processBatches(runId);
        
        // Flush any remaining buffered ticks
        bufferingComponent.flush();
        
        log.info("Processing completed: runId={}, batches={}, gaps={}", 
                 runId, batchesProcessed.get(), gapsDetected.get());
    }
    
    private void processBatches(String runId) throws Exception {
        String batchPath = "tickdata/" + runId + "/";
        String continuationToken = null;
        int batchesInPage = 0;
        
        do {
            // List next page of batches (max 100 per page)
            var page = storage.listBatchFiles(batchPath, continuationToken, 100);
            batchesInPage = page.batchFiles().size();
            
            for (var batchFile : page.batchFiles()) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                
                // Parse tick range from filename
                long tickStart = batchFile.tickStart();
                long tickEnd = batchFile.tickEnd();
                String filename = batchFile.filename();
                
                // Check for gap before this batch
                if (gapDetectionComponent.checkAndRecordGapBefore(tickStart, tickEnd)) {
                    gapsDetected.incrementAndGet();
                }
                
                // Try to claim batch
                if (coordinationComponent.tryClaim(filename, tickStart, tickEnd)) {
                    batchesClaimed.incrementAndGet();
                    
                    log.debug("Claimed batch: {} (ticks {}-{})", filename, tickStart, tickEnd);
                    
                    // Process batch with buffering
                    processOneBatch(batchPath + filename, filename);
                    
                    // Check flush timeout (between batches)
                    bufferingComponent.checkTimeout();
                    
                    batchesProcessed.incrementAndGet();
                }
            }
            
            continuationToken = page.continuationToken();
            
        } while (continuationToken != null && batchesInPage > 0);
    }
    
    private void processOneBatch(String fullPath, String filename) throws Exception {
        // Read batch file
        byte[] data = storage.readBatchFile(fullPath);
        
        // Parse protobuf
        org.evochora.datapipeline.api.contracts.TickDataBatch batch =
            org.evochora.datapipeline.api.contracts.TickDataBatch.parseFrom(data);
        
        // Buffer ticks
        for (TickData tick : batch.getTicksList()) {
            ticksObserved.incrementAndGet();
            bufferingComponent.addTick(tick);
        }
        
        log.debug("Buffered {} ticks from {}, buffer size: {}", 
                 batch.getTicksCount(), filename, bufferingComponent.getBufferSize());
    }
    
    private void flushTicks(List<TickData> ticks) {
        // Just count flushed ticks (no actual processing)
        flushCount.incrementAndGet();
        ticksFlushed.addAndGet(ticks.size());
        
        log.debug("Flushed {} ticks, total: {}", ticks.size(), ticksFlushed.get());
    }
    
    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
            "runs_processed", runsProcessed.get(),
            "batches_claimed", batchesClaimed.get(),
            "batches_processed", batchesProcessed.get(),
            "ticks_observed", ticksObserved.get(),
            "flush_count", flushCount.get(),
            "ticks_flushed", ticksFlushed.get(),
            "buffer_size", bufferingComponent.getBufferSize(),
            "gaps_detected", gapsDetected.get()  // NEW
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

### DummyIndexer v4

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
    
    # Gap detection (Phase 2.5.4)
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

**GapDetectionComponentTest:**
```java
@ExtendWith(LogWatchExtension.class)
@Tag("unit")
class GapDetectionComponentTest {
    
    @Test
    void checkAndRecordGapBefore_gapExists() {
        // Mock coordinator.getMaxCompletedTickEnd() returns 1000
        // Mock samplingInterval = 10
        // Check batch starting at 1050 (expected 1010)
        // Verify gap [1000, 1050) detected
    }
    
    @Test
    void checkAndRecordGapBefore_noGap() {
        // Mock coordinator returns 1000
        // Check batch starting at 1010 (expected)
        // Verify no gap
    }
    
    @Test
    void checkAndRecordGapBefore_firstBatchWithGap() {
        // Mock coordinator returns -1 (no batches yet)
        // Check batch starting at 100 (not 0)
        // Verify gap [0, 100) detected
    }
    
    @Test
    void checkGapFilled_batchWithinGap() {
        // Create GapInfo [1000, 3000)
        // Check batch [1500, 2000)
        // Verify gap filled, splitGap called
    }
    
    @Test
    void checkGapFilled_batchOutsideGap() {
        // Create GapInfo [1000, 2000)
        // Check batch [3000, 4000)
        // Verify not filled
    }
}
```

### Integration Tests

**DummyIndexerV4IntegrationTest:**
```java
@ExtendWith(LogWatchExtension.class)
@Tag("integration")
class DummyIndexerV4IntegrationTest {
    
    @Test
    void testGapDetection_OutOfOrderBatches() throws Exception {
        // Create batches: batch_0000000000_0000000990, batch_0000002000_0000002990
        // (Missing batch_0000001000_0000001990)
        String runId = createTestRunWithGap();
        
        DummyIndexer indexer = createIndexer(runId);
        indexer.start();
        
        // Wait for batches processed
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("batches_processed").intValue() >= 2);
        
        indexer.stop();
        
        // Verify gap [1000, 2000) detected
        assertEquals(1, indexer.getMetrics().get("gaps_detected").intValue());
        assertEquals(1, queryPendingGapCount(runId));
    }
    
    @Test
    void testGapDetection_FirstBatchNotAtZero() throws Exception {
        // Create batch starting at tick 500 (samplingInterval=10)
        String runId = createTestRun(firstBatchTick = 500);
        
        DummyIndexer indexer = createIndexer(runId);
        indexer.start();
        
        // Wait for processing
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.STOPPED);
        
        // Verify gap [0, 500) detected
        assertEquals(1, indexer.getMetrics().get("gaps_detected").intValue());
    }
    
    @Test
    void testGapDetection_NoGaps() throws Exception {
        // Create sequential batches with no gaps
        String runId = createTestRun(sequential = true, numBatches = 10);
        
        DummyIndexer indexer = createIndexer(runId);
        indexer.start();
        
        // Wait for completion
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.STOPPED);
        
        // Verify 0 gaps detected
        assertEquals(0, indexer.getMetrics().get("gaps_detected").intValue());
    }
    
    @Test
    void testGapDetection_DatabasePersistence() throws Exception {
        // Create batches with gap
        String runId = createTestRunWithGap();
        
        DummyIndexer indexer = createIndexer(runId);
        indexer.start();
        
        // Wait for gap detection
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("gaps_detected").intValue() >= 1);
        
        indexer.stop();
        
        // Query coordinator_gaps table directly
        assertEquals(1, queryPendingGapCount(runId));
    }
}
```

## Logging Strategy

### Principles

**❌ NEVER:**
- INFO in loops
- Multi-line logs
- Phase prefixes
- Log operations metrics track

**✅ ALWAYS:**
- INFO very sparingly (completion summary only)
- DEBUG for gap detection/filling
- Single line per event

### Log Levels

**INFO:**
- Processing completed with gap summary

**DEBUG:**
- Gaps detected
- Batches filling gaps
- Gap operations

**WARN:**
- Not used in Phase 2.5.4 (passive observation only)

**ERROR:**
- Gap operation failures

### Examples

```java
// ✅ GOOD - INFO for completion
log.info("Processing completed: runId={}, batches={}, gaps={}", 
         runId, batchesProcessed, gapsDetected);

// ✅ GOOD - DEBUG for gap detection
log.debug("Gap detected: [{}, {})", gapStart, gapEnd);

// ✅ GOOD - DEBUG for gap filling
log.debug("Batch fills gap: batch[{}, {}) within gap[{}, {})",
         batchStart, batchEnd, gapStart, gapEnd);

// ❌ BAD - Redundant log
log.debug("Recorded gap in database: [{}, {})", gapStart, gapEnd);
```

## Monitoring Requirements

### DummyIndexer v4 Metrics

```java
metrics.put("runs_processed", runsProcessed.get());      // O(1)
metrics.put("batches_claimed", batchesClaimed.get());    // O(1)
metrics.put("batches_processed", batchesProcessed.get()); // O(1)
metrics.put("ticks_observed", ticksObserved.get());      // O(1)
metrics.put("flush_count", flushCount.get());            // O(1)
metrics.put("ticks_flushed", ticksFlushed.get());        // O(1)
metrics.put("buffer_size", bufferingComponent.getBufferSize()); // O(1)
metrics.put("gaps_detected", gapsDetected.get());        // O(1) - NEW
```

## JavaDoc Requirements

All public classes and methods require comprehensive JavaDoc as shown in code examples above.

## Implementation Checklist

- [ ] Create GapInfo record
- [ ] Extend AbstractDatabaseResource with gap operations
- [ ] Add coordinatorGapsInitialized cache to AbstractDatabaseResource
- [ ] Implement gap methods in H2Database (including atomic splitGap)
- [ ] Add gap methods to IBatchCoordinatorReady interface
- [ ] Update BatchCoordinatorWrapper to delegate gap operations
- [ ] Create GapDetectionComponent
- [ ] Extend DummyIndexer to v4
- [ ] Write unit tests for GapDetectionComponent
- [ ] Write integration tests for DummyIndexer v4
- [ ] Verify all tests pass
- [ ] Verify no connection leaks
- [ ] Verify JavaDoc complete

---

**Previous Phase:** [14_3_TICK_BUFFERING.md](./14_3_TICK_BUFFERING.md)  
**Next Phase:** [14_5_BATCH_PROCESSING_LOOP.md](./14_5_BATCH_PROCESSING_LOOP.md)


