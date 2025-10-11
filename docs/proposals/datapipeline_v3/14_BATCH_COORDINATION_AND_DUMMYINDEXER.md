# Data Pipeline V3 - Batch Coordination and DummyIndexer (Phase 2.5)

## Goal

Implement centralized batch coordination infrastructure that enables multiple indexer instances to cooperatively process batch files using the competing consumer pattern. This phase establishes the foundation for all future indexers that process tick data batches with proper gap detection and handling.

## Scope

**This phase implements:**
1. IBatchCoordinator database capability interface for batch coordination
2. IMetadataReader database capability interface for metadata queries
3. BatchCoordinatorWrapper with dedicated connection
4. MetadataReaderWrapper with dedicated connection
5. H2Database extensions for coordination and metadata reading
6. AbstractIndexer extensions with batch buffering, gap detection, and coordination
7. DummyIndexer service that exercises the coordination infrastructure without writing data
8. Integration tests verifying competing consumer patterns and gap handling

**This phase does NOT implement:**
9. IEnvironmentDatabase capability (deferred to future phase)
10. EnvironmentIndexer service (deferred to future phase)
11. Actual environment/cell data processing (deferred to future phase)

## Success Criteria

Upon completion:
1. IBatchCoordinator capability interface defined in API with claim/complete/fail/gap operations
2. IMetadataReader capability interface defined in API with non-blocking metadata queries
3. H2Database implements coordination via batch_processing table with atomic claims
4. AbstractIndexer implements complete batch coordination loop with gap detection
5. Gap detection works correctly with variable batch sizes and samplingInterval
6. Temporary gaps marked as 'gap_pending' and released when filled
7. Permanent gaps (timeout exceeded) logged with warning and metrics tracked
8. Tick buffering with configurable insertBatchSize and flushTimeout
9. DummyIndexer successfully processes batches in competing consumer setup
10. Multiple DummyIndexer instances coordinate correctly without duplicate processing
11. All tests pass with proper cleanup and no artifacts

## Prerequisites

- Phase 0: API Foundation (completed) - IResource, IMonitorable interfaces
- Phase 1.2: Core Resource Implementation (completed) - Resource abstraction patterns
- Phase 2.2: Storage Resource (completed) - IBatchStorageRead interface with listBatchFiles + range queries
- Phase 2.3: Persistence Service (completed) - Writes batch files with variable sizes
- Phase 2.4: Database Resource and Metadata Indexer (completed) - Metadata in database, IMetadataWriter
- SimulationMetadata includes `sampling_interval` field (completed) - Required for gap detection
- ISchemaAwareDatabase interface (completed) - Base for all schema-aware database capabilities
- AbstractIndexer schema management (completed) - prepareSchema(), setSchemaForAllDatabaseResources()

## Architectural Context

### Data Flow

```
SimulationEngine (samplingInterval=10) → Queue → PersistenceService
                                                        ↓
                                                   Storage (batch files)
                                                        ↓
                        ┌───────────────────────────────┴───────────────────────────────┐
                        ↓                                                                ↓
                 DummyIndexer-1                                                   DummyIndexer-2
                 (competing consumer)                                          (competing consumer)
                        ↓                                                                ↓
                 BatchCoordinator (Database) ← coordination → BatchCoordinator (Database)
                        ↓
                 Claims batch, reads ticks, no writes
```

### Schema Management

**AbstractIndexer** provides centralized schema management via template method pattern:

1. **`discoverRunId()`** orchestrates schema preparation:
   - Discovers the runId (configured or timestamp-based)
   - Calls `prepareSchema(runId)` (template method hook)
   - Calls `setSchemaForAllDatabaseResources(runId)`

2. **`prepareSchema(runId)`** - Template method hook (default no-op):
   - **MetadataIndexer** overrides this to create the schema (`database.createSimulationRun(runId)`)
   - **DummyIndexer** and future indexers don't override (schema already exists)

3. **`setSchemaForAllDatabaseResources(runId)`** - Sets schema for all `ISchemaAwareDatabase` resources:
   - Discovers all `ISchemaAwareDatabase` resources (coordinators, metadata readers, etc.)
   - Calls `setSimulationRun(runId)` on each
   - Called automatically after `prepareSchema()`

**This ensures:**
- Schema is created exactly once (by MetadataIndexer)
- All indexers' database resources operate in the correct schema
- Race-safe and idempotent
- No redundant schema creation calls

### Competing Consumer Pattern

```
Storage has batch files:
  batch_0000000000_0000000999.pb
  batch_0000001000_0000001999.pb
  batch_0000002000_0000002999.pb

DummyIndexer-1:
  1. Lists available batches from storage
  2. Tries to claim batch_0000000000... via coordinator.tryClaim()
  3. SUCCESS → processes batch
  4. coordinator.markCompleted()

DummyIndexer-2:
  1. Lists available batches from storage  
  2. Tries to claim batch_0000000000... via coordinator.tryClaim()
  3. FAILURE (already claimed) → tries next batch
  4. Tries to claim batch_0000001000... via coordinator.tryClaim()
  5. SUCCESS → processes batch
```

### Gap Detection with samplingInterval

**Scenario: samplingInterval=10, variable batch sizes**

```
Storage batches:
  batch_0000000000_0000000990.pb  (100 ticks: 0, 10, 20, ..., 990)
  batch_0000001000_0000001420.pb  (43 ticks: 1000, 1010, ..., 1420) ← timeout-triggered
  batch_0000001430_0000002580.pb  (116 ticks: 1430, 1440, ..., 2580)

Gap detection:
  hasGapBefore(1000, samplingInterval=10)?
    → previousBatch.tick_end = 990
    → expected = 990 + 10 = 1000
    → batchStartTick = 1000
    → 1000 == 1000? YES → NO GAP ✓

  hasGapBefore(1430, samplingInterval=10)?
    → previousBatch.tick_end = 1420
    → expected = 1420 + 10 = 1430
    → batchStartTick = 1430
    → 1430 == 1430? YES → NO GAP ✓

  hasGapBefore(2000, samplingInterval=10)?
    → previousBatch.tick_end = 1420 (batch at 1430-2580 not processed yet!)
    → expected = 1420 + 10 = 1430
    → batchStartTick = 2000
    → 2000 == 1430? NO → GAP! ✓
    → Mark batch_2000-... as 'gap_pending'
```

### Out-of-Order Batch Arrival

```
t=0: PersistenceService-2 writing ticks 1000-1990 (fast!)
     PersistenceService-1 writing ticks 0-990 (slow)
     
t=1s: Storage has: batch_0000001000_0000001990.pb (samplingInterval=10)

Indexer - Iteration 1:
  PHASE 1 (Gap Filling): getOldestPendingGap() → null
  PHASE 2 (New Batches):
    → listBatchFiles() → ["batch_1000_1990"]
    → detectAndRecordGap(1000):
      → maxCompletedTickEnd() = -1
      → First batch, no gap
    → processOneBatch(batch_1000_1990)
      → tryClaim, read, buffer, markCompleted
      → maxCompleted now = 1990
    → continuationToken = "batch_1000_1990" (processed)

t=5s: Storage adds: batch_0000000000_0000000990.pb
                     batch_0000002000_0000002990.pb

Indexer - Iteration 2:
  PHASE 1 (Gap Filling): getOldestPendingGap() → null
  PHASE 2 (New Batches):
    → listBatchFiles(token="batch_1000_1990") → ["batch_2000_2990"]
    → detectAndRecordGap(2000):
      → maxCompletedTickEnd() = 1990
      → expectedNext = 1990 + 10 = 2000
      → Gap? 2000 == 2000 → NO
    → processOneBatch(batch_2000_2990)
      → tryClaim, read, buffer, markCompleted
      → maxCompleted now = 2990

Indexer - Iteration 3:
  PHASE 1 (Gap Filling): getOldestPendingGap() → null
  PHASE 2 (New Batches):
    → listBatchFiles(token="batch_2000_2990") → ["batch_3000_3990"]
    → detectAndRecordGap(3000):
      → maxCompletedTickEnd() = 2990
      → expectedNext = 2990 + 10 = 3000
      → Gap? 3000 == 3000 → NO
    → processOneBatch...

Indexer - Iteration 3 (batch_3000... later):
  PHASE 1 (Gap Filling):
    → getOldestPendingGap() → Gap[0, 990] (was recorded in iteration 1!)
    → tryFillOldestGap():
      → listBatchFiles(runId, null, 1, startTick=0, endTick=990)
      → Storage returns: ["batch_0_990"]
      → Found batch in gap!
      → Return "batch_0_990"
    → gap = Gap[0, 990] (stored before call)
    → processOneBatch(batch_0_990)
      → tryClaim, read, buffer, markCompleted
      → maxCompleted now = 2990 (unchanged, not necessarily sequential!)
    → splitGapAfterBatch(gap[0, 990], batchStart=0, batchEnd=990)
      → Gap fully filled! No sub-gaps created.
      → DELETE gap[0, 990]
    → Return true (processed gap batch)

Next iteration:
  → PHASE 1: getOldestPendingGap() → null (gap filled!)
  → PHASE 2: Continue with batch_3000... 
```

**Result:** Gap [0, 990] was detected when first batch had tick_start=1000 > 0, then filled when batch_0-990 appeared!

### Permanent Gap Detection

```
t=0s:  Storage has: batch_2000-2990, batch_3000-3990
       Indexer processes batch_2000-2990 (first batch!)
       → detectAndRecordGap(2000):
         → maxCompleted = -1, batchStart = 2000 > 0
         → recordGap([0, 1990])  ← Gap before first batch!
       → coordinator_gaps: [(0, 1990, t=0s, 'pending')]

t=5s:  Indexer Loop - PHASE 1 (Gap Filling):
       → tryFillOldestGap():
         → Get gap [0, 1990]
         → gapAge = 5s < 60s (not permanent yet)
         → listBatchFiles(runId, null, 1, startTick=0, endTick=1990)
         → Storage returns: [] (batch_0-990 still not there)
         → Return null
       → PHASE 2: Process next batch (batch_3000...)

t=60s: Indexer Loop - PHASE 1:
       → tryFillOldestGap():
         → Get gap [0, 1990]
         → gapAge = 60s >= 60s (timeout!)
         → markGapPermanent(0)
           → UPDATE coordinator_gaps SET status='permanent' WHERE gap_start=0
         → log.warn("Permanent gap: [0, 1990] missing for 60s, marked as permanent")
         → permanentGapsDetected++
         → Return null (no processing)

t=65s: Indexer Loop - PHASE 1:
       → getOldestPendingGap():
         → SQL: WHERE status='pending'
         → Gap [0, 1990] has status='permanent' → skipped!
         → Return null
       → PHASE 2: Continue with new batches
```

**Result:** Gap [0, 1990] marked as permanent in DB, warning logged once, never searched again, pipeline continues.

## Package Structure

```
org.evochora.datapipeline.api.resources.database/
  - ISchemaAwareDatabase.java          # EXISTING - Base interface for schema-aware operations
  - IMetadataWriter.java               # EXISTING - Write metadata (extends ISchemaAwareDatabase)
  - IMetadataReader.java               # NEW - Read metadata (extends ISchemaAwareDatabase)
  - IBatchCoordinator.java             # NEW - Batch coordination init (fluent API)
  - IBatchCoordinatorReady.java        # NEW - Batch coordination ready (fluent API)
  - MetadataNotFoundException.java     # NEW - Exception for metadata not found
  - BatchAlreadyClaimedException.java  # NEW - Exception for duplicate claims
  - GapInfo.java                       # NEW - Value object for gap information

org.evochora.datapipeline.resources.database/
  - AbstractDatabaseResource.java      # EXISTING - Extended with do* methods for coordination + metadata reading
  - AbstractDatabaseWrapper.java       # NEW - Base class for all DB wrappers (DRY for common functionality)
  - H2Database.java                    # EXISTING - Extended with coordination + metadata + gap methods
  - MetadataWriterWrapper.java         # EXISTING - IMetadataWriter wrapper
  - MetadataReaderWrapper.java         # NEW - Extends AbstractDatabaseWrapper
  - BatchCoordinatorWrapper.java       # NEW - Extends AbstractDatabaseWrapper (fluent API)

org.evochora.datapipeline.services.indexers/
  - AbstractIndexer.java               # EXISTING - Storage + run discovery + schema management (prepareSchema)
  - AbstractBatchProcessingIndexer.java # NEW - Batch processing loop with component support
  - MetadataIndexer.java               # EXISTING - Metadata indexing (no changes)
  - DummyIndexer.java                  # NEW - Test indexer with all components
  └── components/
      - MetadataReadingComponent.java  # NEW - Metadata polling and caching
      - BatchCoordinationComponent.java # NEW - Coordination wrapper
      - GapDetectionComponent.java     # NEW - Gap detection and handling
      - TickBufferingComponent.java    # NEW - Tick buffering logic
```

## Database Capability Interfaces

### IBatchCoordinator (NEW)

```java
/**
 * Database capability for coordinating batch processing across multiple indexer instances.
 * <p>
 * <strong>Fluent API for Compile-Time Safety:</strong>
 * This interface only provides setIndexerClass(), which returns IBatchCoordinatorReady.
 * The compiler enforces that indexer class is set before any coordination methods can be called.
 * <p>
 * <strong>Usage Pattern:</strong>
 * <pre>
 * IBatchCoordinatorReady coordinator = getRequiredResource(IBatchCoordinator.class, "db-coordinator")
 *     .setIndexerClass(this.getClass().getName());
 * coordinator.tryClaim(...);  // Now usable
 * </pre>
 */
public interface IBatchCoordinator extends ISchemaAwareDatabase, AutoCloseable {
    
    /**
     * Sets the indexer class for this coordinator instance.
     * MUST be called before any coordination operations.
     * <p>
     * Returns the ready-to-use coordinator with all coordination methods available.
     *
     * @param indexerClass Fully qualified class name (e.g., "org.evochora.datapipeline.services.indexers.DummyIndexer")
     * @return Ready coordinator instance
     */
    IBatchCoordinatorReady setIndexerClass(String indexerClass);
    
    @Override
    void close();
}

/**
 * Ready-to-use batch coordinator with all coordination methods.
 * <p>
 * Obtained by calling setIndexerClass() on IBatchCoordinator.
 * Implements the competing consumer pattern where multiple indexers cooperatively process
 * batch files without duplication. Provides atomic claim operations, gap detection, and
 * stuck claim recovery.
 * <p>
 * <strong>Schema Handling:</strong> Extends ISchemaAwareDatabase. AbstractIndexer automatically
 * calls setSimulationRun() after run discovery, setting the schema for all operations.
 * <p>
 * <strong>Thread Safety:</strong> All methods are thread-safe. Multiple indexer instances
 * can call methods concurrently without coordination.
 */
public interface IBatchCoordinatorReady extends ISchemaAwareDatabase, IMonitorable, AutoCloseable {
    
    /**
     * Attempts to atomically claim a batch for processing.
     * <p>
     * Only one indexer instance of the same class can successfully claim a batch.
     * Uses composite key (indexer_class, batch_filename) to allow different indexer
     * types to process the same batch independently.
     * <p>
     * Implementation stores: indexer_class (from setIndexerClass), batch_filename, 
     * indexer_instance_id, tick_start, tick_end, claim_timestamp, status='claimed'
     *
     * @param batchFilename Storage filename (e.g., "batch_0000000000_0000000999.pb")
     * @param tickStart First tick number in batch
     * @param tickEnd Last tick number in batch
     * @param indexerInstanceId Unique ID of claiming indexer instance
     * @throws BatchAlreadyClaimedException if batch already claimed by another indexer of same class
     */
    void tryClaim(String batchFilename, long tickStart, long tickEnd, String indexerInstanceId)
        throws BatchAlreadyClaimedException;
    
    /**
     * Marks a batch as successfully completed.
     * <p>
     * Updates status='completed', records completion_timestamp.
     * Idempotent - safe to call multiple times.
     *
     * @param batchFilename Storage filename
     */
    void markCompleted(String batchFilename);
    
    /**
     * Marks a batch as failed with error details.
     * <p>
     * Updates status='failed', records error_message and completion_timestamp.
     * Failed batches remain visible for debugging but won't be re-claimed automatically.
     *
     * @param batchFilename Storage filename
     * @param errorMessage Human-readable error description
     */
    void markFailed(String batchFilename, String errorMessage);
    
    /**
     * Gets the maximum tick_end from completed or claimed batches.
     * <p>
     * Used for gap detection to determine expected next tick.
     *
     * @return Maximum tick_end, or -1 if no batches processed yet
     */
    long getMaxCompletedTickEnd();
    
    /**
     * Records a gap (missing tick range) in the database.
     * <p>
     * Inserts into coordinator_gaps table with status='pending'.
     * Idempotent - safe to call multiple times for same gap.
     *
     * @param gapStart First tick of missing range
     * @param gapEnd Last tick of missing range
     */
    void recordGap(long gapStart, long gapEnd);
    
    /**
     * Gets the oldest pending gap for this indexer.
     * <p>
     * Returns the gap with smallest gap_start_tick and status='pending'.
     * Used to prioritize gap filling - always try to fill oldest gap first.
     *
     * @return GapInfo with tick range and first_detected timestamp, or null if no gaps
     */
    GapInfo getOldestPendingGap();
    
    /**
     * Splits a gap after finding a batch within it.
     * <p>
     * Atomically deletes old gap and creates up to two new gaps (before/after found batch).
     * Uses SELECT FOR UPDATE to prevent concurrent modifications.
     * <p>
     * Example: Gap [1009, 2999], found batch [1500, 1800]
     * <ul>
     *   <li>DELETE gap [1009, 2999]</li>
     *   <li>INSERT gap [1009, 1490] (before batch, if non-empty)</li>
     *   <li>INSERT gap [1810, 2999] (after batch, if non-empty)</li>
     * </ul>
     *
     * @param oldGapStart Original gap start tick
     * @param oldGapEnd Original gap end tick
     * @param foundBatchStart Start tick of found batch
     * @param foundBatchEnd End tick of found batch
     * @param samplingInterval For calculating gap boundaries
     */
    void splitGap(long oldGapStart, long oldGapEnd, long foundBatchStart, long foundBatchEnd, int samplingInterval);
    
    /**
     * Marks a gap as permanent after timeout.
     * <p>
     * Updates status='permanent' in coordinator_gaps.
     * Permanent gaps are excluded from gap filling attempts.
     *
     * @param gapStart Start tick of gap to mark permanent
     */
    void markGapPermanent(long gapStart);
    
    /**
     * Closes the coordinator wrapper and releases its dedicated connection.
     */
    @Override
    void close();
}
```

### IMetadataReader

```java
/**
 * Database capability for reading simulation metadata indexed by MetadataIndexer.
 * <p>
 * Provides non-blocking read access to metadata. Services should poll using
 * AbstractIndexer.pollForMetadata() helper if metadata is not yet available.
 * <p>
 * <strong>Schema Handling:</strong> Extends ISchemaAwareDatabase. AbstractIndexer automatically
 * calls setSimulationRun() after run discovery, setting the schema for all read operations.
 * <p>
 * <strong>Thread Safety:</strong> All methods are thread-safe.
 * <p>
 * <strong>Usage Pattern:</strong> Injected via usage type "db-meta-read:resourceName".
 */
public interface IMetadataReader extends ISchemaAwareDatabase, IMonitorable, AutoCloseable {
    
    /**
     * Reads complete simulation metadata for a run.
     * <p>
     * Non-blocking - throws immediately if metadata not yet indexed.
     * Use hasMetadata() to check availability, or AbstractIndexer.pollForMetadata()
     * to wait with timeout.
     * <p>
     * Implementation reconstructs SimulationMetadata from database key-value storage.
     *
     * @param simulationRunId Raw simulation run ID
     * @return Complete simulation metadata
     * @throws MetadataNotFoundException if metadata not yet indexed
     */
    SimulationMetadata getMetadata(String simulationRunId) throws MetadataNotFoundException;
    
    /**
     * Checks if metadata has been indexed for a run.
     * <p>
     * Non-blocking check.
     *
     * @param simulationRunId Raw simulation run ID
     * @return true if metadata indexed, false otherwise
     */
    boolean hasMetadata(String simulationRunId);
    
    /**
     * Closes the reader wrapper and releases its dedicated connection.
     */
    @Override
    void close();
}

/**
 * Exception thrown when metadata is not yet available in database.
 */
public class MetadataNotFoundException extends Exception {
    public MetadataNotFoundException(String message) {
        super(message);
    }
}

/**
 * Exception thrown when attempting to claim a batch that is already claimed or completed.
 * <p>
 * This is a checked exception that represents an expected condition in the competing
 * consumer pattern, not an error. Callers should handle this gracefully by trying
 * the next available batch.
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

/**
 * Value object representing a gap (missing tick range) in batch processing.
 * <p>
 * Used by gap tracking components to communicate gap information between layers.
 */
public record GapInfo(
    long gapStartTick,
    long gapEndTick,
    Instant firstDetected,
    String status  // "pending" or "permanent"
) {}
```

## Database Schema Extensions

### coordinator_batches Table

**Owned by:** IBatchCoordinator capability (usageType: `db-coordinator`)

**Creation:** Lazy creation in `H2Database.doTryClaim()` with cached check.

**Purpose:** Track which batches have been claimed/processed by each indexer type.

**Schema Definition:**

```sql
CREATE TABLE IF NOT EXISTS coordinator_batches (
  indexer_class VARCHAR NOT NULL,
  batch_filename VARCHAR NOT NULL,
  tick_start BIGINT NOT NULL,
  tick_end BIGINT NOT NULL,
  indexer_instance_id VARCHAR,
  claim_timestamp TIMESTAMP,
  completion_timestamp TIMESTAMP,
  status VARCHAR NOT NULL,
  error_message TEXT,
  
  PRIMARY KEY (indexer_class, batch_filename),
  
  CONSTRAINT check_status CHECK (status IN ('claimed', 'completed', 'failed')),
  
  INDEX idx_tick_end (tick_end),
  INDEX idx_status (status)
);
```

**Columns:**
- `indexer_class`: Fully qualified class name (e.g., `org.evochora.datapipeline.services.indexers.DummyIndexer`)
- `batch_filename`: Batch file name (e.g., `batch_0000000000_0000000999.pb`)
- `tick_start`, `tick_end`: Parsed from filename, stored for efficient queries
- Composite PRIMARY KEY ensures each indexer type processes each batch independently

**Status Values:**
- `claimed`: Batch currently being processed
- `completed`: Batch successfully processed
- `failed`: Batch processing failed (error_message contains details)

### coordinator_gaps Table

**Owned by:** IBatchCoordinator capability (usageType: `db-coordinator`)

**Creation:** Lazy creation in `H2Database.doRecordGap()` with cached check.

**Purpose:** Track tick ranges with missing batches. Enables gap filling when batches appear later.

**Schema Definition:**

```sql
CREATE TABLE IF NOT EXISTS coordinator_gaps (
  indexer_class VARCHAR NOT NULL,
  gap_start_tick BIGINT NOT NULL,
  gap_end_tick BIGINT NOT NULL,
  first_detected TIMESTAMP NOT NULL,
  status VARCHAR NOT NULL,
  
  PRIMARY KEY (indexer_class, gap_start_tick),
  
  CONSTRAINT check_status CHECK (status IN ('pending', 'permanent')),
  
  INDEX idx_status (status),
  INDEX idx_tick_range (gap_start_tick, gap_end_tick)
);
```

**Columns:**
- `indexer_class`: Indexer that detected the gap
- `gap_start_tick`, `gap_end_tick`: Tick range with missing data
- `first_detected`: When gap was first discovered (for timeout detection)
- `status`: `pending` (active search) or `permanent` (timeout exceeded)

**Gap Lifecycle:**
```
1. Gap detected: INSERT INTO coordinator_gaps (gap_start=1009, gap_end=2999, status='pending')
2. Batch found in gap (e.g., batch_1500_1800):
   - DELETE gap [1009, 2999]
   - INSERT gap [1009, 1499] (if 1009 < 1500)
   - INSERT gap [1801, 2999] (if 1801 < 2999)
3. If gap persists > 60s: UPDATE status='permanent'
```

**Note:** Both tables exist within each simulation run's schema. No `simulation_run_id` column needed.

## AbstractDatabaseResource Extensions

AbstractDatabaseResource must be extended with new abstract methods grouped by capability interface:

```java
// In AbstractDatabaseResource.java

// Cached table initialization checks
private final ConcurrentHashMap<String, Boolean> coordinatorBatchesInitialized = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, Boolean> coordinatorGapsInitialized = new ConcurrentHashMap<>();

// ========================================================================
// IMetadataReader Capability
// ========================================================================

/**
 * Retrieves simulation metadata from the database.
 * <p>
 * <strong>Capability:</strong> {@link IMetadataReader#getMetadata(String)}
 * <p>
 * Implementation reads from metadata table in current schema.
 * Used by indexers to access simulation configuration (e.g., samplingInterval).
 *
 * @param connection Database connection (with schema already set)
 * @param simulationRunId Simulation run ID (for validation)
 * @return Parsed SimulationMetadata protobuf
 * @throws MetadataNotFoundException if metadata doesn't exist
 * @throws Exception for other database errors
 */
protected abstract SimulationMetadata doGetMetadata(Object connection, String simulationRunId) 
        throws Exception;

/**
 * Checks if metadata exists in the database.
 * <p>
 * <strong>Capability:</strong> {@link IMetadataReader#hasMetadata(String)}
 * <p>
 * Non-blocking check used for polling scenarios.
 *
 * @param connection Database connection (with schema already set)
 * @param simulationRunId Simulation run ID
 * @return true if metadata exists, false otherwise
 * @throws Exception if database query fails
 */
protected abstract boolean doHasMetadata(Object connection, String simulationRunId) 
        throws Exception;

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

// ========================================================================
// IBatchCoordinator Capability - Gap Tracking
// ========================================================================

/**
 * Ensures coordinator_gaps table exists for the current schema.
 * <p>
 * <strong>Capability:</strong> {@link IBatchCoordinatorReady} (internal - table creation)
 * <p>
 * Called once per schema via cached check in doRecordGap.
 * Creates table with PRIMARY KEY (indexer_class, gap_start_tick).
 *
 * @param connection Database connection (with schema already set)
 * @throws Exception if table creation fails
 */
protected abstract void doEnsureCoordinatorGapsTable(Object connection) throws Exception;

/**
 * Records a new gap in the database.
 * <p>
 * <strong>Capability:</strong> {@link IBatchCoordinatorReady#recordGap(long, long)}
 * <p>
 * Performs lazy table creation with cached check before INSERT.
 * Gap represents missing tick range [gapStart, gapEnd).
 *
 * @param connection Database connection (with schema already set)
 * @param indexerClass Fully qualified class name
 * @param gapStart Inclusive start of gap (multiple of samplingInterval)
 * @param gapEnd Exclusive end of gap (multiple of samplingInterval)
 * @throws Exception if insert fails (duplicate gaps are silently ignored via MERGE)
 */
protected abstract void doRecordGap(Object connection, String indexerClass, 
                                    long gapStart, long gapEnd) throws Exception;

/**
 * Retrieves the oldest pending gap (lowest gap_start_tick).
 * <p>
 * <strong>Capability:</strong> {@link IBatchCoordinatorReady#getOldestPendingGap()}
 * <p>
 * Used to prioritize filling oldest gaps first (FIFO gap processing).
 *
 * @param connection Database connection (with schema already set)
 * @param indexerClass Fully qualified class name
 * @return GapInfo with startTick and endTick, or null if no pending gaps
 * @throws Exception if query fails
 */
protected abstract GapInfo doGetOldestPendingGap(Object connection, String indexerClass) 
        throws Exception;

/**
 * Splits a gap atomically when a batch is found within it.
 * <p>
 * <strong>Capability:</strong> {@link IBatchCoordinatorReady} (internal - gap splitting)
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
 * @param connection Database connection (with schema already set)
 * @param indexerClass Fully qualified class name
 * @param gapStart Original gap start tick
 * @param batchTickStart Start of found batch (inclusive)
 * @param batchTickEnd End of found batch (exclusive)
 * @throws Exception if split fails (e.g., gap not found, concurrent modification)
 */
protected abstract void doSplitGap(Object connection, String indexerClass,
                                   long gapStart, long batchTickStart, long batchTickEnd) 
        throws Exception;

/**
 * Marks a gap as permanent (lost data).
 * <p>
 * <strong>Capability:</strong> {@link IBatchCoordinatorReady#markGapPermanent(long)}
 * <p>
 * Updates status to 'permanent'. Gap remains in database for observability
 * but is excluded from active search (WHERE status='pending').
 *
 * @param connection Database connection (with schema already set)
 * @param indexerClass Fully qualified class name
 * @param gapStart Gap start tick
 * @throws Exception if update fails (e.g., gap not found)
 */
protected abstract void doMarkGapPermanent(Object connection, String indexerClass, 
                                          long gapStart) throws Exception;
```

These methods are called by their respective wrappers (MetadataReaderWrapper, BatchCoordinatorWrapper) and implemented by concrete database classes (H2Database, PostgreSQLDatabase, etc.).

## H2Database Extensions

H2Database must implement all new abstract methods from AbstractDatabaseResource, grouped by capability interface.

### Schema Initialization Cache

**Purpose:** Avoid repeated `CREATE TABLE IF NOT EXISTS` overhead for coordination tables.

**Implementation:**
```java
// In H2Database.java (class fields)
private final ConcurrentHashMap<String, Boolean> coordinatorBatchesInitialized = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, Boolean> coordinatorGapsInitialized = new ConcurrentHashMap<>();
```

**Performance:**
- Metadata table: Created in `doInsertMetadata()` (1× per run, no caching needed)
- Coordination tables: Created in `doEnsureCoordinator*Table()` with cached check (1× per schema, then O(1) lookups)

### Implementation by Capability

#### IMetadataReader Capability

Implementations for `doGetMetadata()` and `doHasMetadata()` - see Phase 14_1 spec for full details.

#### IBatchCoordinator Capability - Batch Coordination

Implementations for batch coordination methods:

```java
// In H2Database.java

@Override
protected void doTryClaim(Object connection, String indexerClass, String batchFilename, 
                          long tickStart, long tickEnd, String indexerInstanceId) 
                          throws BatchAlreadyClaimedException, Exception {
    Connection conn = (Connection) connection;
    
    // Ensure coordinator_batches table exists (lazy creation with cache)
    String currentSchema = conn.getSchema();
    if (!coordinationTablesInitialized.contains(currentSchema)) {
        conn.createStatement().execute(
            "CREATE TABLE IF NOT EXISTS coordinator_batches (" +
            "  indexer_class VARCHAR(255) NOT NULL," +
            "  batch_filename VARCHAR(255) NOT NULL," +
            "  tick_start BIGINT NOT NULL," +
            "  tick_end BIGINT NOT NULL," +
            "  indexer_instance_id VARCHAR(255)," +
            "  claim_timestamp TIMESTAMP," +
            "  completion_timestamp TIMESTAMP," +
            "  status VARCHAR(20) NOT NULL," +
            "  error_message TEXT," +
            "  PRIMARY KEY (indexer_class, batch_filename)," +
            "  CONSTRAINT check_status CHECK (status IN ('claimed', 'completed', 'failed'))," +
            "  INDEX idx_tick_end (tick_end)," +
            "  INDEX idx_status (status)" +
            ")"
        );
        conn.commit();
        coordinationTablesInitialized.add(currentSchema);
    }
    
    try {
        // Atomic INSERT with composite primary key (indexer_class, batch_filename)
        PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO coordinator_batches " +
            "(indexer_class, batch_filename, tick_start, tick_end, indexer_instance_id, claim_timestamp, status) " +
            "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 'claimed')"
        );
        
        stmt.setString(1, indexerClass);
        stmt.setString(2, batchFilename);
        stmt.setLong(3, tickStart);
        stmt.setLong(4, tickEnd);
        stmt.setString(5, indexerInstanceId);
        stmt.executeUpdate();
        conn.commit();
        
        queriesExecuted.incrementAndGet();
        rowsInserted.incrementAndGet();
        
    } catch (SQLException e) {
        conn.rollback();
        
        if (e.getErrorCode() == 23505) { // H2 unique constraint violation
            // Expected case - batch already claimed by another indexer of same class
            throw new BatchAlreadyClaimedException(batchFilename);
        }
        
        // Unexpected database error
        throw e;
    }
}

@Override
protected void doMarkCompleted(Object connection, String indexerClass, String batchFilename) throws Exception {
    Connection conn = (Connection) connection;
    
    PreparedStatement stmt = conn.prepareStatement(
        "UPDATE coordinator_batches " +
        "SET status = 'completed', completion_timestamp = CURRENT_TIMESTAMP " +
        "WHERE indexer_class = ? AND batch_filename = ?"
    );
    stmt.setString(1, indexerClass);
    stmt.setString(2, batchFilename);
    stmt.executeUpdate();
    conn.commit();
    
    queriesExecuted.incrementAndGet();
}

@Override
protected void doMarkFailed(Object connection, String indexerClass, String batchFilename, String errorMessage) throws Exception {
    Connection conn = (Connection) connection;
    
    PreparedStatement stmt = conn.prepareStatement(
        "UPDATE coordinator_batches " +
        "SET status = 'failed', " +
        "    completion_timestamp = CURRENT_TIMESTAMP, " +
        "    error_message = ? " +
        "WHERE indexer_class = ? AND batch_filename = ?"
    );
    stmt.setString(1, errorMessage);
    stmt.setString(2, indexerClass);
    stmt.setString(3, batchFilename);
    stmt.executeUpdate();
    conn.commit();
    
    queriesExecuted.incrementAndGet();
}

@Override
protected long doGetMaxCompletedTickEnd(Object connection, String indexerClass) throws Exception {
    Connection conn = (Connection) connection;
    
    PreparedStatement stmt = conn.prepareStatement(
        "SELECT COALESCE(MAX(tick_end), -1) AS max_tick_end " +
        "FROM coordinator_batches " +
        "WHERE indexer_class = ? AND status IN ('completed', 'claimed')"
    );
    stmt.setString(1, indexerClass);
    ResultSet rs = stmt.executeQuery();
    
    queriesExecuted.incrementAndGet();
    
    if (rs.next()) {
        return rs.getLong("max_tick_end");
    }
    return -1; // No batches processed yet
}
```

#### IBatchCoordinator Capability - Gap Tracking

Implementations for gap tracking methods:

```java
// In H2Database.java

@Override
protected void doRecordGap(Object connection, String indexerClass, long gapStart, long gapEnd) throws Exception {
    Connection conn = (Connection) connection;
    
    // Ensure coordinator_gaps table exists (lazy creation with cache)
    String currentSchema = conn.getSchema();
    if (!coordinationTablesInitialized.contains(currentSchema)) {
        conn.createStatement().execute(
            "CREATE TABLE IF NOT EXISTS coordinator_gaps (" +
            "  indexer_class VARCHAR(255) NOT NULL," +
            "  gap_start_tick BIGINT NOT NULL," +
            "  gap_end_tick BIGINT NOT NULL," +
            "  first_detected TIMESTAMP NOT NULL," +
            "  status VARCHAR(20) NOT NULL," +
            "  PRIMARY KEY (indexer_class, gap_start_tick)," +
            "  CONSTRAINT check_status CHECK (status IN ('pending', 'permanent'))," +
            "  INDEX idx_status (status)," +
            "  INDEX idx_tick_range (gap_start_tick, gap_end_tick)" +
            ")"
        );
        conn.commit();
        coordinationTablesInitialized.add(currentSchema);
    }
    
    // Insert gap (use MERGE to handle race condition)
    PreparedStatement stmt = conn.prepareStatement(
        "MERGE INTO coordinator_gaps " +
        "(indexer_class, gap_start_tick, gap_end_tick, first_detected, status) " +
        "KEY(indexer_class, gap_start_tick) " +
        "VALUES (?, ?, ?, CURRENT_TIMESTAMP, 'pending')"
    );
    stmt.setString(1, indexerClass);
    stmt.setLong(2, gapStart);
    stmt.setLong(3, gapEnd);
    stmt.executeUpdate();
    conn.commit();
    
    queriesExecuted.incrementAndGet();
}

@Override
protected GapInfo doGetOldestPendingGap(Object connection, String indexerClass) throws Exception {
    Connection conn = (Connection) connection;
    
    PreparedStatement stmt = conn.prepareStatement(
        "SELECT gap_start_tick, gap_end_tick, first_detected, status " +
        "FROM coordinator_gaps " +
        "WHERE indexer_class = ? AND status = 'pending' " +
        "ORDER BY gap_start_tick ASC LIMIT 1"
    );
    stmt.setString(1, indexerClass);
    ResultSet rs = stmt.executeQuery();
    
    queriesExecuted.incrementAndGet();
    
    if (rs.next()) {
        return new GapInfo(
            rs.getLong("gap_start_tick"),
            rs.getLong("gap_end_tick"),
            rs.getTimestamp("first_detected").toInstant(),
            rs.getString("status")
        );
    }
    return null;
}

@Override
protected void doSplitGap(Object connection, String indexerClass, long oldGapStart, long oldGapEnd,
                         long foundBatchStart, long foundBatchEnd, int samplingInterval) throws Exception {
    Connection conn = (Connection) connection;
    
    try {
        // Atomic transaction with locking
        // Step 1: Lock gap to prevent concurrent modifications
        PreparedStatement lockStmt = conn.prepareStatement(
            "SELECT * FROM coordinator_gaps " +
            "WHERE indexer_class = ? AND gap_start_tick = ? " +
            "FOR UPDATE"
        );
        lockStmt.setString(1, indexerClass);
        lockStmt.setLong(2, oldGapStart);
        lockStmt.executeQuery();
        
        // Step 2: Delete old gap
        PreparedStatement deleteStmt = conn.prepareStatement(
            "DELETE FROM coordinator_gaps " +
            "WHERE indexer_class = ? AND gap_start_tick = ?"
        );
        deleteStmt.setString(1, indexerClass);
        deleteStmt.setLong(2, oldGapStart);
        deleteStmt.executeUpdate();
        
        // Step 3: Insert new gap before found batch (if exists)
        long newGapStartBefore = oldGapStart;
        long newGapEndBefore = foundBatchStart - samplingInterval;
        
        if (newGapStartBefore <= newGapEndBefore) {
            PreparedStatement insertBeforeStmt = conn.prepareStatement(
                "INSERT INTO coordinator_gaps " +
                "(indexer_class, gap_start_tick, gap_end_tick, first_detected, status) " +
                "VALUES (?, ?, ?, CURRENT_TIMESTAMP, 'pending')"
            );
            insertBeforeStmt.setString(1, indexerClass);
            insertBeforeStmt.setLong(2, newGapStartBefore);
            insertBeforeStmt.setLong(3, newGapEndBefore);
            insertBeforeStmt.executeUpdate();
        }
        
        // Step 4: Insert new gap after found batch (if exists)
        long newGapStartAfter = foundBatchEnd + samplingInterval;
        long newGapEndAfter = oldGapEnd;
        
        if (newGapStartAfter <= newGapEndAfter) {
            PreparedStatement insertAfterStmt = conn.prepareStatement(
                "INSERT INTO coordinator_gaps " +
                "(indexer_class, gap_start_tick, gap_end_tick, first_detected, status) " +
                "VALUES (?, ?, ?, CURRENT_TIMESTAMP, 'pending')"
            );
            insertAfterStmt.setString(1, indexerClass);
            insertAfterStmt.setLong(2, newGapStartAfter);
            insertAfterStmt.setLong(3, newGapEndAfter);
            insertAfterStmt.executeUpdate();
        }
        
        conn.commit();
        queriesExecuted.incrementAndGet();
        
        log.debug("Split gap [{}, {}] around batch [{}, {}] for {}",
                 oldGapStart, oldGapEnd, foundBatchStart, foundBatchEnd, indexerClass);
        
    } catch (SQLException e) {
        conn.rollback();
        throw e;
    }
}

@Override
protected void doMarkGapPermanent(Object connection, String indexerClass, long gapStart) throws Exception {
    Connection conn = (Connection) connection;
    
    PreparedStatement stmt = conn.prepareStatement(
        "UPDATE coordinator_gaps " +
        "SET status = 'permanent' " +
        "WHERE indexer_class = ? AND gap_start_tick = ? AND status = 'pending'"
    );
    stmt.setString(1, indexerClass);
    stmt.setLong(2, gapStart);
    stmt.executeUpdate();
    conn.commit();
    
    queriesExecuted.incrementAndGet();
}

// Metadata reading
@Override
protected SimulationMetadata doGetMetadata(Object connection, String simulationRunId) throws Exception {
    Connection conn = (Connection) connection;
    String schemaName = toSchemaName(simulationRunId);
    
    // Set schema
    conn.createStatement().execute("SET SCHEMA " + schemaName);
    
    // Read metadata from key-value table
    PreparedStatement stmt = conn.prepareStatement(
        "SELECT key, value FROM metadata"
    );
    ResultSet rs = stmt.executeQuery();
    
    Map<String, String> kvMap = new HashMap<>();
    while (rs.next()) {
        kvMap.put(rs.getString("key"), rs.getString("value"));
    }
    
    if (kvMap.isEmpty()) {
        throw new MetadataNotFoundException("No metadata found for run: " + simulationRunId);
    }
    
    // Reconstruct SimulationMetadata from JSON values
    Gson gson = new Gson();
    
    // Parse environment
    Map<String, Object> envMap = gson.fromJson(kvMap.get("environment"), Map.class);
    EnvironmentConfig.Builder envBuilder = EnvironmentConfig.newBuilder();
    envBuilder.setDimensions(((Double) envMap.get("dimensions")).intValue());
    ((List<Double>) envMap.get("shape")).forEach(d -> envBuilder.addShape(d.intValue()));
    ((List<Boolean>) envMap.get("toroidal")).forEach(envBuilder::addToroidal);
    
    // Parse simulation_info
    Map<String, Object> simInfoMap = gson.fromJson(kvMap.get("simulation_info"), Map.class);
    
    SimulationMetadata.Builder metaBuilder = SimulationMetadata.newBuilder();
    metaBuilder.setSimulationRunId((String) simInfoMap.get("runId"));
    metaBuilder.setStartTimeMs(((Double) simInfoMap.get("startTime")).longValue());
    metaBuilder.setInitialSeed(((Double) simInfoMap.get("seed")).longValue());
    metaBuilder.setEnvironment(envBuilder.build());
    
    // samplingInterval (CRITICAL for gap detection)
    if (simInfoMap.containsKey("samplingInterval")) {
        metaBuilder.setSamplingInterval(((Double) simInfoMap.get("samplingInterval")).intValue());
    } else {
        // Fallback for backward compatibility (remove after migration)
        metaBuilder.setSamplingInterval(1);
    }
    
    queriesExecuted.incrementAndGet();
    
    return metaBuilder.build();
}

@Override
protected boolean doHasMetadata(Object connection, String simulationRunId) throws Exception {
    Connection conn = (Connection) connection;
    String schemaName = toSchemaName(simulationRunId);
    
    // Check if schema exists
    PreparedStatement stmt = conn.prepareStatement(
        "SELECT 1 FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?"
    );
    stmt.setString(1, schemaName.toUpperCase()); // H2 stores schema names uppercase
    ResultSet rs = stmt.executeQuery();
    
    queriesExecuted.incrementAndGet();
    
    return rs.next();
}

```

### getWrappedResource Extension

```java
@Override
public IWrappedResource getWrappedResource(ResourceContext context) {
    String usageType = context.usageType();
    
    return switch (usageType) {
        case "db-meta-write" -> new MetadataWriterWrapper(this, context);
        case "db-meta-read" -> new MetadataReaderWrapper(this, context);
        case "db-coordinator" -> new BatchCoordinatorWrapper(this, context);
        default -> throw new IllegalArgumentException(
            "Unknown database usage type: " + usageType +
            ". Supported: db-meta-write, db-meta-read, db-coordinator"
        );
    };
}
```

## Wrapper Implementations

### BatchCoordinatorWrapper

```java
/**
 * Database-agnostic wrapper for batch coordination operations.
 * <p>
 * Implements fluent API pattern: IBatchCoordinator (initialization) and IBatchCoordinatorReady (operations).
 * Holds dedicated connection for exclusive use by one indexer instance.
 * Delegates coordination operations to AbstractDatabaseResource.
 * <p>
 * <strong>Compile-Time Safety:</strong> setIndexerClass() must be called before any coordination methods.
 * <p>
 * <strong>Performance Contract:</strong> All metrics use O(1) recording.
 */
class BatchCoordinatorWrapper implements IBatchCoordinator, IBatchCoordinatorReady, IWrappedResource, IMonitorable {
    
    private final AbstractDatabaseResource database;
    private final ResourceContext context;
    private final Object dedicatedConnection;
    private String indexerClass;  // Set via setIndexerClass(), used in all operations
    
    // Operation counters (O(1) atomic operations)
    private final AtomicLong claimAttempts = new AtomicLong(0);
    private final AtomicLong claimSuccesses = new AtomicLong(0);
    private final AtomicLong batchesCompleted = new AtomicLong(0);
    private final AtomicLong batchesFailed = new AtomicLong(0);
    private final AtomicLong gapsPending = new AtomicLong(0);
    private final AtomicLong gapsReleased = new AtomicLong(0);
    
    // Latency tracking with sliding window (O(1) recording, O(windowSeconds × buckets) retrieval)
    private final SlidingWindowPercentiles tryClaimLatency;
    private final SlidingWindowPercentiles markCompletedLatency;
    private final SlidingWindowPercentiles markFailedLatency;
    private final SlidingWindowPercentiles gapOperationsLatency;
    
    // Error tracking
    private final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();
    private static final int MAX_ERRORS = 100;
    
    BatchCoordinatorWrapper(AbstractDatabaseResource db, ResourceContext context) {
        this.database = db;
        this.context = context;
        
        try {
            this.dedicatedConnection = db.acquireDedicatedConnection();
        } catch (Exception e) {
            throw new RuntimeException("Failed to acquire database connection", e);
        }
        
        // Get metrics window from database resource config (default: 5 seconds)
        int windowSeconds = db.getOptions().hasPath("metricsWindowSeconds")
            ? db.getOptions().getInt("metricsWindowSeconds")
            : 5;
        
        // Initialize latency trackers with sliding window
        this.tryClaimLatency = new SlidingWindowPercentiles(windowSeconds);
        this.markCompletedLatency = new SlidingWindowPercentiles(windowSeconds);
        this.markFailedLatency = new SlidingWindowPercentiles(windowSeconds);
        this.gapOperationsLatency = new SlidingWindowPercentiles(windowSeconds);
    }
    
    @Override
    public IBatchCoordinatorReady setIndexerClass(String indexerClass) {
        if (indexerClass == null || indexerClass.isEmpty()) {
            throw new IllegalArgumentException("indexerClass cannot be null or empty");
        }
        this.indexerClass = indexerClass;
        return this;  // Return self as IBatchCoordinatorReady
    }
    
    private void ensureIndexerClassSet() {
        if (indexerClass == null) {
            throw new IllegalStateException(
                "indexerClass not set - must call setIndexerClass() before using coordinator"
            );
        }
    }
    
    @Override
    public void tryClaim(String batchFilename, long tickStart, long tickEnd, String indexerInstanceId)
            throws BatchAlreadyClaimedException {
        ensureIndexerClassSet();
        long startNanos = System.nanoTime();
        claimAttempts.incrementAndGet();
        
        try {
            database.doTryClaim(dedicatedConnection, indexerClass, batchFilename, tickStart, tickEnd, indexerInstanceId);
            
            // Claim successful
            claimSuccesses.incrementAndGet();
            tryClaimLatency.record(System.nanoTime() - startNanos);
            
        } catch (BatchAlreadyClaimedException e) {
            // Expected case - another indexer claimed it first
            // Re-throw to caller
            tryClaimLatency.record(System.nanoTime() - startNanos);
            throw e;
            
        } catch (Exception e) {
            // Unexpected database error
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
            database.doMarkCompleted(dedicatedConnection, indexerClass, batchFilename);
            batchesCompleted.incrementAndGet();
            markCompletedLatency.record(System.nanoTime() - startNanos);
            
        } catch (Exception e) {
            recordError("MARK_COMPLETED_FAILED", "Failed to mark batch completed",
                       "Batch: " + batchFilename + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to mark batch completed: " + batchFilename, e);
        }
    }
    
    @Override
    public void markFailed(String batchFilename, String errorMessage) {
        ensureIndexerClassSet();
        long startNanos = System.nanoTime();
        
        try {
            database.doMarkFailed(dedicatedConnection, indexerClass, batchFilename, errorMessage);
            batchesFailed.incrementAndGet();
            markFailedLatency.record(System.nanoTime() - startNanos);
            
        } catch (Exception e) {
            recordError("MARK_FAILED_FAILED", "Failed to mark batch failed",
                       "Batch: " + batchFilename + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to mark batch failed: " + batchFilename, e);
        }
    }
    
    @Override
    public long getMaxCompletedTickEnd() {
        ensureIndexerClassSet();
        try {
            return database.doGetMaxCompletedTickEnd(dedicatedConnection, indexerClass);
        } catch (Exception e) {
            recordError("GET_MAX_TICK_END_FAILED", "Failed to get max completed tick",
                       "Error: " + e.getMessage());
            throw new RuntimeException("Failed to get max completed tick", e);
        }
    }
    
    @Override
    public void recordGap(long gapStart, long gapEnd) {
        ensureIndexerClassSet();
        long startNanos = System.nanoTime();
        
        try {
            database.doRecordGap(dedicatedConnection, indexerClass, gapStart, gapEnd);
            gapsPending.incrementAndGet();
            gapOperationsLatency.record(System.nanoTime() - startNanos);
            
        } catch (Exception e) {
            recordError("RECORD_GAP_FAILED", "Failed to record gap",
                       "Gap: [" + gapStart + ", " + gapEnd + "], Error: " + e.getMessage());
            throw new RuntimeException("Failed to record gap: [" + gapStart + ", " + gapEnd + "]", e);
        }
    }
    
    @Override
    public GapInfo getOldestPendingGap() {
        ensureIndexerClassSet();
        try {
            return database.doGetOldestPendingGap(dedicatedConnection, indexerClass);
        } catch (Exception e) {
            recordError("GET_OLDEST_GAP_FAILED", "Failed to get oldest pending gap",
                       "Error: " + e.getMessage());
            throw new RuntimeException("Failed to get oldest pending gap", e);
        }
    }
    
    @Override
    public void splitGap(long oldGapStart, long oldGapEnd, long foundBatchStart, long foundBatchEnd, int samplingInterval) {
        ensureIndexerClassSet();
        long startNanos = System.nanoTime();
        
        try {
            database.doSplitGap(dedicatedConnection, indexerClass, oldGapStart, oldGapEnd, 
                              foundBatchStart, foundBatchEnd, samplingInterval);
            gapsReleased.incrementAndGet();
            gapOperationsLatency.record(System.nanoTime() - startNanos);
            
        } catch (Exception e) {
            recordError("SPLIT_GAP_FAILED", "Failed to split gap",
                       "OldGap: [" + oldGapStart + ", " + oldGapEnd + "], FoundBatch: [" + foundBatchStart + ", " + foundBatchEnd + "]");
            throw new RuntimeException("Failed to split gap", e);
        }
    }
    
    @Override
    public void markGapPermanent(long gapStart) {
        ensureIndexerClassSet();
        long startNanos = System.nanoTime();
        
        try {
            database.doMarkGapPermanent(dedicatedConnection, indexerClass, gapStart);
            gapOperationsLatency.record(System.nanoTime() - startNanos);
            
        } catch (Exception e) {
            recordError("MARK_GAP_PERMANENT_FAILED", "Failed to mark gap as permanent",
                       "GapStart: " + gapStart + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to mark gap permanent: " + gapStart, e);
        }
    }
    
    @Override
    public void close() {
        if (dedicatedConnection != null && dedicatedConnection instanceof Connection) {
            try {
                ((Connection) dedicatedConnection).close();
            } catch (SQLException e) {
                log.warn("Failed to close database connection", e);
            }
        }
    }
    
    @Override
    public String getResourceName() {
        return database.getResourceName();
    }
    
    @Override
    public Map<String, Number> getUsageStats() {
        return Map.of(
            "claim_attempts", claimAttempts.get(),
            "claim_successes", claimSuccesses.get(),
            "batches_completed", batchesCompleted.get(),
            "batches_failed", batchesFailed.get(),
            "gaps_pending", gapsPending.get(),
            "gaps_released", gapsReleased.get()
        );
    }
    
    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new LinkedHashMap<>();
        
        // Operation counts (O(1))
        metrics.put("claim_attempts", claimAttempts.get());
        metrics.put("claim_successes", claimSuccesses.get());
        metrics.put("claim_success_rate", 
            claimAttempts.get() > 0 ? claimSuccesses.get() / (double) claimAttempts.get() : 0.0);
        metrics.put("batches_completed", batchesCompleted.get());
        metrics.put("batches_failed", batchesFailed.get());
        metrics.put("gaps_pending", gapsPending.get());
        metrics.put("gaps_released", gapsReleased.get());
        metrics.put("error_count", errors.size());
        
        // Latency percentiles (O(windowSeconds × buckets) = O(55) per operation)
        // Convert nanoseconds to milliseconds for readability
        metrics.put("try_claim_latency_p50_ms", tryClaimLatency.getPercentile(50) / 1_000_000.0);
        metrics.put("try_claim_latency_p95_ms", tryClaimLatency.getPercentile(95) / 1_000_000.0);
        metrics.put("try_claim_latency_p99_ms", tryClaimLatency.getPercentile(99) / 1_000_000.0);
        metrics.put("try_claim_latency_avg_ms", tryClaimLatency.getAverage() / 1_000_000.0);
        
        metrics.put("mark_completed_latency_p95_ms", markCompletedLatency.getPercentile(95) / 1_000_000.0);
        metrics.put("mark_failed_latency_p95_ms", markFailedLatency.getPercentile(95) / 1_000_000.0);
        metrics.put("mark_gap_pending_latency_p95_ms", markGapPendingLatency.getPercentile(95) / 1_000_000.0);
        metrics.put("has_gap_before_latency_p95_ms", hasGapBeforeLatency.getPercentile(95) / 1_000_000.0);
        metrics.put("release_gap_pending_latency_p95_ms", releaseGapPendingLatency.getPercentile(95) / 1_000_000.0);
        
        return metrics;
    }
    
    @Override
    public boolean isHealthy() {
        return database.isHealthy();
    }
    
    @Override
    public List<OperationalError> getErrors() {
        return new ArrayList<>(errors);
    }
    
    @Override
    public void clearErrors() {
        errors.clear();
    }
    
    private void recordError(String code, String message, String details) {
        errors.add(new OperationalError(Instant.now(), code, message, details));
        
        while (errors.size() > MAX_ERRORS) {
            errors.pollFirst();
        }
    }
}
```

### MetadataReaderWrapper

```java
/**
 * Database-agnostic wrapper for metadata reading operations.
 * <p>
 * Provides non-blocking metadata queries with dedicated connection.
 * <p>
 * <strong>Performance Contract:</strong> All metrics use O(1) recording.
 */
class MetadataReaderWrapper implements IMetadataReader, IWrappedResource, IMonitorable {
    
    private final AbstractDatabaseResource database;
    private final ResourceContext context;
    private final Object dedicatedConnection;
    
    // Operation counters (O(1))
    private final AtomicLong metadataReads = new AtomicLong(0);
    private final AtomicLong metadataNotFound = new AtomicLong(0);
    private final AtomicLong readErrors = new AtomicLong(0);
    
    // Latency tracking with sliding window
    private final SlidingWindowPercentiles getMetadataLatency;
    private final SlidingWindowPercentiles hasMetadataLatency;
    
    // Error tracking
    private final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();
    private static final int MAX_ERRORS = 100;
    
    MetadataReaderWrapper(AbstractDatabaseResource db, ResourceContext context) {
        this.database = db;
        this.context = context;
        
        try {
            this.dedicatedConnection = db.acquireDedicatedConnection();
        } catch (Exception e) {
            throw new RuntimeException("Failed to acquire database connection", e);
        }
        
        // Get metrics window from database resource config (default: 5 seconds)
        int windowSeconds = db.getOptions().hasPath("metricsWindowSeconds")
            ? db.getOptions().getInt("metricsWindowSeconds")
            : 5;
        
        // Initialize latency trackers with sliding window
        this.getMetadataLatency = new SlidingWindowPercentiles(windowSeconds);
        this.hasMetadataLatency = new SlidingWindowPercentiles(windowSeconds);
    }
    
    @Override
    public SimulationMetadata getMetadata(String simulationRunId) throws MetadataNotFoundException {
        long startNanos = System.nanoTime();
        
        try {
            SimulationMetadata metadata = database.doGetMetadata(dedicatedConnection, simulationRunId);
            metadataReads.incrementAndGet();
            getMetadataLatency.record(System.nanoTime() - startNanos);  // Record in nanoseconds
            
            return metadata;
            
        } catch (MetadataNotFoundException e) {
            metadataNotFound.incrementAndGet();
            throw e; // Expected exception
            
        } catch (Exception e) {
            readErrors.incrementAndGet();
            recordError("GET_METADATA_FAILED", "Failed to read metadata",
                       "RunId: " + simulationRunId + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to read metadata for run: " + simulationRunId, e);
        }
    }
    
    @Override
    public boolean hasMetadata(String simulationRunId) {
        long startNanos = System.nanoTime();
        
        try {
            boolean exists = database.doHasMetadata(dedicatedConnection, simulationRunId);
            hasMetadataLatency.record(System.nanoTime() - startNanos);  // Record in nanoseconds
            
            return exists;
            
        } catch (Exception e) {
            readErrors.incrementAndGet();
            recordError("HAS_METADATA_FAILED", "Failed to check metadata existence",
                       "RunId: " + simulationRunId + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to check metadata for run: " + simulationRunId, e);
        }
    }
    
    @Override
    public void close() {
        if (dedicatedConnection != null && dedicatedConnection instanceof Connection) {
            try {
                ((Connection) dedicatedConnection).close();
            } catch (SQLException e) {
                log.warn("Failed to close database connection", e);
            }
        }
    }
    
    @Override
    public String getResourceName() {
        return database.getResourceName();
    }
    
    @Override
    public Map<String, Number> getUsageStats() {
        return Map.of(
            "metadata_reads", metadataReads.get(),
            "metadata_not_found", metadataNotFound.get(),
            "read_errors", readErrors.get()
        );
    }
    
    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new LinkedHashMap<>();
        
        metrics.put("metadata_reads", metadataReads.get());
        metrics.put("metadata_not_found", metadataNotFound.get());
        metrics.put("read_errors", readErrors.get());
        metrics.put("error_count", errors.size());
        
        // Convert nanoseconds to milliseconds for readability
        metrics.put("get_metadata_latency_p50_ms", getMetadataLatency.getPercentile(50) / 1_000_000.0);
        metrics.put("get_metadata_latency_p95_ms", getMetadataLatency.getPercentile(95) / 1_000_000.0);
        metrics.put("get_metadata_latency_p99_ms", getMetadataLatency.getPercentile(99) / 1_000_000.0);
        metrics.put("get_metadata_latency_avg_ms", getMetadataLatency.getAverage() / 1_000_000.0);
        
        metrics.put("has_metadata_latency_p95_ms", hasMetadataLatency.getPercentile(95) / 1_000_000.0);
        
        return metrics;
    }
    
    @Override
    public boolean isHealthy() {
        return database.isHealthy();
    }
    
    @Override
    public List<OperationalError> getErrors() {
        return new ArrayList<>(errors);
    }
    
    @Override
    public void clearErrors() {
        errors.clear();
    }
    
    private void recordError(String code, String message, String details) {
        errors.add(new OperationalError(Instant.now(), code, message, details));
        
        while (errors.size() > MAX_ERRORS) {
            errors.pollFirst();
        }
    }
}
```

## Indexer Component Classes

### Package: org.evochora.datapipeline.services.indexers.components

Reusable components for batch-processing indexers. Each component encapsulates a specific concern and can be composed as needed.

#### MetadataReadingComponent

```java
/**
 * Component for reading and caching simulation metadata from the database.
 * <p>
 * Provides access to metadata fields like samplingInterval which are needed
 * by other components (e.g., GapDetectionComponent).
 */
public class MetadataReadingComponent {
    private static final Logger log = LoggerFactory.getLogger(MetadataReadingComponent.class);
    
    private final IMetadataReader metadataReader;
    private final int maxPollDurationMs;
    private final int pollIntervalMs;
    
    private SimulationMetadata metadata;
    
    public MetadataReadingComponent(IMetadataReader metadataReader, 
                                   int pollIntervalMs, 
                                   int maxPollDurationMs) {
        this.metadataReader = metadataReader;
        this.pollIntervalMs = pollIntervalMs;
        this.maxPollDurationMs = maxPollDurationMs;
    }
    
    /**
     * Polls for metadata until available or timeout.
     * Blocks until metadata is available in the database.
     */
    public void loadMetadata(String runId) throws InterruptedException, TimeoutException {
        log.debug("Waiting for metadata to be indexed: runId={}", runId);
        
        long startTime = System.currentTimeMillis();
        
        while (!Thread.currentThread().isInterrupted()) {
            try {
                this.metadata = metadataReader.getMetadata(runId);
                log.info("Metadata available for runId={}", runId);
                return;
                
            } catch (MetadataNotFoundException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > maxPollDurationMs) {
                    throw new TimeoutException(
                        "Metadata not indexed within " + maxPollDurationMs + "ms for run: " + runId
                    );
                }
                
                Thread.sleep(pollIntervalMs);
            }
        }
        
        throw new InterruptedException("Metadata polling interrupted");
    }
    
    public int getSamplingInterval() {
        if (metadata == null) {
            throw new IllegalStateException("Metadata not loaded - call loadMetadata() first");
        }
        return metadata.getSamplingInterval();
    }
    
    public SimulationMetadata getMetadata() {
        return metadata;
    }
}
```

#### BatchCoordinationComponent

```java
/**
 * Component for coordinating batch processing across multiple indexer instances.
 * <p>
 * Encapsulates the IBatchCoordinatorReady resource and provides high-level
 * coordination methods.
 */
public class BatchCoordinationComponent {
    private static final Logger log = LoggerFactory.getLogger(BatchCoordinationComponent.class);
    
    private final IBatchCoordinatorReady coordinator;
    private final String indexerInstanceId;
    
    public BatchCoordinationComponent(IBatchCoordinator rawCoordinator, 
                                     String indexerClass,
                                     String indexerInstanceId) {
        // Fluent API: set indexer class to get ready coordinator
        this.coordinator = rawCoordinator.setIndexerClass(indexerClass);
        this.indexerInstanceId = indexerInstanceId;
    }
    
    /**
     * Attempts to claim a batch for processing.
     * @return true if successfully claimed, false if already claimed by another indexer
     */
    public boolean tryClaim(String batchFilename, long tickStart, long tickEnd) {
        try {
            coordinator.tryClaim(batchFilename, tickStart, tickEnd, indexerInstanceId);
            return true;
        } catch (BatchAlreadyClaimedException e) {
            return false; // Normal competing consumer behavior
        }
    }
    
    public void markCompleted(String batchFilename) {
        coordinator.markCompleted(batchFilename);
    }
    
    public void markFailed(String batchFilename, String errorMessage) {
        coordinator.markFailed(batchFilename, errorMessage);
    }
    
    public long getMaxCompletedTickEnd() {
        return coordinator.getMaxCompletedTickEnd();
    }
    
    public void recordGap(long gapStart, long gapEnd) {
        coordinator.recordGap(gapStart, gapEnd);
    }
    
    public GapInfo getOldestPendingGap() {
        return coordinator.getOldestPendingGap();
    }
    
    public void splitGap(long oldGapStart, long oldGapEnd, long foundBatchStart, long foundBatchEnd, int samplingInterval) {
        coordinator.splitGap(oldGapStart, oldGapEnd, foundBatchStart, foundBatchEnd, samplingInterval);
    }
    
    public void markGapPermanent(long gapStart) {
        coordinator.markGapPermanent(gapStart);
    }
}
```

#### GapDetectionComponent

```java
/**
 * Component for detecting and handling gaps in batch sequences.
 * <p>
 * Manages gap tracking lifecycle:
 * <ul>
 *   <li>Detect gaps when new batches are discovered</li>
 *   <li>Actively search for batches within known gaps</li>
 *   <li>Split gaps when batches are found within them</li>
 *   <li>Mark gaps as permanent after timeout</li>
 * </ul>
 * <p>
 * Requires MetadataReadingComponent (samplingInterval) and BatchCoordinationComponent (database operations).
 */
public class GapDetectionComponent {
    private static final Logger log = LoggerFactory.getLogger(GapDetectionComponent.class);
    
    private final MetadataReadingComponent metadata;
    private final BatchCoordinationComponent coordination;
    private final IBatchStorageRead storage;
    private final long gapWarningTimeoutMs;
    private final AtomicLong permanentGapsDetected = new AtomicLong(0);
    
    public GapDetectionComponent(MetadataReadingComponent metadata,
                                BatchCoordinationComponent coordination,
                                IBatchStorageRead storage,
                                long gapWarningTimeoutMs) {
        this.metadata = metadata;
        this.coordination = coordination;
        this.storage = storage;
        this.gapWarningTimeoutMs = gapWarningTimeoutMs;
    }
    
    /**
     * Detects gap before a new batch and records it in database.
     * <p>
     * Handles two cases:
     * <ul>
     *   <li>First batch with tick_start > 0: Record gap [0, tickStart - samplingInterval]</li>
     *   <li>Subsequent batches: Check if expectedNext != batchStart</li>
     * </ul>
     */
    public void detectAndRecordGap(String runId, long batchStartTick) {
        long maxCompleted = coordination.getMaxCompletedTickEnd();
        int samplingInterval = metadata.getSamplingInterval();
        
        if (maxCompleted == -1) {
            // This is the first batch we're processing
            if (batchStartTick > 0) {
                // Gap before first batch! (batches from tick 0 are missing)
                long gapStart = 0;
                long gapEnd = batchStartTick - samplingInterval;
                
                log.debug("Gap before first batch: [{}, {}], first batch starts at {}", 
                         gapStart, gapEnd, batchStartTick);
                
                coordination.recordGap(gapStart, gapEnd);
            }
            return;
        }
        
        // Check for gap after last completed batch
        long expectedNext = maxCompleted + samplingInterval;
        
        if (batchStartTick != expectedNext) {
            // Gap detected!
            long gapStart = expectedNext;
            long gapEnd = batchStartTick - samplingInterval;
            
            log.debug("Gap detected: [{}, {}] before batch starting at {}", 
                     gapStart, gapEnd, batchStartTick);
            
            coordination.recordGap(gapStart, gapEnd);
        }
    }
    
    /**
     * Tries to fill the oldest pending gap by searching storage.
     * <p>
     * @param runId Simulation run ID
     * @return Filename of found batch, or null if gap still empty
     */
    public String tryFillOldestGap(String runId) throws Exception {
        GapInfo gap = coordination.getOldestPendingGap();
        
        if (gap == null) {
            return null; // No gaps
        }
        
        // Check timeout
        long gapAge = System.currentTimeMillis() - gap.firstDetected().toEpochMilli();
        if (gapAge > gapWarningTimeoutMs) {
            // Mark as permanent
            coordination.markGapPermanent(gap.gapStartTick());
            permanentGapsDetected.incrementAndGet();
            
            log.warn("Permanent gap: [{}, {}] missing for {}s (threshold: {}s), marked as permanent. " +
                    "Data incomplete.",
                    gap.gapStartTick(), gap.gapEndTick(), gapAge / 1000, gapWarningTimeoutMs / 1000);
            
            return null; // Gap is permanent, no filling
        }
        
        // Search for batches within gap range
        BatchFileListResult result = storage.listBatchFiles(
            runId + "/",
            null,
            1,  // Only need first batch in gap
            gap.gapStartTick(),
            gap.gapEndTick()
        );
        
        if (result.getFilenames().isEmpty()) {
            return null; // Gap still empty
        }
        
        // Found batch in gap!
        String foundBatch = result.getFilenames().get(0);
        log.info("Found batch in gap: {} fills part of gap [{}, {}]",
                foundBatch, gap.gapStartTick(), gap.gapEndTick());
        
        return foundBatch;
    }
    
    /**
     * Splits a gap after processing a batch that was found within it.
     */
    public void splitGapAfterBatch(GapInfo gap, long foundBatchStart, long foundBatchEnd) {
        coordination.splitGap(
            gap.gapStartTick(),
            gap.gapEndTick(),
            foundBatchStart,
            foundBatchEnd,
            metadata.getSamplingInterval()
        );
    }
    
    public long getPermanentGapsDetected() {
        return permanentGapsDetected.get();
    }
}
```

#### TickBufferingComponent

```java
/**
 * Component for buffering ticks before batch processing.
 * <p>
 * Allows flexible insertBatchSize independent of storage batch size.
 * Supports flush on size threshold or timeout.
 */
public class TickBufferingComponent {
    private static final Logger log = LoggerFactory.getLogger(TickBufferingComponent.class);
    
    private final int insertBatchSize;
    private final long flushTimeoutMs;
    
    private final List<TickData> tickBuffer = new ArrayList<>();
    private final List<String> claimedBatches = new ArrayList<>();
    private long lastFlushTime;
    
    public TickBufferingComponent(int insertBatchSize, long flushTimeoutMs) {
        this.insertBatchSize = insertBatchSize;
        this.flushTimeoutMs = flushTimeoutMs;
        this.lastFlushTime = System.currentTimeMillis();
    }
    
    /**
     * Adds ticks from a claimed batch to the buffer.
     */
    public void addTicks(String batchFilename, List<TickData> ticks) {
        tickBuffer.addAll(ticks);
        claimedBatches.add(batchFilename);
    }
    
    /**
     * Checks if buffer should be flushed.
     * Flush when: size >= insertBatchSize OR timeout exceeded
     */
    public boolean shouldFlush() {
        if (tickBuffer.size() >= insertBatchSize) {
            return true;
        }
        
        long timeSinceLastFlush = System.currentTimeMillis() - lastFlushTime;
        if (!tickBuffer.isEmpty() && timeSinceLastFlush >= flushTimeoutMs) {
            log.debug("Flush timeout reached: {} ticks buffered for {}ms",
                     tickBuffer.size(), timeSinceLastFlush);
            return true;
        }
        
        return false;
    }
    
    /**
     * Returns buffered ticks and resets the buffer.
     */
    public List<TickData> getTicks() {
        List<TickData> result = new ArrayList<>(tickBuffer);
        tickBuffer.clear();
        return result;
    }
    
    /**
     * Returns claimed batch filenames and resets the list.
     */
    public List<String> getClaimedBatches() {
        List<String> result = new ArrayList<>(claimedBatches);
        claimedBatches.clear();
        lastFlushTime = System.currentTimeMillis();
        return result;
    }
    
    public int getCurrentBufferSize() {
        return tickBuffer.size();
    }
    
    public int getClaimedBatchCount() {
        return claimedBatches.size();
    }
}
```

## AbstractIndexer (Reference)

**Note:** AbstractIndexer already exists and provides:
- Storage access (`IBatchStorageRead`)
- Run ID discovery (configured or timestamp-based)
- Schema management (`prepareSchema()` template method, `setSchemaForAllDatabaseResources()`)
- Batch filename parsing utilities (`parseBatchStartTick()`, `parseBatchEndTick()`)

See existing implementation. No changes required for this phase.

## AbstractBatchProcessingIndexer

Base class for indexers that process tick batches with optional coordination, gap detection, and buffering.

```java
/**
 * Abstract base class for batch-processing indexers.
 * <p>
 * Provides intelligent batch processing loop that adapts to available components:
 * <ul>
 *   <li>MetadataReadingComponent - polls for metadata and provides samplingInterval</li>
 *   <li>BatchCoordinationComponent - coordinates with other indexer instances (competing consumers)</li>
 *   <li>GapDetectionComponent - detects and handles out-of-order batches</li>
 *   <li>TickBufferingComponent - buffers ticks for flexible batch sizes</li>
 * </ul>
 * <p>
 * Subclasses choose which components to use by setting the protected fields in their constructor.
 * The batch loop automatically adapts based on which components are non-null.
 * <p>
 * Subclasses must implement {@code processBatch(List<TickData>)} to define batch processing logic.
 */
public abstract class AbstractBatchProcessingIndexer extends AbstractIndexer {
    
    // Optional components (set by subclasses in constructor)
    protected MetadataReadingComponent metadata;
    protected BatchCoordinationComponent coordination;
    protected GapDetectionComponent gapDetection;
    protected TickBufferingComponent buffering;
    
    // Pagination state
    private String continuationToken = null;
    
    protected AbstractBatchProcessingIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
    }
    
    @Override
    protected void indexRun(String runId) throws Exception {
        // Step 1: Load metadata if component present
        if (metadata != null) {
            metadata.loadMetadata(runId);
            log.info("Metadata loaded: samplingInterval={}", metadata.getSamplingInterval());
        }
        
        log.info("Starting batch processing for run: {}", runId);
        
        // Step 2: Batch processing loop
        while (!Thread.currentThread().isInterrupted()) {
            boolean processedAny = processBatchDiscoveryIteration(runId);
            
            if (!processedAny) {
                // No new batches - check buffering timeout
                if (buffering != null && buffering.shouldFlush()) {
                    flushBuffer();
                }
                
                Thread.sleep(pollIntervalMs);
            }
        }
        
        // Step 3: Shutdown - flush remaining buffer
        if (buffering != null && buffering.getCurrentBufferSize() > 0) {
            log.info("Shutdown: flushing {} remaining ticks from {} batches",
                    buffering.getCurrentBufferSize(), buffering.getClaimedBatchCount());
            flushBuffer();
        }
    }
    
    /**
     * Process one iteration of batch discovery and claiming.
     * <p>
     * Priority: Gap filling → New batches
     * 
     * @return true if any batch was processed, false if nothing available
     */
    private boolean processBatchDiscoveryIteration(String runId) throws Exception {
        
        // PHASE 1: Try to fill gaps (priority!)
        if (gapDetection != null) {
            String gapBatch = gapDetection.tryFillOldestGap(runId);
            
            if (gapBatch != null) {
                // Found batch in gap!
                long tickStart = parseBatchStartTick(gapBatch);
                long tickEnd = parseBatchEndTick(gapBatch);
                
                // Store gap info for splitting later
                GapInfo gap = coordination.getOldestPendingGap();
                
                // Process the batch
                if (processOneBatch(gapBatch, tickStart, tickEnd)) {
                    // Split gap after successful processing
                    if (gap != null) {
                        gapDetection.splitGapAfterBatch(gap, tickStart, tickEnd);
                    }
                    return true; // Return immediately to check for new gaps
                }
            }
        }
        
        // PHASE 2: Discover new batches from storage
        String batchPath = indexerOptions.hasPath("batchPath") 
            ? indexerOptions.getString("batchPath")
            : runId + "/";
        
        BatchFileListResult result = storage.listBatchFiles(batchPath, continuationToken, 1);
        
        if (result.getFilenames().isEmpty()) {
            return false; // No new batches
        }
        
        String batchFilename = result.getFilenames().get(0);
        long tickStart = parseBatchStartTick(batchFilename);
        long tickEnd = parseBatchEndTick(batchFilename);
        
        // Detect gaps before new batch (if component present)
        if (gapDetection != null) {
            gapDetection.detectAndRecordGap(runId, tickStart);
        }
        
        // Process the batch (even if gap detected!)
        if (processOneBatch(batchFilename, tickStart, tickEnd)) {
            // Update continuation token
            continuationToken = result.isTruncated() ? result.getNextContinuationToken() : null;
            return true;
        }
        
        return false;
    }
    
    /**
     * Attempts to process one batch (claim, read, buffer).
     * @return true if batch was processed, false if already claimed by another indexer
     */
    private boolean processOneBatch(String batchFilename, long tickStart, long tickEnd) throws Exception {
        // Coordination (if component present) - try to claim
        if (coordination != null) {
            if (!coordination.tryClaim(batchFilename, tickStart, tickEnd)) {
                return false; // Already claimed by another indexer
            }
        }
        
        // Read batch
        List<TickData> ticks = storage.readBatch(batchFilename);
        
        // Buffering (if component present)
        if (buffering != null) {
            buffering.addTicks(batchFilename, ticks);
            
            if (buffering.shouldFlush()) {
                flushBuffer();
            }
        } else {
            // No buffering - process immediately
            processBatch(ticks);
            
            if (coordination != null) {
                coordination.markCompleted(batchFilename);
            }
        }
        
        return true;
    }
    
    /**
     * Flushes buffered ticks and marks batches as completed.
     */
    private void flushBuffer() throws Exception {
        List<TickData> ticks = buffering.getTicks();
        List<String> batches = buffering.getClaimedBatches();
        
        log.debug("Flushing buffer: {} ticks from {} batches", ticks.size(), batches.size());
        
        // Process the batch
        processBatch(ticks);
        
        // Mark all batches as completed (if coordination present)
        if (coordination != null) {
            for (String batchFilename : batches) {
                coordination.markCompleted(batchFilename);
                
                // Release gaps (if gap detection present)
                if (gapDetection != null) {
                    long tickEnd = parseBatchEndTick(batchFilename);
                    gapDetection.releaseGaps(tickEnd);
                }
            }
        }
    }
    
    /**
     * Template method for processing a batch of ticks.
     * Subclasses implement their specific indexing logic here.
     * 
     * @param ticks The ticks to process (may span multiple batch files if buffering is enabled)
     */
    protected abstract void processBatch(List<TickData> ticks) throws Exception;
    
    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new HashMap<>();
        
        // Component metrics
        if (buffering != null) {
            metrics.put("current_buffer_size", buffering.getCurrentBufferSize());
            metrics.put("claimed_batches_pending", buffering.getClaimedBatchCount());
        }
        if (gapDetection != null) {
            metrics.put("permanent_gaps_detected", gapDetection.getPermanentGapsDetected());
        }
        
        return metrics;
    }
}
```

## DummyIndexer Implementation

```java
/**
 * Test indexer that exercises batch coordination infrastructure without writing data.
 * <p>
 * Uses ALL components to validate the complete batch-processing infrastructure:
 * <ul>
 *   <li>MetadataReadingComponent - waits for metadata, provides samplingInterval</li>
 *   <li>BatchCoordinationComponent - coordinates with other DummyIndexer instances</li>
 *   <li>GapDetectionComponent - handles out-of-order batches</li>
 *   <li>TickBufferingComponent - buffers ticks with flexible batch size</li>
 * </ul>
 * <p>
 * DummyIndexer reads tick data but performs no database writes - it only observes and counts.
 * <p>
 * <strong>Purpose:</strong> Validate competing consumer patterns, gap detection, and coordination
 * logic in isolation before implementing actual data-processing indexers.
 * <p>
 * <strong>Usage:</strong> Run multiple DummyIndexer instances concurrently to test coordination
 * and verify no duplicate processing occurs.
 */
public class DummyIndexer extends AbstractBatchProcessingIndexer implements IMonitorable {
    
    private final AtomicLong batchesProcessed = new AtomicLong(0);
    private final AtomicLong ticksProcessed = new AtomicLong(0);
    private final AtomicLong cellsObserved = new AtomicLong(0);
    private final AtomicLong organismsObserved = new AtomicLong(0);
    
    public DummyIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        
        // Setup ALL components for full infrastructure testing
        
        // 1. Metadata Reading
        IMetadataReader metadataReader = getRequiredResource(IMetadataReader.class, "db-meta-read");
        int pollIntervalMs = options.hasPath("pollIntervalMs") ? options.getInt("pollIntervalMs") : 1000;
        int maxPollDurationMs = options.hasPath("maxPollDurationMs") ? options.getInt("maxPollDurationMs") : 300000;
        this.metadata = new MetadataReadingComponent(metadataReader, pollIntervalMs, maxPollDurationMs);
        
        // 2. Batch Coordination (with fluent API for compile-time safety)
        IBatchCoordinator rawCoordinator = getRequiredResource(IBatchCoordinator.class, "db-coordinator");
        String instanceId = name + "-" + Thread.currentThread().getId();
        this.coordination = new BatchCoordinationComponent(
            rawCoordinator,
            this.getClass().getName(),  // Fully qualified class name
            instanceId
        );
        
        // 3. Gap Detection (depends on metadata + coordination + storage)
        long gapWarningTimeoutMs = options.hasPath("gapWarningTimeoutMs") 
            ? options.getLong("gapWarningTimeoutMs") : 60000;
        this.gapDetection = new GapDetectionComponent(metadata, coordination, storage, gapWarningTimeoutMs);
        
        // 4. Tick Buffering
        int insertBatchSize = options.hasPath("insertBatchSize") ? options.getInt("insertBatchSize") : 1000;
        long flushTimeoutMs = options.hasPath("flushTimeoutMs") ? options.getLong("flushTimeoutMs") : 5000;
        
        if (insertBatchSize <= 0) {
            throw new IllegalArgumentException("insertBatchSize must be positive");
        }
        if (flushTimeoutMs <= 0) {
            throw new IllegalArgumentException("flushTimeoutMs must be positive");
        }
        
        this.buffering = new TickBufferingComponent(insertBatchSize, flushTimeoutMs);
        
        log.info("DummyIndexer initialized with all components: insertBatchSize={}, flushTimeout={}ms, gapWarningTimeout={}ms",
                insertBatchSize, flushTimeoutMs, gapWarningTimeoutMs);
    }
    
    @Override
    protected void processBatch(List<TickData> ticks) {
        // Simply count what we observed - no actual processing
        for (TickData tick : ticks) {
            ticksProcessed.incrementAndGet();
            cellsObserved.addAndGet(tick.getCellsCount());
            organismsObserved.addAndGet(tick.getOrganismsCount());
        }
        
        batchesProcessed.incrementAndGet();
        
        log.debug("Processed batch: {} ticks, {} cells, {} organisms",
                ticks.size(),
                ticks.stream().mapToLong(TickData::getCellsCount).sum(),
                ticks.stream().mapToLong(TickData::getOrganismsCount).sum());
    }
    
    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new HashMap<>(super.getMetrics());
        
        metrics.put("batches_processed", batchesProcessed.get());
        metrics.put("ticks_processed", ticksProcessed.get());
        metrics.put("cells_observed", cellsObserved.get());
        metrics.put("organisms_observed", organismsObserved.get());
        
        return metrics;
    }
    
    @Override
    public boolean isHealthy() {
        return getCurrentState() != State.ERROR;
    }
    
    @Override
    public List<OperationalError> getErrors() {
        return Collections.emptyList(); // DummyIndexer doesn't track errors
    }
    
    @Override
    public void clearErrors() {
        // No-op
    }
}
```

## Configuration

**Note:** Database resource `index-database` is already configured in `evochora.conf` - no changes needed.

### DummyIndexer Service

```hocon
dummy-indexer-1 {
  className = "org.evochora.datapipeline.services.indexers.DummyIndexer"
  
  resources {
    storage = "storage-read:tick-storage"
    metadataReader = "db-meta-read:index-database"
    coordinator = "db-coordinator:index-database"
  }
  
  options {
    # Optional: Specific run to index (post-mortem mode)
    # If not set, uses timestamp-based discovery (parallel mode)
    # runId = "20251006143025-550e8400-e29b-41d4-a716-446655440000"
    
    # Run discovery polling (parallel mode)
    pollIntervalMs = 1000
    maxPollDurationMs = 300000  # 5 minutes
    
    # Optional: Override batch path (default: runId + "/")
    # batchPath = "custom/path/"
    
    # Tick buffering
    insertBatchSize = 5000      # Accumulate 5000 ticks before "processing"
    flushTimeoutMs = 5000        # Flush partial buffer after 5s idle
    
    # Gap detection
    gapWarningTimeoutMs = 60000  # Warn about permanent gaps after 1 minute
  }
}

# Multiple competing consumers
dummy-indexer-2 {
  className = "org.evochora.datapipeline.services.indexers.DummyIndexer"
  resources {
    storage = "storage-read:tick-storage"
    metadataReader = "db-meta-read:index-database"
    coordinator = "db-coordinator:index-database"
  }
  options {
    pollIntervalMs = 1000
    insertBatchSize = 1000  # Different batch size from indexer-1
    flushTimeoutMs = 3000
    gapWarningTimeoutMs = 60000
  }
}
```

## Testing Strategy

### Unit Tests

**BatchCoordinatorWrapperTest:**
- Test atomic claim operations (multiple threads competing)
- Test markCompleted, markFailed
- Test recordGap, getOldestPendingGap
- Test splitGap with various batch sizes
- Test markGapPermanent
- Mock AbstractDatabaseResource

**MetadataReaderWrapperTest:**
- Test getMetadata success and MetadataNotFoundException
- Test hasMetadata
- Mock AbstractDatabaseResource

**Component Tests:**
- `MetadataReadingComponentTest`: Test polling, timeout, caching
- `BatchCoordinationComponentTest`: Test fluent API, claim wrapping
- `GapDetectionComponentTest`: Test gap detection, splitting, permanent marking
- `TickBufferingComponentTest`: Test buffering, flush triggers

**AbstractBatchProcessingIndexerTest:**
- Test component composition (null vs non-null components)
- Test batch loop with/without gaps
- Test pagination with continuation token
- Test gap detection logic
- Test parseBatchStartTick/parseBatchEndTick
- Mock storage, coordinator, metadataReader

**DummyIndexerTest:**
- Test processBatch counting
- Verify no side effects (no database writes)
- Mock dependencies

### Integration Tests

**DummyIndexerIntegrationTest:**

```java
@ExtendWith(LogWatchExtension.class)
@Tag("integration")
class DummyIndexerIntegrationTest {
    
    private H2Database testDatabase;
    private FileSystemStorageResource testStorage;
    private Path tempStorageDir;
    private ServiceManager serviceManager;
    
    @BeforeEach
    void setup() throws IOException {
        // In-memory H2 database with UUID for isolation
        testDatabase = new H2Database("test-db", ConfigFactory.parseString("""
            jdbcUrl = "jdbc:h2:mem:test_%s;DB_CLOSE_DELAY=-1"
            maxPoolSize = 4
            minIdle = 2
            connectionTimeout = 5000
            """.formatted(UUID.randomUUID())));
        
        // Temporary storage directory
        tempStorageDir = Files.createTempDirectory("evochora-test-");
        testStorage = new FileSystemStorageResource("test-storage",
            ConfigFactory.parseString("rootDirectory = \"" + tempStorageDir + "\""));
    }
    
    @AfterEach
    void cleanup() throws IOException {
        if (testDatabase != null) {
            assertEquals(0, testDatabase.getMetrics().get("h2_pool_active_connections"),
                "Connection leak detected");
            testDatabase.stop();
        }
        
        if (tempStorageDir != null) {
            Files.walk(tempStorageDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        // Ignore
                    }
                });
        }
    }
    
    @Test
    void testSingleIndexer_ProcessesAllBatches() throws Exception {
        // Setup: Create metadata and batch files
        String runId = createTestRun(samplingInterval = 10, numBatches = 5);
        
        // Start DummyIndexer
        Config indexerConfig = ConfigFactory.parseString("""
            runId = "%s"
            insertBatchSize = 1000
            flushTimeoutMs = 1000
            """.formatted(runId));
        
        DummyIndexer indexer = new DummyIndexer("test-indexer", indexerConfig, 
            Map.of(
                "storage", List.of(testStorage),
                "metadataReader", List.of(testDatabase),
                "coordinator", List.of(testDatabase)
            ));
        
        indexer.start();
        
        // Wait for processing to complete
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("batches_processed").intValue() == 5);
        
        indexer.stop();
        
        // Verify all batches marked as completed
        int completedCount = queryCompletedBatchCount(runId);
        assertEquals(5, completedCount);
    }
    
    @Test
    void testCompetingConsumers_NoDuplicateProcessing() throws Exception {
        // Setup: Create metadata and many batch files
        String runId = createTestRun(samplingInterval = 10, numBatches = 100);
        
        // Start 3 competing DummyIndexers
        List<DummyIndexer> indexers = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Config config = ConfigFactory.parseString("""
                runId = "%s"
                insertBatchSize = 500
                flushTimeoutMs = 1000
                """.formatted(runId));
            
            DummyIndexer indexer = new DummyIndexer("indexer-" + i, config,
                Map.of(
                    "storage", List.of(testStorage),
                    "metadataReader", List.of(testDatabase),
                    "coordinator", List.of(testDatabase)
                ));
            
            indexer.start();
            indexers.add(indexer);
        }
        
        // Wait for all batches to be processed
        await().atMost(30, TimeUnit.SECONDS)
            .until(() -> {
                long totalProcessed = indexers.stream()
                    .mapToLong(idx -> idx.getMetrics().get("batches_processed").longValue())
                    .sum();
                return totalProcessed == 100;
            });
        
        // Stop all indexers
        indexers.forEach(DummyIndexer::stop);
        
        // Verify: No duplicates - each batch processed exactly once
        int completedCount = queryCompletedBatchCount(runId);
        assertEquals(100, completedCount);
        
        // Verify: Workload distributed (no single indexer did everything)
        for (DummyIndexer indexer : indexers) {
            long processed = indexer.getMetrics().get("batches_processed").longValue();
            assertTrue(processed > 0, "Indexer " + indexer + " processed no batches");
            assertTrue(processed < 100, "Indexer " + indexer + " processed all batches");
        }
    }
    
    @Test
    void testGapDetection_TemporaryGap() throws Exception {
        // Setup: Out-of-order batches (batch 1 arrives late)
        String runId = createTestRun(samplingInterval = 10);
        createBatch(runId, tickStart=0, tickEnd=990);
        createBatch(runId, tickStart=2000, tickEnd=2990);  // Gap! Missing batch 1000-1990
        createBatch(runId, tickStart=3000, tickEnd=3990);
        
        DummyIndexer indexer = startIndexer(runId);
        
        // Wait for initial batches processed
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("batches_processed").intValue() >= 2);
        
        // Verify gap recorded
        assertEquals(2, queryCompletedBatchCount(runId));  // batch 0-990, 2000-2990
        assertEquals(1, queryPendingGapCount(runId));      // Gap [1000, 1990]
        
        // Now "deliver" the missing batch
        createBatch(runId, tickStart=1000, tickEnd=1990);
        
        // Wait for gap to be filled
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("batches_processed").intValue() == 4);
        
        indexer.stop();
        
        // Verify all batches completed, no gaps remaining
        assertEquals(4, queryCompletedBatchCount(runId));
        assertEquals(0, queryPendingGapCount(runId));
    }
    
    @Test
    @ExpectLog(logger = "org.evochora.datapipeline.services.indexers.components.GapDetectionComponent",
               level = WARN, message = "Permanent gap (data lost): [*, *)")
    void testGapDetection_PermanentGap() throws Exception {
        // Setup: Batches with permanent gap
        String runId = createTestRun(samplingInterval = 10);
        createBatch(runId, tickStart=0, tickEnd=990);
        createBatch(runId, tickStart=3000, tickEnd=3990);  // Gap [1000, 2990] never filled
        
        // Configure short gap warning timeout for test
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            gapWarningTimeoutMs = 2000
            """.formatted(runId));
        
        DummyIndexer indexer = startIndexer(runId, config);
        
        // Wait for gap warning (should appear after 2 seconds)
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("permanent_gaps_detected").intValue() > 0);
        
        indexer.stop();
        
        // Verify: 2 batches completed (0-990, 3000-3990), 1 gap marked permanent
        assertEquals(2, queryCompletedBatchCount(runId));
        assertEquals(1, queryPermanentGapCount(runId));  // Gap [1000, 2990]
    }
    
    @Test
    void testTickBuffering_SmallerInsertBatchSize() throws Exception {
        // Storage batches: 1000 ticks each
        // insertBatchSize: 500 ticks
        // Expected: 2 "processing" calls per storage batch
        
        String runId = createTestRun(samplingInterval = 1, 
            numBatches = 2, ticksPerBatch = 1000);
        
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            insertBatchSize = 500
            """.formatted(runId));
        
        CountingDummyIndexer indexer = new CountingDummyIndexer("test", config, resources);
        indexer.start();
        
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.processCallCount.get() == 4); // 2 batches × 2 flushes
        
        indexer.stop();
    }
    
    @Test
    void testTickBuffering_LargerInsertBatchSize() throws Exception {
        // Storage batches: 1000 ticks each
        // insertBatchSize: 5000 ticks
        // Expected: 1 "processing" call per 5 storage batches
        
        String runId = createTestRun(samplingInterval = 1,
            numBatches = 10, ticksPerBatch = 1000);
        
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            insertBatchSize = 5000
            """.formatted(runId));
        
        CountingDummyIndexer indexer = new CountingDummyIndexer("test", config, resources);
        indexer.start();
        
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.processCallCount.get() == 2); // 10 batches × 1000 / 5000 = 2 flushes
        
        indexer.stop();
    }
    
    @Test
    void testFlushTimeout_PartialBatch() throws Exception {
        // Small number of batches, long flushTimeout
        // Should flush partial buffer on shutdown
        
        String runId = createTestRun(samplingInterval = 1, numBatches = 3, ticksPerBatch = 100);
        
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            insertBatchSize = 1000
            flushTimeoutMs = 60000
            """.formatted(runId));
        
        DummyIndexer indexer = startIndexer(runId, config);
        
        // Wait until all batches claimed (but not flushed yet)
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("current_buffer_size").intValue() == 300);
        
        // Stop indexer - should flush partial buffer
        indexer.stop();
        
        // Verify all ticks processed
        assertEquals(300, indexer.getMetrics().get("ticks_processed").intValue());
    }
    
    // Helper methods
    
    private String createTestRun(int samplingInterval, int numBatches) {
        // Generate test run with metadata and batch files
        // ... implementation ...
    }
    
    private int queryCompletedBatchCount(String runId) {
        // Query coordinator_batches table:
        // SELECT COUNT(*) FROM coordinator_batches 
        // WHERE indexer_class='DummyIndexer' AND status='completed'
    }
    
    private int queryPendingGapCount(String runId) {
        // Query coordinator_gaps table:
        // SELECT COUNT(*) FROM coordinator_gaps 
        // WHERE indexer_class='DummyIndexer' AND status='pending'
    }
    
    private int queryPermanentGapCount(String runId) {
        // Query coordinator_gaps table:
        // SELECT COUNT(*) FROM coordinator_gaps 
        // WHERE indexer_class='DummyIndexer' AND status='permanent'
    }
    
    // Test helper subclass that counts processBatch calls
    private static class CountingDummyIndexer extends DummyIndexer {
        final AtomicInteger processCallCount = new AtomicInteger(0);
        
        @Override
        protected void processBatch(List<TickData> ticks) {
            processCallCount.incrementAndGet();
            super.processBatch(ticks);
        }
    }
}
```

## Architectural Decisions

### 1. AbstractDatabaseWrapper Base Class (DRY + Connection Pooling Optimization)

**Decision:** Introduce `AbstractDatabaseWrapper` as base class for all database capability wrappers with smart connection caching.

**Problem:** Without base class, each wrapper (MetadataReaderWrapper, BatchCoordinatorWrapper, etc.) duplicates ~150 lines of identical code:
- Connection management
- Schema setting (`setSimulationRun()` implementation)
- Error tracking and recording
- Metrics infrastructure (Template Method Pattern)
- Resource lifecycle (close, isHealthy, getErrors, etc.)

**Additional Problem:** Connection Pool Exhaustion
- Each indexer needs multiple capabilities (metadata reader, coordinator)
- Each capability held a dedicated connection permanently
- Example: 10 indexers × 2 capabilities = 20 connections (exhausts default pool!)

**Solution:** Extract common functionality into `AbstractDatabaseWrapper` with lazy connection caching:
```java
public abstract class AbstractDatabaseWrapper 
        implements ISchemaAwareDatabase, IWrappedResource, IMonitorable, AutoCloseable {
    private Object cachedConnection = null;  // Lazy acquired, smart released
    private String cachedSchema = null;
    
    // Acquire connection only when needed
    protected Object ensureConnection() { ... }
    
    // Release connection during idle periods (e.g., before Thread.sleep)
    public void releaseConnection() { ... }
    
    // Common implementations: setSimulationRun(), close(), isHealthy(), getErrors(), etc.
    // Template Method: getMetrics() calls addCustomMetrics() hook
}
```

**Connection Lifecycle:**
1. **Constructor:** No connection acquired (lazy!)
2. **First Operation:** `ensureConnection()` acquires and caches connection, sets schema
3. **Active Work:** Connection remains cached (no overhead)
4. **Idle/Polling:** Indexer calls `releaseConnection()` before `Thread.sleep`
5. **Next Operation:** `ensureConnection()` re-acquires connection (schema already cached!)

**Benefits:**
- ✅ **Code Reduction:** Wrappers go from ~200 to ~80 lines (-60%)
- ✅ **Consistency:** All wrappers behave identically for common operations
- ✅ **DRY:** Schema setting, error handling, metrics pattern implemented once
- ✅ **Template Method Pattern:** Same as AbstractDatabaseResource (familiar to developers)
- ✅ **Type Safety:** Final methods prevent accidental overrides
- ✅ **Maintainability:** Bug fixes in common code benefit all wrappers
- ✅ **Connection Pool Optimization:** 10-20 pool size sufficient for 100+ indexers
  - Connections acquired lazily (only when needed)
  - Released during polling (freed for other indexers)
  - Cached during active batch processing (no SET SCHEMA overhead)

**Implementation in Phases:**
- Phase 2.5.1: Introduced with MetadataReaderWrapper
- Phase 2.5.2: Adopted by BatchCoordinatorWrapper
- Future: MetadataWriterWrapper will be refactored to use it

### 2. Component-Based Architecture

Composition over inheritance for batch-processing features.

**Structure:**
- `AbstractIndexer`: Minimal (storage + run discovery)
- `AbstractBatchProcessingIndexer`: Intelligent batch loop with component support
- Components: `MetadataReadingComponent`, `BatchCoordinationComponent`, `GapDetectionComponent`, `TickBufferingComponent`

**Benefits:**
- Each indexer chooses which components it needs
- No unnecessary dependencies (MetadataIndexer doesn't need coordination)
- Future-proof: New components (e.g., `SnapshotStateManagement`) can be added without refactoring
- Scales to 10+ indexer types without massive inheritance hierarchies

**Example Usage:**
```java
// Full-featured: DummyIndexer uses all components
class DummyIndexer extends AbstractBatchProcessingIndexer {
    this.metadata = new MetadataReadingComponent(...);
    this.coordination = new BatchCoordinationComponent(...);
    this.gapDetection = new GapDetectionComponent(metadata, coordination, ...);
    this.buffering = new TickBufferingComponent(...);
}

// Simple: Single-instance indexer without coordination
class SimpleAggregator extends AbstractBatchProcessingIndexer {
    this.buffering = new TickBufferingComponent(...);
    // No coordination, no gap detection
}
```

### 2. Coordination Strategy

Pessimistic locking with atomic database operations (INSERT with unique constraint).

- Guarantees no duplicate processing
- Database handles atomicity and races
- Simple mental model
- Visible coordination state for operations

### 2. Gap Detection Formula

Deterministic formula using samplingInterval:

```
expectedNextStart = previousBatch.tick_end + samplingInterval
hasGap = (batchStartTick != expectedNextStart)
```

- SimulationEngine is deterministic (always samples tick % samplingInterval == 0)
- Works with variable batch sizes (timeout-triggered batches)
- No need for complex analysis or tolerances
- Requires samplingInterval in SimulationMetadata (completed in Prerequisites)

### 3. Gap Handling Strategy

Separate gap table with interval-based tracking and active gap filling.

**Two Tables:**
- `coordinator_batches`: Tracks processed batches (claimed/completed/failed)
- `coordinator_gaps`: Tracks missing tick ranges (pending/permanent)

**Gap Lifecycle:**
1. **Gap Detection:** When new batch discovered, check if tick range is missing
   - First batch with tick_start > 0 → record gap [0, tickStart - samplingInterval]
   - Subsequent batches: gap if batchStart ≠ (maxCompleted + samplingInterval)
   
2. **Gap Filling (Priority!):** Before processing new batches, try to fill oldest gap
   - Query: `getOldestPendingGap()` → Gap[start, end]
   - Search storage: `listBatchFiles(runId, null, 1, start, end)`
   - If found: Process batch, split gap into smaller sub-gaps (or delete if filled)
   
3. **Gap Splitting:** When batch found partially fills gap
   - Example: Gap [1009, 2990], found batch [1500, 1800]
   - DELETE gap [1009, 2990]
   - INSERT gap [1009, 1490] (before batch, if non-empty)
   - INSERT gap [1810, 2990] (after batch, if non-empty)
   - Atomic with SELECT FOR UPDATE locking
   
4. **Permanent Gaps:** After timeout (default 60s)
   - UPDATE status='permanent'
   - Log WARNING once
   - Excluded from future gap filling
   - Pipeline continues

**Benefits:**
- Non-blocking (process new batches while searching for gaps)
- Efficient (only search within known gap ranges, not entire storage)
- Backward compatible with pagination (gaps don't depend on continuation token)
- Scalable (works with variable batch sizes)
- Observable (gap status visible in database)

### 4. Tick Buffering

Flexible insertBatchSize independent of storage batch size.

- Different indexers have different optimal batch sizes
- Environment indexer: many small transactions (insertBatchSize=100)
- Organism indexer: large bulk inserts (insertBatchSize=10000)
- Decouples storage concerns from processing concerns

### 5. Metadata Access

IMetadataReader capability, poll from database, not storage.

- Single source of truth (database)
- Structured and parsed data
- Clear dependency: MetadataIndexer → other indexers
- No duplicate protobuf parsing

### 6. DummyIndexer Scope

No database writes, only observation and counting.

- Tests coordination infrastructure in isolation
- No schema complexity
- Fast test execution
- Clear separation of concerns (coordination vs. processing)

### 7. Exception-Based Claim Result Communication

Use checked `BatchAlreadyClaimedException` for claim conflicts.

- Type-safe and database-agnostic (no string matching on error messages)
- Exception signature documents expected behavior (competing consumer pattern)
- Uses database error codes (H2: 23505, PostgreSQL: 23505, MySQL: 1062)
- Robust across database versions and locales
- Clear separation: BatchAlreadyClaimedException = expected, other exceptions = errors

```java
// H2 implementation
if (e.getErrorCode() == 23505) {
    throw new BatchAlreadyClaimedException(batchFilename);
}
```

### 8. Separate Tables for Batches and Gaps

Two coordinator tables instead of overloading one table with mixed purposes.

**coordinator_batches:**
- Tracks which batch files have been processed
- PRIMARY KEY: (indexer_class, batch_filename)
- Enables multiple indexer types to process same batches independently

**coordinator_gaps:**
- Tracks missing tick ranges (not individual batches!)
- PRIMARY KEY: (indexer_class, gap_start_tick)
- Enables efficient gap search with tick-range queries

**Benefits:**
- Clean separation of concerns (processed vs. missing)
- No filename for gaps (they represent ranges, not files)
- Efficient queries (gaps by tick range, batches by filename)
- Scalable to variable batch sizes

### 9. Table Creation Strategy

Lazy creation with cached check: each capability creates its tables on first use.

```java
// In H2Database
private final Set<String> coordinationTablesInitialized = ConcurrentHashMap.newKeySet();

protected void doTryClaim(String indexerClass, ...) {
    String currentSchema = conn.getSchema();
    if (!coordinationTablesInitialized.contains(currentSchema)) {
        CREATE TABLE IF NOT EXISTS batch_processing (...);
        coordinationTablesInitialized.add(currentSchema);
    }
    // ... actual claim logic ...
}
```

- O(1) cache lookup per operation
- Thread-safe without locks
- Each capability manages its own tables
- Constant overhead regardless of batch count (~1.8ms per schema)
- IMetadataWriter creates `metadata` table in `doInsertMetadata`
- IBatchCoordinator creates `batch_processing` table in `doTryClaim`

## Monitoring Requirements

### Performance Contract: O(1) Metric Recording

**All monitoring operations MUST use O(1) data structures from `org.evochora.datapipeline.utils.monitoring`:**

```java
import org.evochora.datapipeline.utils.monitoring.PercentileTracker;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowPercentiles;
```

**Available Utilities:**

1. **PercentileTracker** - For latency percentile tracking without sliding window (p50, p95, p99)
   ```java
   private final PercentileTracker tryClaimLatency = new PercentileTracker();
   
   long startNanos = System.nanoTime();
   // ... operation ...
   long durationNanos = System.nanoTime() - startNanos;
   tryClaimLatency.record(durationNanos);  // O(1) - linear scan of 11 fixed buckets
   
   // Later in getMetrics():
   metrics.put("try_claim_latency_p50_ns", tryClaimLatency.getPercentile(50));  // O(1)
   metrics.put("try_claim_latency_p95_ns", tryClaimLatency.getPercentile(95));  // O(1)
   metrics.put("try_claim_latency_avg_ns", tryClaimLatency.getAverage());       // O(1)
   ```

2. **SlidingWindowPercentiles** - For latency percentiles over sliding time window
   ```java
   private final SlidingWindowPercentiles claimLatency = new SlidingWindowPercentiles(5);  // 5-second window
   
   long startNanos = System.nanoTime();
   // ... operation ...
   claimLatency.record(System.nanoTime() - startNanos);  // O(1)
   
   // Later in getMetrics():
   metrics.put("try_claim_latency_p95_ns", claimLatency.getPercentile(95));  // O(windowSeconds × buckets) = O(55) ≈ O(1)
   metrics.put("try_claim_latency_avg_ns", claimLatency.getAverage());       // O(5) ≈ O(1)
   ```

3. **SlidingWindowCounter** - For rate calculations (operations per second with moving window)
   ```java
   private final SlidingWindowCounter claimCounter = new SlidingWindowCounter(5);  // 5-second window
   
   claimCounter.recordCount();  // O(1) - increment for current second
   
   // Later in getMetrics():
   metrics.put("claims_per_sec", claimCounter.getRate());  // O(windowSeconds) = O(5) ≈ O(1)
   metrics.put("claims_window_total", claimCounter.getWindowSum());  // O(5) ≈ O(1)
   ```

4. **AtomicLong/AtomicInteger** - For simple cumulative counters
   ```java
   private final AtomicLong claimAttempts = new AtomicLong(0);
   
   claimAttempts.incrementAndGet();  // O(1) atomic operation
   
   // Later in getMetrics():
   metrics.put("claim_attempts_total", claimAttempts.get());  // O(1) read
   ```

### IMonitorable Implementation

**All services, resources, and wrappers MUST implement IMonitorable:**

```java
public interface IMonitorable {
    /**
     * Returns operational metrics.
     * MUST be O(1) - no iterations, aggregations, or blocking operations.
     */
    Map<String, Number> getMetrics();
    
    /**
     * Returns health status.
     * MUST be O(1) - simple flag checks only.
     */
    boolean isHealthy();
    
    /**
     * Returns recent operational errors (bounded list).
     * MUST be O(1) - pre-bounded collection.
     */
    List<OperationalError> getErrors();
    
    /**
     * Clears error history.
     */
    void clearErrors();
}
```

### Component Monitoring Requirements

#### 1. BatchCoordinatorWrapper

**Required Metrics (all O(1) or O(constant)):**
```java
// Cumulative counters (AtomicLong) - O(1)
metrics.put("claim_attempts", claimAttempts.get());
metrics.put("claim_successes", claimSuccesses.get());
metrics.put("claim_success_rate", /* calculated from counters */);
metrics.put("batches_completed", batchesCompleted.get());
metrics.put("batches_failed", batchesFailed.get());
metrics.put("gaps_pending", gapsPending.get());
metrics.put("gaps_released", gapsReleased.get());

// Latency percentiles (SlidingWindowPercentiles) - O(windowSeconds × buckets) = O(55) ≈ O(1)
metrics.put("try_claim_latency_p50_ms", tryClaimLatency.getPercentile(50) / 1_000_000.0);
metrics.put("try_claim_latency_p95_ms", tryClaimLatency.getPercentile(95) / 1_000_000.0);
metrics.put("try_claim_latency_p99_ms", tryClaimLatency.getPercentile(99) / 1_000_000.0);
metrics.put("try_claim_latency_avg_ms", tryClaimLatency.getAverage() / 1_000_000.0);
metrics.put("mark_completed_latency_p95_ms", markCompletedLatency.getPercentile(95) / 1_000_000.0);
metrics.put("has_gap_before_latency_p95_ms", hasGapBeforeLatency.getPercentile(95) / 1_000_000.0);

// Error tracking (bounded ConcurrentLinkedDeque) - O(1)
metrics.put("error_count", errors.size());
```

**Implementation Pattern:**

Full implementation shown in "Wrapper Implementations" section above. Key patterns:

```java
class BatchCoordinatorWrapper implements IBatchCoordinator, IWrappedResource, IMonitorable {
    
    // Counters - O(1)
    private final AtomicLong claimAttempts = new AtomicLong(0);
    
    // Latency with sliding window - O(1) recording, O(windowSeconds × buckets) retrieval
    private final SlidingWindowPercentiles tryClaimLatency;
    
    // Bounded errors - O(1) amortized
    private final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();
    private static final int MAX_ERRORS = 100;
    
    BatchCoordinatorWrapper(AbstractDatabaseResource db, ResourceContext context) {
        // Get window from resource config
        int windowSeconds = db.getOptions().hasPath("metricsWindowSeconds") 
            ? db.getOptions().getInt("metricsWindowSeconds") : 5;
        
        this.tryClaimLatency = new SlidingWindowPercentiles(windowSeconds);
    }
    
    @Override
    public boolean tryClaim(...) {
        long startNanos = System.nanoTime();
        claimAttempts.incrementAndGet();
        
        try {
            database.doTryClaim(...);
            tryClaimLatency.record(System.nanoTime() - startNanos);  // nanoseconds
            return true;
        } catch (Exception e) {
            recordError(...);
            throw e;
        }
    }
    
    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new LinkedHashMap<>();
        
        metrics.put("claim_attempts", claimAttempts.get());
        
        // Convert nanoseconds to milliseconds
        metrics.put("try_claim_latency_p95_ms", tryClaimLatency.getPercentile(95) / 1_000_000.0);
        
        return metrics;
    }
}
```

#### 2. MetadataReaderWrapper

**Required Metrics (all O(1) or O(constant)):**
```java
// Operation counters - O(1)
metrics.put("metadata_reads", metadataReads.get());
metrics.put("metadata_not_found", metadataNotFound.get());
metrics.put("read_errors", readErrors.get());

// Latency percentiles (SlidingWindowPercentiles) - O(windowSeconds × buckets) = O(55) ≈ O(1)
metrics.put("get_metadata_latency_p50_ms", getMetadataLatency.getPercentile(50) / 1_000_000.0);
metrics.put("get_metadata_latency_p95_ms", getMetadataLatency.getPercentile(95) / 1_000_000.0);
metrics.put("get_metadata_latency_p99_ms", getMetadataLatency.getPercentile(99) / 1_000_000.0);
metrics.put("get_metadata_latency_avg_ms", getMetadataLatency.getAverage() / 1_000_000.0);
metrics.put("has_metadata_latency_p95_ms", hasMetadataLatency.getPercentile(95) / 1_000_000.0);

// Error tracking - O(1)
metrics.put("error_count", errors.size());
```

#### 3. AbstractBatchProcessingIndexer

**Required Metrics (all O(1), delegated to components):**
```java
// From TickBufferingComponent (if present)
if (buffering != null) {
    metrics.put("current_buffer_size", buffering.getCurrentBufferSize());
    metrics.put("claimed_batches_pending", buffering.getClaimedBatchCount());
}

// From GapDetectionComponent (if present)
if (gapDetection != null) {
    metrics.put("permanent_gaps_detected", gapDetection.getPermanentGapsDetected());
}
```

#### 4. Indexer Components

**Components do NOT implement IMonitorable** - they expose simple getters for metrics.
AbstractBatchProcessingIndexer aggregates component metrics in its getMetrics() method.

**MetadataReadingComponent:**
- No metrics (simple state holder)

**BatchCoordinationComponent:**
- No metrics (delegates to IBatchCoordinatorReady which has IMonitorable)

**GapDetectionComponent:**
```java
public long getPermanentGapsDetected() { return permanentGapsDetected.get(); }  // O(1)
```

**TickBufferingComponent:**
```java
public int getCurrentBufferSize() { return tickBuffer.size(); }      // O(1)
public int getClaimedBatchCount() { return claimedBatches.size(); }  // O(1)
```

#### 5. DummyIndexer

**Required Metrics (all O(1)):**
```java
// Processing counters
metrics.put("batches_processed", batchesProcessed.get());
metrics.put("ticks_processed", ticksProcessed.get());
metrics.put("cells_observed", cellsObserved.get());
metrics.put("organisms_observed", organismsObserved.get());

// Inherited from AbstractBatchProcessingIndexer (component metrics)
metrics.putAll(super.getMetrics());
```

#### 6. H2Database Extensions

**Required Metrics (all O(1) or O(constant)):**
```java
// Base metrics (from AbstractDatabaseResource) - O(1)
metrics.put("queries_executed", queriesExecuted.get());
metrics.put("rows_inserted", rowsInserted.get());
metrics.put("write_errors", writeErrors.get());
metrics.put("read_errors", readErrors.get());

// H2-specific metrics (via addCustomMetrics() hook)
// Connection pool (O(1) via HikariCP MXBean)
metrics.put("h2_active_connections", hikariPool.getActiveConnections());
metrics.put("h2_idle_connections", hikariPool.getIdleConnections());
metrics.put("h2_pool_total_connections", hikariPool.getTotalConnections());

// Cache metrics (O(1) atomic reads)
metrics.put("h2_cache_hits", cacheHits.get());
metrics.put("h2_cache_misses", cacheMisses.get());
metrics.put("h2_cache_hit_ratio", /* calculated from counters */);

// Rate metrics (O(windowSeconds) via SlidingWindowCounter)
metrics.put("h2_queries_per_sec", queriesCounter.getRate());  // O(5) ≈ O(1)
metrics.put("h2_disk_writes_per_sec", diskWritesCounter.getRate());  // O(5) ≈ O(1)
```

### Monitoring Anti-Patterns (FORBIDDEN)

**Never do these - they violate O(1) contract:**

❌ **Iterating over large collections:**
```java
// BAD - O(N) iteration
long sum = 0;
for (TickData tick : allProcessedTicks) {
    sum += tick.getCellsCount();
}
```

❌ **Database queries in getMetrics():**
```java
// BAD - Blocking I/O
int count = executeQuery("SELECT COUNT(*) FROM batches");
```

❌ **Complex aggregations:**
```java
// BAD - O(N) aggregation
double avg = allLatencies.stream().mapToDouble(d -> d).average().orElse(0);
```

❌ **Unbounded error lists:**
```java
// BAD - Unbounded memory growth
errors.add(new OperationalError(...));  // Never trim!
```

✅ **Correct patterns:**
```java
// GOOD - O(1) counter increment
ticksProcessed.incrementAndGet();

// GOOD - O(1) latency recording (linear scan of 11 fixed buckets)
latencyTracker.record(durationNanos);

// GOOD - O(1) rate counter recording
rateCounter.recordCount();

// GOOD - Bounded error list
errors.add(new OperationalError(...));
while (errors.size() > MAX_ERRORS) {  // O(1) amortized
    errors.pollFirst();
}
```

### Testing Monitoring

**All monitoring tests must verify O(1) contract:**

```java
@Test
void testMetrics_AreConstantTime() {
    // Setup: Process 1000 items
    for (int i = 0; i < 1000; i++) {
        indexer.processSomething();
    }
    
    // Measure getMetrics() time
    long start = System.nanoTime();
    Map<String, Number> metrics = indexer.getMetrics();
    long duration = System.nanoTime() - start;
    
    // Verify: O(1) - should complete in < 1ms even with 1000 items
    assertTrue(duration < 1_000_000, "getMetrics() took too long: " + duration + "ns");
}

@Test
void testMetrics_NoDatabaseCalls() {
    // Mock database connection to verify no queries during getMetrics()
    Connection mockConn = mock(Connection.class);
    
    wrapper.getMetrics();
    
    // Verify: No database interactions
    verifyNoInteractions(mockConn);
}
```

### Metrics Export

**All metrics are available for export to external systems:**

```java
// Prometheus format example
public String toPrometheusFormat() {
    StringBuilder sb = new StringBuilder();
    
    for (Map.Entry<String, Number> entry : getMetrics().entrySet()) {
        String metricName = entry.getKey().replace("_", ":");
        sb.append("evochora_indexer_").append(metricName)
          .append(" ").append(entry.getValue()).append("\n");
    }
    
    return sb.toString();
}

// Example output:
// evochora_indexer_claim:attempts 12345
// evochora_indexer_claim:successes 12000
// evochora_indexer_try:claim:latency:p95 42.5
```

## Logging Strategy

### Principles

**❌ NEVER:**
- INFO in loops (too noisy - use DEBUG)
- Multi-line log statements
- Phase/version prefixes in production code
- Redundant information already in metrics
- Log successful operations that metrics track

**✅ ALWAYS:**
- INFO very sparingly (start/stop, completion summaries only)
- DEBUG for repetitive operations in loops
- Single line per event
- Include essential context (runId, critical values)

### Log Levels

**INFO:**
- Service lifecycle (start/stop)
- Processing completed with summary
- Critical failures

**DEBUG:**
- Batch claimed/discovered
- Gap detected/filled
- Buffer operations
- Flush triggers
- Metadata loaded

**WARN:**
- Permanent gaps (once per gap)
- Batch failures

**ERROR:**
- Fatal errors
- Coordination failures

### Examples

```java
// ✅ GOOD - INFO for completion summary
log.info("Processing completed: runId={}, ticks={}, cells={}, organisms={}, flushes={}", 
         runId, ticksProcessed, cellsObserved, organismsObserved, flushCount);

// ✅ GOOD - DEBUG in loop
log.debug("Claimed batch: {} (ticks {}-{})", filename, tickStart, tickEnd);
log.debug("Gap detected: [{}, {})", gapStart, gapEnd);
log.debug("Buffered {} ticks from {}, buffer size: {}", tickCount, filename, bufferSize);

// ✅ GOOD - WARN for permanent gap (once)
log.warn("Permanent gap (data lost): [{}, {})", gapStart, gapEnd);

// ❌ BAD - INFO in loop
log.info("Claimed batch: {}", filename);
log.info("Gap detected before batch: {}", filename);

// ❌ BAD - Multi-line
log.info("Processing completed:");
log.info("  - Ticks: {}", ticks);
log.info("  - Cells: {}", cells);

// ❌ BAD - Phase prefix
log.debug("Claimed batch: {}", filename);

// ❌ BAD - Redundant (metrics track this)
log.info("Batch already claimed, skipping");
log.debug("Marked batch completed: {}", filename);
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
  - Example: `/** Implements {@link IMetadataReader#getMetadata(String)}. */` or `/** Implements {@link IBatchCoordinatorReady#tryClaim(String, long, long)}. */`

Examples shown in code sections throughout this specification demonstrate proper JavaDoc structure.

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

**Example:**
```java
@ExtendWith(LogWatchExtension.class)
@Tag("integration")
class MyTest {
    
    @AfterEach
    void verifyNoLeaks() {
        assertEquals(0, testDatabase.getMetrics().get("h2_pool_active_connections"),
            "Connection leak detected");
    }
    
    @Test
    void testNormalOperation() {
        // Use Awaitility instead of Thread.sleep - NO Thread.sleep EVER!
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> someCondition());
        
        // DEBUG and INFO logs don't need @AllowLog or @ExpectLog
    }
    
    @Test
    @ExpectLog(logger = "org.evochora.MyService",
               level = WARN, message = "Something went wrong: runId=*")
    void testWarning() {
        // WARN and ERROR must use @ExpectLog with specific message pattern
        // Wildcards only for variable parts (runId, filenames, etc.)
    }
}
```

## Future Extensions

### EnvironmentIndexer (Phase 2.6)

Uses all components like DummyIndexer, but writes environment data:

```java
public class EnvironmentIndexer extends AbstractBatchProcessingIndexer {
    private final IEnvironmentDatabase database;
    
    public EnvironmentIndexer(...) {
        super(...);
        
        // Same components as DummyIndexer
        this.metadata = new MetadataReadingComponent(...);
        this.coordination = new BatchCoordinationComponent(rawCoordinator, this.getClass().getName(), ...);
        this.gapDetection = new GapDetectionComponent(metadata, coordination, storage, ...);
        this.buffering = new TickBufferingComponent(...);
        
        // Additional: database for writes
        this.database = getRequiredResource(IEnvironmentDatabase.class, "db-env-write");
    }
    
    @Override
    protected void processBatch(List<TickData> ticks) {
        database.insertCells(ticks);  // Actual environment data processing
    }
}
```

### OrganismIndexer (Phase 2.7)

Same pattern with different database capability:

```java
public class OrganismIndexer extends AbstractBatchProcessingIndexer {
    private final IOrganismDatabase database;
    
    public OrganismIndexer(...) {
        // Components setup identical to EnvironmentIndexer
        // Only processBatch() differs
    }
    
    @Override
    protected void processBatch(List<TickData> ticks) {
        database.insertOrganisms(ticks);
    }
}
```

### Single-Instance Aggregator (Future)

Example of indexer without coordination (no competing consumers):

```java
public class StatisticsAggregator extends AbstractBatchProcessingIndexer {
    public StatisticsAggregator(...) {
        super(...);
        
        // Only buffering, no coordination or gap detection
        this.buffering = new TickBufferingComponent(...);
        // coordination = null → batch loop skips claiming
        // gapDetection = null → batch loop processes all batches sequentially
    }
    
    @Override
    protected void processBatch(List<TickData> ticks) {
        aggregateStatistics(ticks);
    }
}
```

### Snapshot+Delta Mode (Future)

New components for future snapshot+delta mode:

```java
public class SnapshotDeltaIndexer extends AbstractBatchProcessingIndexer {
    public SnapshotDeltaIndexer(...) {
        super(...);
        
        // NEW components for snapshot mode
        this.stateManagement = new SnapshotStateManagement(...);
        this.snapshotGapDetection = new SnapshotGapDetection(...); // Different gap logic
        
        // Reuse existing components
        this.coordination = new BatchCoordinationComponent(...);
        this.buffering = new TickBufferingComponent(...);
    }
}
```

### Cloud Deployment

Same code, different resources:
```hocon
# Development
coordinator-resource = "db-coordinator:h2-local"

# Production
coordinator-resource = "db-coordinator:postgres-cloud"
```

---

**Status:** Ready for implementation.

