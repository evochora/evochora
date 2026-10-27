# Phase 2.5.2: Batch Coordination Infrastructure

**Part of:** [14_BATCH_COORDINATION_AND_DUMMYINDEXER.md](./14_BATCH_COORDINATION_AND_DUMMYINDEXER.md)

**Status:** Ready for implementation

---

## Goal

Implement batch coordination infrastructure for competing consumer pattern across multiple indexer instances.

Enables multiple indexer instances (of same or different types) to claim and process batch files independently without duplicates, using pessimistic locking via database.

## Scope

**In Scope:**
1. `IBatchCoordinator` and `IBatchCoordinatorReady` database capability interfaces (fluent API)
2. `BatchAlreadyClaimedException` checked exception
3. `coordinator_batches` database table with composite primary key `(indexer_class, batch_filename)`
4. `BatchCoordinatorWrapper` implementation
5. `AbstractDatabaseResource` extensions for batch coordination
6. `H2Database` implementation of coordination methods
7. `BatchCoordinationComponent` for high-level coordination
8. DummyIndexer v2 - claims and marks batches (no processing yet)
9. Unit tests for all components
10. Integration tests for DummyIndexer v2

**Out of Scope:**
- Tick buffering (Phase 2.5.3)
- Gap detection (Phase 2.5.4)
- Batch processing loop (Phase 2.5.5)

## Success Criteria

1. Fluent API enforces `setIndexerClass()` before operational methods
2. Multiple DummyIndexers can run concurrently without claiming same batch
3. Different indexer types (e.g., DummyIndexer, EnvironmentIndexer) process same batches independently
4. `coordinator_batches` table tracks claimed/completed/failed batches per indexer class
5. BatchAlreadyClaimedException thrown on concurrent claim attempts
6. DummyIndexer v2 successfully claims batches and marks them completed
7. All tests pass with proper log validation
8. No connection leaks (verified in tests)
9. Metrics tracked with O(1) operations

## Prerequisites

- Phase 2.5.1: Metadata Reading (completed)
  - IMetadataReader capability
  - MetadataReadingComponent
  - DummyIndexer v1
- ISchemaAwareDatabase interface (completed)
- AbstractIndexer schema management (completed)

## Package Structure

```
org.evochora.datapipeline.api.resources.database/
  - IBatchCoordinator.java                # NEW - Batch coordination capability (fluent API entry)
  - IBatchCoordinatorReady.java           # NEW - Operational methods (after setIndexerClass)
  - BatchAlreadyClaimedException.java     # NEW - Exception for concurrent claims

org.evochora.datapipeline.resources.database/
  - AbstractDatabaseResource.java         # EXTEND - Add batch coordination methods
  - AbstractDatabaseWrapper.java          # EXISTING (from Phase 2.5.1) - Base for wrappers
  - H2Database.java                       # EXTEND - Implement batch coordination
  - BatchCoordinatorWrapper.java          # NEW - Extends AbstractDatabaseWrapper

org.evochora.datapipeline.services.indexers/
  - DummyIndexer.java                     # EXTEND - v2: Claim and complete batches
  └── components/
      - BatchCoordinationComponent.java   # NEW - High-level coordination wrapper
```

## Database Schema

### coordinator_batches Table

```sql
CREATE TABLE IF NOT EXISTS coordinator_batches (
    indexer_class VARCHAR(500) NOT NULL,      -- Fully qualified class name
    batch_filename VARCHAR(255) NOT NULL,     -- e.g., "batch_0000000000_0000009990.pb"
    tick_start BIGINT NOT NULL,               -- Parsed from filename
    tick_end BIGINT NOT NULL,                 -- Parsed from filename
    status VARCHAR(20) NOT NULL,              -- 'claimed', 'completed', 'failed'
    claimed_at TIMESTAMP NOT NULL,            -- When batch was claimed
    completed_at TIMESTAMP,                   -- When processing finished
    
    PRIMARY KEY (indexer_class, batch_filename),
    CONSTRAINT check_status CHECK (status IN ('claimed', 'completed', 'failed'))
);

CREATE INDEX IF NOT EXISTS idx_coordinator_batches_tick_end 
    ON coordinator_batches(indexer_class, tick_end);
```

**Indexer Class Strategy:**
- Each indexer type uses its fully qualified class name (e.g., `org.evochora.datapipeline.services.indexers.DummyIndexer`)
- Different indexer types process same batch files independently
- Prevents conflicts between DummyIndexer, EnvironmentIndexer, etc.

**Table Creation:**
- Lazy creation with cached check (per schema)
- `ConcurrentHashMap<String, Boolean> coordinatorBatchesInitialized` in AbstractDatabaseResource
- First `doTryClaim()` call creates table, subsequent calls skip
- Thread-safe for parallel indexer startup

## IBatchCoordinator Interface (Fluent API Entry)

```java
package org.evochora.datapipeline.api.resources.database;

/**
 * Database capability for batch coordination (fluent API entry point).
 * <p>
 * This interface enforces compile-time safety: indexers must call
 * {@link #setIndexerClass(String)} before accessing operational methods.
 * <p>
 * <strong>Usage Pattern:</strong>
 * <pre>{@code
 * IBatchCoordinator coordinator = getRequiredResource(IBatchCoordinator.class, "db-coordinator");
 * IBatchCoordinatorReady ready = coordinator.setIndexerClass(this.getClass().getName());
 * ready.tryClaim(batchFilename, tickStart, tickEnd);
 * }</pre>
 * <p>
 * Extends {@link ISchemaAwareDatabase} - AbstractIndexer automatically calls
 * {@code setSimulationRun()} after run discovery to set the schema.
 */
public interface IBatchCoordinator extends ISchemaAwareDatabase, IMonitorable, AutoCloseable {
    
    /**
     * Sets the indexer class for this coordinator instance.
     * <p>
     * Must be called exactly once before any operational methods.
     * Returns {@link IBatchCoordinatorReady} with operational methods.
     *
     * @param indexerClass Fully qualified class name (e.g., this.getClass().getName())
     * @return Ready coordinator with operational methods
     * @throws IllegalStateException if called more than once
     */
    IBatchCoordinatorReady setIndexerClass(String indexerClass);
    
    @Override
    void close();
}
```

## IBatchCoordinatorReady Interface (Operational Methods)

```java
package org.evochora.datapipeline.api.resources.database;

/**
 * Operational batch coordination methods (returned after setIndexerClass).
 * <p>
 * All methods automatically use the indexer class set via
 * {@link IBatchCoordinator#setIndexerClass(String)}.
 * <p>
 * Implements {@link AutoCloseable} to enable try-with-resources pattern for
 * automatic connection cleanup.
 * <p>
 * <strong>Thread Safety:</strong> Safe for single-threaded indexer use.
 * Not designed for concurrent access from multiple threads.
 */
public interface IBatchCoordinatorReady extends AutoCloseable {
    
    /**
     * Attempts to claim a batch for processing.
     * <p>
     * Atomically inserts batch with 'claimed' status. If batch already claimed
     * by another indexer instance (race condition), throws BatchAlreadyClaimedException.
     * <p>
     * Uses composite primary key (indexer_class, batch_filename) to allow different
     * indexer types to process same batches independently.
     *
     * @param batchFilename Batch file name (e.g., "batch_0000000000_0000009990.pb")
     * @param tickStart Starting tick (parsed from filename)
     * @param tickEnd Ending tick (parsed from filename)
     * @throws BatchAlreadyClaimedException if batch already claimed by another instance
     */
    void tryClaim(String batchFilename, long tickStart, long tickEnd) 
            throws BatchAlreadyClaimedException;
    
    /**
     * Marks a claimed batch as completed.
     * <p>
     * Updates status to 'completed' and sets completed_at timestamp.
     *
     * @param batchFilename Batch file name
     * @throws IllegalStateException if batch not claimed or already completed
     */
    void markCompleted(String batchFilename);
    
    /**
     * Marks a claimed batch as failed.
     * <p>
     * Updates status to 'failed' and sets completed_at timestamp.
     *
     * @param batchFilename Batch file name
     * @throws IllegalStateException if batch not claimed
     */
    void markFailed(String batchFilename);
    
    /**
     * Gets the maximum tick_end value among all completed batches.
     * <p>
     * Used for gap detection to identify missing tick ranges.
     *
     * @return Maximum tick_end, or -1 if no completed batches
     */
    long getMaxCompletedTickEnd();
    
    @Override
    void close();
}
```

## BatchAlreadyClaimedException

```java
package org.evochora.datapipeline.api.resources.database;

/**
 * Exception thrown when attempting to claim a batch that's already claimed.
 * <p>
 * This is a checked exception representing normal competing consumer behavior.
 * Indexers should catch this and simply move to the next batch.
 */
public class BatchAlreadyClaimedException extends Exception {
    private final String batchFilename;
    
    public BatchAlreadyClaimedException(String batchFilename) {
        super("Batch already claimed: " + batchFilename);
        this.batchFilename = batchFilename;
    }
    
    public String getBatchFilename() {
        return batchFilename;
    }
}
```

## AbstractDatabaseResource Extensions

Add new abstract methods grouped by capability interface:

```java
// In AbstractDatabaseResource.java

// Cached table initialization checks
private final ConcurrentHashMap<String, Boolean> coordinatorBatchesInitialized = new ConcurrentHashMap<>();

// ========================================================================
// IBatchCoordinator Capability - Batch Coordination
// ========================================================================

/**
 * Ensures coordinator_batches table exists for the current schema.
 * <p>
 * <strong>Capability:</strong> {@link IBatchCoordinator} (internal - table creation)
 * <p>
 * Called once per schema via cached check in doTryClaim.
 * Creates table with composite PRIMARY KEY (indexer_class, batch_filename).
 *
 * @param connection Database connection (with schema already set)
 * @throws Exception if table creation fails
 */
protected abstract void doEnsureCoordinatorBatchesTable(Object connection) throws Exception;

/**
 * Attempts to claim a batch atomically.
 * <p>
 * <strong>Capability:</strong> {@link IBatchCoordinatorReady#tryClaim(String, long, long)}
 * <p>
 * Performs lazy table creation with cached check, then atomic INSERT.
 * Uses composite PRIMARY KEY to prevent duplicate claims by different indexer instances.
 *
 * @param connection Database connection (with schema already set)
 * @param indexerClass Fully qualified class name (e.g., "org.evochora.datapipeline.services.indexers.DummyIndexer")
 * @param batchFilename Batch file name (e.g., "batch_0000000000_0000009990.pb")
 * @param tickStart Starting tick (parsed from filename)
 * @param tickEnd Ending tick (parsed from filename)
 * @throws BatchAlreadyClaimedException if batch already claimed by another indexer instance of same class
 * @throws Exception for other database errors
 */
protected abstract void doTryClaim(Object connection, String indexerClass, 
                                   String batchFilename, long tickStart, long tickEnd) 
        throws BatchAlreadyClaimedException, Exception;

/**
 * Marks a batch as completed.
 * <p>
 * <strong>Capability:</strong> {@link IBatchCoordinatorReady#markCompleted(String)}
 * <p>
 * Updates status='completed', records completion timestamp.
 *
 * @param connection Database connection (with schema already set)
 * @param indexerClass Fully qualified class name
 * @param batchFilename Batch file name
 * @throws Exception if update fails or batch not in 'claimed' state
 */
protected abstract void doMarkCompleted(Object connection, String indexerClass, 
                                       String batchFilename) throws Exception;

/**
 * Marks a batch as failed.
 * <p>
 * <strong>Capability:</strong> {@link IBatchCoordinatorReady#markFailed(String)}
 * <p>
 * Updates status='failed', records completion timestamp.
 *
 * @param connection Database connection (with schema already set)
 * @param indexerClass Fully qualified class name
 * @param batchFilename Batch file name
 * @throws Exception if update fails or batch not in 'claimed' state
 */
protected abstract void doMarkFailed(Object connection, String indexerClass, 
                                    String batchFilename) throws Exception;

/**
 * Gets the maximum tick_end among completed batches.
 * <p>
 * <strong>Capability:</strong> {@link IBatchCoordinatorReady#getMaxCompletedTickEnd()}
 * <p>
 * Used for gap detection to determine expected next tick.
 *
 * @param connection Database connection (with schema already set)
 * @param indexerClass Fully qualified class name
 * @return Maximum tick_end, or -1 if no completed batches
 * @throws Exception if query fails
 */
protected abstract long doGetMaxCompletedTickEnd(Object connection, String indexerClass) 
        throws Exception;
```

## H2Database Implementation

H2Database must implement all new abstract methods for the IBatchCoordinator capability:

```java
// In H2Database.java

// ========================================================================
// IBatchCoordinator Capability - Batch Coordination
// ========================================================================

/** Creates coordinator_batches table for batch coordination. */
@Override
protected void doEnsureCoordinatorBatchesTable(Object connection) throws Exception {
    Connection conn = (Connection) connection;
    Statement stmt = conn.createStatement();
    
    stmt.execute("""
        CREATE TABLE IF NOT EXISTS coordinator_batches (
            indexer_class VARCHAR(500) NOT NULL,
            batch_filename VARCHAR(255) NOT NULL,
            tick_start BIGINT NOT NULL,
            tick_end BIGINT NOT NULL,
            status VARCHAR(20) NOT NULL,
            claimed_at TIMESTAMP NOT NULL,
            completed_at TIMESTAMP,
            
            PRIMARY KEY (indexer_class, batch_filename),
            CONSTRAINT check_status CHECK (status IN ('claimed', 'completed', 'failed'))
        )
        """);
    
    stmt.execute("""
        CREATE INDEX IF NOT EXISTS idx_coordinator_batches_tick_end 
            ON coordinator_batches(indexer_class, tick_end)
        """);
    
    tablesCreated.incrementAndGet();
}

/** Implements {@link IBatchCoordinatorReady#tryClaim(String, long, long)}. Atomic INSERT with PRIMARY KEY check. */
@Override
protected void doTryClaim(Object connection, String indexerClass, 
                         String batchFilename, long tickStart, long tickEnd) 
        throws BatchAlreadyClaimedException, Exception {
    Connection conn = (Connection) connection;
    
    // Lazy table creation with cached check
    String currentSchema = conn.getSchema();
    coordinatorBatchesInitialized.computeIfAbsent(currentSchema, schema -> {
        try {
            doEnsureCoordinatorBatchesTable(connection);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create coordinator_batches table", e);
        }
    });
    
    try {
        PreparedStatement stmt = conn.prepareStatement("""
            INSERT INTO coordinator_batches 
                (indexer_class, batch_filename, tick_start, tick_end, status, claimed_at) 
            VALUES (?, ?, ?, ?, 'claimed', CURRENT_TIMESTAMP)
            """);
        
        stmt.setString(1, indexerClass);
        stmt.setString(2, batchFilename);
        stmt.setLong(3, tickStart);
        stmt.setLong(4, tickEnd);
        
        stmt.executeUpdate();
        queriesExecuted.incrementAndGet();
        
    } catch (SQLException e) {
        // Check for primary key violation (batch already claimed)
        if (e.getErrorCode() == 23505) { // H2 unique constraint violation
            throw new BatchAlreadyClaimedException(batchFilename);
        }
        throw e;
    }
}

/** Implements {@link IBatchCoordinatorReady#markCompleted(String)}. Updates status='completed'. */
@Override
protected void doMarkCompleted(Object connection, String indexerClass, 
                              String batchFilename) throws Exception {
    Connection conn = (Connection) connection;
    
    PreparedStatement stmt = conn.prepareStatement("""
        UPDATE coordinator_batches 
        SET status = 'completed', completed_at = CURRENT_TIMESTAMP 
        WHERE indexer_class = ? AND batch_filename = ? AND status = 'claimed'
        """);
    
    stmt.setString(1, indexerClass);
    stmt.setString(2, batchFilename);
    
    int updated = stmt.executeUpdate();
    queriesExecuted.incrementAndGet();
    
    if (updated == 0) {
        throw new IllegalStateException(
            "Cannot mark as completed - batch not claimed or already completed: " + batchFilename
        );
    }
}

/** Implements {@link IBatchCoordinatorReady#markFailed(String)}. Updates status='failed'. */
@Override
protected void doMarkFailed(Object connection, String indexerClass, 
                           String batchFilename) throws Exception {
    Connection conn = (Connection) connection;
    
    PreparedStatement stmt = conn.prepareStatement("""
        UPDATE coordinator_batches 
        SET status = 'failed', completed_at = CURRENT_TIMESTAMP 
        WHERE indexer_class = ? AND batch_filename = ? AND status = 'claimed'
        """);
    
    stmt.setString(1, indexerClass);
    stmt.setString(2, batchFilename);
    
    int updated = stmt.executeUpdate();
    queriesExecuted.incrementAndGet();
    
    if (updated == 0) {
        throw new IllegalStateException(
            "Cannot mark as failed - batch not claimed: " + batchFilename
        );
    }
}

/** Implements {@link IBatchCoordinatorReady#getMaxCompletedTickEnd()}. Returns MAX(tick_end) for gap detection. */
@Override
protected long doGetMaxCompletedTickEnd(Object connection, String indexerClass) throws Exception {
    Connection conn = (Connection) connection;
    
    PreparedStatement stmt = conn.prepareStatement("""
        SELECT MAX(tick_end) as max_tick 
        FROM coordinator_batches 
        WHERE indexer_class = ? AND status = 'completed'
        """);
    
    stmt.setString(1, indexerClass);
    ResultSet rs = stmt.executeQuery();
    
    queriesExecuted.incrementAndGet();
    
    if (rs.next()) {
        long maxTick = rs.getLong("max_tick");
        return rs.wasNull() ? -1 : maxTick;
    }
    
    return -1;
}
```

## BatchCoordinatorWrapper

```java
package org.evochora.datapipeline.resources.database;

import org.evochora.datapipeline.api.resources.*;
import org.evochora.datapipeline.api.resources.database.*;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowPercentiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Database-agnostic wrapper for batch coordination operations.
 * <p>
 * Extends {@link AbstractDatabaseWrapper} to inherit common functionality:
 * connection management, schema setting, error tracking, metrics infrastructure.
 * <p>
 * Implements fluent API pattern:
 * <ol>
 *   <li>Indexer calls {@link #setIndexerClass(String)} once</li>
 *   <li>Returns {@link IBatchCoordinatorReady} with operational methods</li>
 *   <li>All subsequent operations use stored indexerClass</li>
 * </ol>
 * <p>
 * <strong>Performance Contract:</strong> All metrics use O(1) recording.
 */
class BatchCoordinatorWrapper extends AbstractDatabaseWrapper 
                                     implements IBatchCoordinator, IBatchCoordinatorReady {
    private static final Logger log = LoggerFactory.getLogger(BatchCoordinatorWrapper.class);
    
    private String indexerClass; // Set via setIndexerClass()
    
    // Metrics (O(1) atomic operations)
    private final AtomicLong claimAttempts = new AtomicLong(0);
    private final AtomicLong claimSuccesses = new AtomicLong(0);
    private final AtomicLong claimConflicts = new AtomicLong(0);
    private final AtomicLong batchesCompleted = new AtomicLong(0);
    private final AtomicLong batchesFailed = new AtomicLong(0);
    private final AtomicLong coordinationErrors = new AtomicLong(0);
    
    // Latency tracking (O(1) recording)
    private final SlidingWindowPercentiles claimLatency;
    private final SlidingWindowPercentiles completeLatency;
    
    BatchCoordinatorWrapper(AbstractDatabaseResource db, ResourceContext context) {
        super(db, context);  // Parent handles connection, error tracking, metrics window
        
        this.claimLatency = new SlidingWindowPercentiles(metricsWindowSeconds);
        this.completeLatency = new SlidingWindowPercentiles(metricsWindowSeconds);
    }
    
    @Override
    public IBatchCoordinatorReady setIndexerClass(String indexerClass) {
        if (this.indexerClass != null) {
            throw new IllegalStateException("IndexerClass already set to: " + this.indexerClass);
        }
        this.indexerClass = indexerClass;
        log.debug("Batch coordinator configured for indexer: {}", indexerClass);
        return this;
    }
    
    @Override
    public void tryClaim(String batchFilename, long tickStart, long tickEnd) 
            throws BatchAlreadyClaimedException {
        ensureIndexerClassSet();
        
        long startNanos = System.nanoTime();
        claimAttempts.incrementAndGet();
        
        try {
            database.doTryClaim(ensureConnection(), indexerClass, batchFilename, tickStart, tickEnd);
            claimSuccesses.incrementAndGet();
            claimLatency.record(System.nanoTime() - startNanos);
            
        } catch (BatchAlreadyClaimedException e) {
            claimConflicts.incrementAndGet();
            claimLatency.record(System.nanoTime() - startNanos);
            throw e;
            
        } catch (Exception e) {
            coordinationErrors.incrementAndGet();
            recordError("CLAIM_FAILED", "Failed to claim batch",
                       "Batch: " + batchFilename + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to claim batch: " + batchFilename, e);
        }
    }
    
    @Override
    public void markCompleted(String batchFilename) {
        ensureIndexerClassSet();
        
        long startNanos = System.nanoTime();
        
        try {
            database.doMarkCompleted(ensureConnection(), indexerClass, batchFilename);
            batchesCompleted.incrementAndGet();
            completeLatency.record(System.nanoTime() - startNanos);
            
        } catch (Exception e) {
            coordinationErrors.incrementAndGet();
            recordError("MARK_COMPLETED_FAILED", "Failed to mark batch completed",
                       "Batch: " + batchFilename + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to mark completed: " + batchFilename, e);
        }
    }
    
    @Override
    public void markFailed(String batchFilename) {
        ensureIndexerClassSet();
        
        try {
            database.doMarkFailed(ensureConnection(), indexerClass, batchFilename);
            batchesFailed.incrementAndGet();
            
        } catch (Exception e) {
            coordinationErrors.incrementAndGet();
            recordError("MARK_FAILED_FAILED", "Failed to mark batch failed",
                       "Batch: " + batchFilename + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to mark failed: " + batchFilename, e);
        }
    }
    
    @Override
    public long getMaxCompletedTickEnd() {
        ensureIndexerClassSet();
        
        try {
            return database.doGetMaxCompletedTickEnd(ensureConnection(), indexerClass);
            
        } catch (Exception e) {
            coordinationErrors.incrementAndGet();
            recordError("GET_MAX_TICK_FAILED", "Failed to get max completed tick",
                       "Error: " + e.getMessage());
            return -1;
        }
    }
    
    // Note: close(), isHealthy(), getErrors(), clearErrors(), getResourceName(), 
    // getUsageState(), setSimulationRun(), releaseConnection() inherited from AbstractDatabaseWrapper
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        // Counters - O(1)
        metrics.put("claim_attempts", claimAttempts.get());
        metrics.put("claim_successes", claimSuccesses.get());
        metrics.put("claim_conflicts", claimConflicts.get());
        metrics.put("batches_completed", batchesCompleted.get());
        metrics.put("batches_failed", batchesFailed.get());
        metrics.put("coordination_errors", coordinationErrors.get());
        
        // Latency percentiles in milliseconds - O(windowSeconds × buckets) = O(constant)
        metrics.put("claim_latency_p50_ms", claimLatency.getPercentile(50) / 1_000_000.0);
        metrics.put("claim_latency_p95_ms", claimLatency.getPercentile(95) / 1_000_000.0);
        metrics.put("claim_latency_p99_ms", claimLatency.getPercentile(99) / 1_000_000.0);
        metrics.put("complete_latency_p95_ms", completeLatency.getPercentile(95) / 1_000_000.0);
        
        // Note: connection_cached metric inherited from AbstractDatabaseWrapper
    }
    
    private void ensureIndexerClassSet() {
        if (indexerClass == null) {
            throw new IllegalStateException(
                "IndexerClass not set - call setIndexerClass() first"
            );
        }
    }
}
```

## BatchCoordinationComponent

```java
package org.evochora.datapipeline.services.indexers.components;

import org.evochora.datapipeline.api.resources.database.BatchAlreadyClaimedException;
import org.evochora.datapipeline.api.resources.database.IBatchCoordinatorReady;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Component wrapping batch coordination operations.
 * <p>
 * Provides high-level coordination methods for indexers. Wraps
 * {@link IBatchCoordinatorReady} for cleaner indexer code.
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Should be used by single indexer instance only.
 */
public class BatchCoordinationComponent {
    private static final Logger log = LoggerFactory.getLogger(BatchCoordinationComponent.class);
    
    private final IBatchCoordinatorReady coordinator;
    
    /**
     * Creates batch coordination component.
     *
     * @param coordinator Ready coordinator (after setIndexerClass)
     */
    public BatchCoordinationComponent(IBatchCoordinatorReady coordinator) {
        this.coordinator = coordinator;
    }
    
    /**
     * Attempts to claim a batch for processing.
     *
     * @param batchFilename Batch file name
     * @param tickStart Starting tick
     * @param tickEnd Ending tick
     * @return true if claimed successfully, false if already claimed
     */
    public boolean tryClaim(String batchFilename, long tickStart, long tickEnd) {
        try {
            coordinator.tryClaim(batchFilename, tickStart, tickEnd);
            return true;
            
        } catch (BatchAlreadyClaimedException e) {
            return false; // No log - normal competing consumer behavior
        }
    }
    
    /**
     * Marks a claimed batch as completed.
     *
     * @param batchFilename Batch file name
     */
    public void markCompleted(String batchFilename) {
        coordinator.markCompleted(batchFilename);
        // No log - metrics track this
    }
    
    /**
     * Marks a claimed batch as failed.
     *
     * @param batchFilename Batch file name
     */
    public void markFailed(String batchFilename) {
        coordinator.markFailed(batchFilename);
        log.warn("Batch failed: {}", batchFilename);
    }
    
    /**
     * Gets the maximum tick_end among completed batches.
     *
     * @return Maximum tick_end, or -1 if no completed batches
     */
    public long getMaxCompletedTickEnd() {
        return coordinator.getMaxCompletedTickEnd();
    }
    
}
```

**Note:** Connection cleanup is handled automatically via try-with-resources in DummyIndexer (see indexRun() implementation below).

## DummyIndexer v2 Implementation

```java
package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.database.IBatchCoordinator;
import org.evochora.datapipeline.api.resources.database.IBatchCoordinatorReady;
import org.evochora.datapipeline.api.resources.database.IMetadataReader;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.services.indexers.components.BatchCoordinationComponent;
import org.evochora.datapipeline.services.indexers.components.MetadataReadingComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test indexer for validating batch coordination infrastructure.
 * <p>
 * <strong>Phase 2.5.1 Scope:</strong> Metadata reading
 * <p>
 * <strong>Phase 2.5.2 Scope:</strong> Batch claiming and completion (no tick processing yet)
 * <ul>
 *   <li>Discovers and claims batches from storage</li>
 *   <li>Reads batch files (counts ticks only, no actual processing)</li>
 *   <li>Marks batches as completed</li>
 *   <li>Supports competing consumer pattern (multiple instances)</li>
 * </ul>
 */
public class DummyIndexer extends AbstractIndexer implements IMonitorable {
    private static final Logger log = LoggerFactory.getLogger(DummyIndexer.class);
    
    // Resources (for try-with-resources cleanup)
    private final IMetadataReader metadataReader;
    private final IBatchCoordinatorReady coordinator;
    
    // Components
    private final MetadataReadingComponent metadataComponent;
    private final BatchCoordinationComponent coordinationComponent;
    private final IBatchStorageRead storage;
    
    // Metrics
    private final AtomicLong runsProcessed = new AtomicLong(0);
    private final AtomicLong batchesClaimed = new AtomicLong(0);
    private final AtomicLong batchesProcessed = new AtomicLong(0);
    private final AtomicLong ticksObserved = new AtomicLong(0);
    
    public DummyIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        
        // Setup metadata reader (Phase 2.5.1)
        this.metadataReader = getRequiredResource("metadata", IMetadataReader.class);
        int pollIntervalMs = options.hasPath("pollIntervalMs") ? options.getInt("pollIntervalMs") : 1000;
        int maxPollDurationMs = options.hasPath("maxPollDurationMs") ? options.getInt("maxPollDurationMs") : 300000;
        
        this.metadataComponent = new MetadataReadingComponent(metadataReader, pollIntervalMs, maxPollDurationMs);
        
        // Setup batch coordinator (Phase 2.5.2)
        IBatchCoordinator coord = getRequiredResource("coordinator", IBatchCoordinator.class);
        this.coordinator = coord.setIndexerClass(this.getClass().getName());
        this.coordinationComponent = new BatchCoordinationComponent(coordinator);
        
        // Storage for batch discovery
        this.storage = getRequiredResource("storage", IBatchStorageRead.class);
        
        log.info("DummyIndexer v2 initialized (metadata + batch coordination)");
    }
    
    @Override
    protected void indexRun(String runId) throws Exception {
        // Use try-with-resources for automatic connection cleanup
        try (IMetadataReader reader = metadataReader; IBatchCoordinatorReady coord = coordinator) {
            // Load metadata (polls until available)
            metadataComponent.loadMetadata(runId);
            
            log.debug("Metadata available: runId={}, samplingInterval={}", 
                     runId, metadataComponent.getSamplingInterval());
            
            runsProcessed.incrementAndGet();
            
            // Process batches
            processBatches(runId);
            
            log.info("Batch processing completed: runId={}, batchesClaimed={}, batchesProcessed={}", 
                     runId, batchesClaimed.get(), batchesProcessed.get());
        }  // AutoCloseable.close() releases all connections
    }
    
    private void processBatches(String runId) throws Exception {
        String batchPath = "tickdata/" + runId + "/";
        String continuationToken = null;
        int batchesInPage = 0;
        
        do {
            // List next page of batches (max 100 per page)
            var page = storage.listBatchFiles(batchPath, continuationToken, 100);
            batchesInPage = page.batchFiles().size();
            
            log.debug("Discovered {} batches in page", batchesInPage);
            
            for (var batchFile : page.batchFiles()) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                
                // Parse tick range from filename
                long tickStart = batchFile.tickStart();
                long tickEnd = batchFile.tickEnd();
                String filename = batchFile.filename();
                
                // Try to claim batch
                if (coordinationComponent.tryClaim(filename, tickStart, tickEnd)) {
                    batchesClaimed.incrementAndGet();
                    
                    log.debug("Claimed batch: {} (ticks {}-{})", filename, tickStart, tickEnd);
                    
                    // "Process" batch (just count ticks for now)
                    processOneBatch(batchPath + filename, filename);
                    
                    // Mark completed
                    coordinationComponent.markCompleted(filename);
                    batchesProcessed.incrementAndGet();
                } else {
                    log.debug("Batch already claimed, skipping: {}", filename);
                }
            }
            
            continuationToken = page.continuationToken();
            
        } while (continuationToken != null && batchesInPage > 0);
    }
    
    private void processOneBatch(String fullPath, String filename) throws Exception {
        // Just count ticks (no actual processing)
        byte[] data = storage.readBatchFile(fullPath);
        
        // Parse protobuf to count ticks
        org.evochora.datapipeline.api.contracts.TickDataBatch batch =
            org.evochora.datapipeline.api.contracts.TickDataBatch.parseFrom(data);
        
        int tickCount = batch.getTicksCount();
        ticksObserved.addAndGet(tickCount);
        
        log.debug("Batch contains {} ticks: {}", tickCount, filename);
    }
    
    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
            "runs_processed", runsProcessed.get(),
            "batches_claimed", batchesClaimed.get(),
            "batches_processed", batchesProcessed.get(),
            "ticks_observed", ticksObserved.get()
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

### DummyIndexer v2

```hocon
dummy-indexer {
  className = "org.evochora.datapipeline.services.indexers.DummyIndexer"
  
  resources {
    storage = "storage-read:tick-storage"
    metadata = "db-meta-read:index-database"
    coordinator = "db-coordinator:index-database"  # NEW in Phase 2.5.2
  }
  
  options {
    # Inherits from central services.runId (if set)
    # If services.runId not set → automatic discovery from storage
    # Can be overridden here for indexer-specific post-mortem mode
    runId = ${?pipeline.services.runId}
    
    # Metadata polling
    pollIntervalMs = 1000
    maxPollDurationMs = 300000
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
  - Example: `@ExpectLog(logger = "...", level = WARN, message = "Batch failed: *")`
  - **NEVER** use broad patterns like `message = "*"`
- **Never use `Thread.sleep`** - use Awaitility `await().atMost(...).until(...)` instead
- Leave no artifacts (in-memory H2, temp directories cleaned in `@AfterEach`)
- Use UUID-based database names for parallel test execution
- Verify no connection leaks in `@AfterEach`

### Unit Tests

**BatchCoordinatorWrapperTest:**
```java
@ExtendWith(LogWatchExtension.class)
@Tag("unit")
class BatchCoordinatorWrapperTest {
    
    @Test
    void setIndexerClass_success() {
        // Create wrapper
        // Call setIndexerClass()
        // Verify returns IBatchCoordinatorReady
    }
    
    @Test
    void setIndexerClass_twiceFails() {
        // Call setIndexerClass() twice
        // Verify IllegalStateException
    }
    
    @Test
    void tryClaim_success() {
        // Mock doTryClaim to succeed
        // Verify claimSuccesses incremented
        // Verify metrics updated
    }
    
    @Test
    void tryClaim_alreadyClaimed() {
        // Mock doTryClaim to throw BatchAlreadyClaimedException
        // Verify exception propagated
        // Verify claimConflicts incremented
    }
    
    @Test
    void tryClaim_beforeSetIndexerClass_fails() {
        // Call tryClaim before setIndexerClass()
        // Verify IllegalStateException
    }
    
    @Test
    void markCompleted_success() {
        // Mock doMarkCompleted to succeed
        // Verify batchesCompleted incremented
    }
    
    @Test
    void metrics_allO1Operations() {
        // Verify all metric operations are O(1)
    }
}
```

**BatchCoordinationComponentTest:**
```java
@ExtendWith(LogWatchExtension.class)
@Tag("unit")
class BatchCoordinationComponentTest {
    
    @Test
    void tryClaim_alreadyClaimed_returnsFalse() {
        // Mock coordinator.tryClaim() to throw exception
        // Verify returns false
        // No @AllowLog needed - no logs in this method
    }
    
    @Test
    @ExpectLog(logger = "org.evochora.datapipeline.services.indexers.components.BatchCoordinationComponent",
               level = WARN, message = "Batch failed: *")
    void markFailed_success() {
        // Call markFailed
        // Verify coordinator.markFailed called
        // Verify WARN log appears
    }
}
```

### Integration Tests

**DummyIndexerV2IntegrationTest:**
```java
@ExtendWith(LogWatchExtension.class)
@Tag("integration")
class DummyIndexerV2IntegrationTest {
    
    @Test
    void testBatchCoordination_SingleIndexer() throws Exception {
        // Create test run with metadata and 10 batch files
        String runId = createTestRun(numBatches = 10);
        
        DummyIndexer indexer = createIndexer(runId);
        indexer.start();
        
        // Wait for all batches processed (NO Thread.sleep!)
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("batches_processed").intValue() == 10);
        
        indexer.stop();
        
        // Verify coordinator_batches table has 10 'completed' entries
        assertEquals(10, queryCompletedBatchCount(runId));
    }
    
    @Test
    void testBatchCoordination_CompetingConsumers() throws Exception {
        // Create test run with 50 batch files
        String runId = createTestRun(numBatches = 50);
        
        // Start 3 DummyIndexer instances simultaneously
        List<DummyIndexer> indexers = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            DummyIndexer indexer = createIndexer(runId, "indexer-" + i);
            indexer.start();
            indexers.add(indexer);
        }
        
        // Wait for all batches processed
        await().atMost(30, TimeUnit.SECONDS)
            .until(() -> {
                long total = indexers.stream()
                    .mapToLong(idx -> idx.getMetrics().get("batches_processed").longValue())
                    .sum();
                return total == 50;
            });
        
        // Stop all
        indexers.forEach(DummyIndexer::stop);
        
        // Verify all 50 batches processed exactly once
        assertEquals(50, queryCompletedBatchCount(runId));
        
        // Verify workload distributed
        for (DummyIndexer indexer : indexers) {
            long processed = indexer.getMetrics().get("batches_processed").longValue();
            assertTrue(processed > 0, "Indexer processed no batches");
            assertTrue(processed < 50, "Indexer processed all batches");
        }
    }
    
    @Test
    void testBatchCoordination_DifferentIndexerTypes() throws Exception {
        // Create test run with 20 batch files
        // Start DummyIndexer instance
        // Start second indexer with different class name
        // Wait for all to complete
        await().atMost(30, TimeUnit.SECONDS)
            .until(() -> /* both indexers finished */);
        
        // Verify both processed all 20 batches
        // Verify coordinator_batches has 40 rows (20 per indexer class)
    }
    
    @Test
    void testMaxCompletedTickEnd() throws Exception {
        // Process batches, verify getMaxCompletedTickEnd returns correct value
    }
}
```

## Logging Strategy

### Principles

**❌ NEVER:**
- INFO in loops (use DEBUG)
- Multi-line logs
- Phase/version prefixes
- Log successful operations that metrics track

**✅ ALWAYS:**
- INFO very sparingly (completion summary only)
- DEBUG for loop operations
- Single line per event

### Log Levels

**INFO:**
- Processing completed with summary (once per run)

**DEBUG:**
- Batches discovered, claimed, skipped
- Tick counts

**WARN:**
- Batch failures

**ERROR:**
- Coordination failures

### Examples

```java
// ✅ GOOD - INFO only for summary
log.info("Batch processing completed: runId={}, batchesClaimed={}, batchesProcessed={}", 
         runId, batchesClaimed, batchesProcessed);

// ✅ GOOD - DEBUG in loop
log.debug("Claimed batch: {} (ticks {}-{})", filename, tickStart, tickEnd);

// ✅ GOOD - No log for normal behavior
if (!tryClaim(...)) {
    return false; // Metrics track this
}

// ❌ BAD - INFO in loop
log.info("Claimed batch: {}", filename);

// ❌ BAD - Unnecessary log
log.debug("Batch already claimed, skipping: {}", filename);
```

## Monitoring Requirements

### BatchCoordinatorWrapper Metrics

```java
// Counters - O(1)
metrics.put("claim_attempts", claimAttempts.get());
metrics.put("claim_successes", claimSuccesses.get());
metrics.put("claim_conflicts", claimConflicts.get());
metrics.put("batches_completed", batchesCompleted.get());
metrics.put("batches_failed", batchesFailed.get());
metrics.put("coordination_errors", coordinationErrors.get());

// Latency - O(constant)
metrics.put("claim_latency_p50_ms", claimLatency.getPercentile(50) / 1_000_000.0);
metrics.put("claim_latency_p95_ms", claimLatency.getPercentile(95) / 1_000_000.0);
metrics.put("claim_latency_p99_ms", claimLatency.getPercentile(99) / 1_000_000.0);
```

### DummyIndexer v2 Metrics

```java
metrics.put("runs_processed", runsProcessed.get());      // O(1)
metrics.put("batches_claimed", batchesClaimed.get());    // O(1)
metrics.put("batches_processed", batchesProcessed.get()); // O(1)
metrics.put("ticks_observed", ticksObserved.get());      // O(1)
```

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
  - Example: `/** Implements {@link IBatchCoordinatorReady#tryClaim(String, long, long)}. */`

Examples shown in code sections above demonstrate proper JavaDoc structure.

## Implementation Checklist

- [ ] Create IBatchCoordinator interface (fluent API entry)
- [ ] Create IBatchCoordinatorReady interface (operational methods)
- [ ] Create BatchAlreadyClaimedException
- [ ] Extend AbstractDatabaseResource with batch coordination methods
- [ ] Add coordinatorBatchesInitialized cache to AbstractDatabaseResource
- [ ] Implement methods in H2Database
- [ ] Update AbstractDatabaseResource.getWrappedResource() for "db-coordinator"
- [ ] Create BatchCoordinatorWrapper (extends AbstractDatabaseWrapper from Phase 2.5.1)
- [ ] Create BatchCoordinationComponent
- [ ] Extend DummyIndexer to v2
- [ ] Write unit tests for BatchCoordinatorWrapper
- [ ] Write unit tests for BatchCoordinationComponent
- [ ] Write integration tests for DummyIndexer v2 (single, competing, different types)
- [ ] Verify all tests pass
- [ ] Verify no connection leaks
- [ ] Verify JavaDoc complete

---

**Previous Phase:** [14_1_METADATA_READING.md](./14_1_METADATA_READING.md)  
**Next Phase:** [14_3_TICK_BUFFERING.md](./14_3_TICK_BUFFERING.md)


