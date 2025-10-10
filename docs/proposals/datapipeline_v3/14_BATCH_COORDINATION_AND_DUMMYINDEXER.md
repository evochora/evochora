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
- Phase 2.2: Storage Resource (completed) - IBatchStorageRead interface with listBatchFiles
- Phase 2.3: Persistence Service (completed) - Writes batch files with variable sizes
- Phase 2.4: Database Resource and Metadata Indexer (completed) - Metadata in database, IMetadataWriter
- SimulationMetadata includes `sampling_interval` field (completed) - Required for gap detection

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
t=0: PersistenceService-1 claims ticks 0-999, writing...
     PersistenceService-2 claims ticks 1000-1999, writes fast!
     
t=1: Storage has: batch_0000001000_0000001999.pb

Indexer discovers batch_1000-1999:
  → hasGapBefore(1000)? YES (batch 0-999 missing)
  → markGapPending(batch_1000-1999)
  → Continue processing other batches

t=5: Storage has: batch_0000000000_0000000999.pb
                  batch_0000001000_0000001999.pb

Indexer discovers batch_0-999:
  → hasGapBefore(0)? NO (first batch)
  → tryClaim → SUCCESS → process
  → markCompleted(batch_0-999)
  → releaseGapPendingBatches(999)
     → Checks: batch_1000-1999 had gap before 1000, now filled!
     → Update status: 'gap_pending' → 'ready'
     
Next iteration:
  → Discovers batch_1000-1999 with status 'ready'
  → hasGapBefore(1000)? NO (batch 0-999 completed)
  → tryClaim → SUCCESS → process
```

### Permanent Gap Detection

```
t=0: Indexer finds batch_1000-1999, marks as gap_pending
t=60: Gap still pending, logs WARNING, increments permanentGapsDetected counter
      Pipeline continues running (data incomplete but operational)
```

## Package Structure

```
org.evochora.datapipeline.api.resources.database/
  - ISchemaAwareDatabase.java      # Base interface for schema-aware operations (NEW)
  - IMetadataWriter.java           # Write metadata (existing, renamed, extends ISchemaAwareDatabase)
  - IMetadataReader.java           # Read metadata (NEW, extends ISchemaAwareDatabase)
  - IBatchCoordinator.java         # Batch coordination (NEW, extends ISchemaAwareDatabase)
  - MetadataNotFoundException.java # Exception for metadata not found (NEW)
  - BatchAlreadyClaimedException.java # Exception for duplicate claims (NEW)

org.evochora.datapipeline.resources.database/
  - AbstractDatabaseResource.java  # Base class (existing, extend)
  - H2Database.java                # H2 implementation (existing, extend)
  - MetadataWriterWrapper.java     # IMetadataWriter wrapper (existing, renamed)
  - MetadataReaderWrapper.java     # IMetadataReader wrapper (NEW)
  - BatchCoordinatorWrapper.java   # IBatchCoordinator wrapper (NEW)

org.evochora.datapipeline.services.indexers/
  - AbstractIndexer.java           # Base indexer (existing, extend significantly)
  - MetadataIndexer.java           # Metadata indexing (existing, unchanged)
  - DummyIndexer.java              # Test indexer (NEW)
```

## Database Capability Interfaces

### ISchemaAwareDatabase

Base interface for all database capabilities that operate within a simulation run schema:

```java
/**
 * Base capability for database operations that work within a simulation run schema.
 * <p>
 * All database capabilities that operate on run-specific data extend this interface.
 * The schema is set once per wrapper instance by AbstractIndexer after run discovery.
 * <p>
 * <strong>Lifecycle:</strong>
 * <ol>
 *   <li>AbstractIndexer discovers runId</li>
 *   <li>AbstractIndexer calls setSimulationRun(runId) on ALL ISchemaAwareDatabase resources</li>
 *   <li>All subsequent operations on these wrappers work within the set schema</li>
 * </ol>
 */
public interface ISchemaAwareDatabase extends IResource {
    /**
     * Sets the active database schema for this wrapper's connection.
     * <p>
     * Must be called once before any schema-specific operations.
     * AbstractIndexer calls this automatically for all database resources after run discovery.
     * <p>
     * Implementation executes: SET SCHEMA schema_name
     * 
     * @param simulationRunId Raw simulation run ID (sanitized internally to schema name)
     */
    void setSimulationRun(String simulationRunId);
}
```

### IBatchCoordinator

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
     * Marks a batch as pending due to gap before it.
     * <p>
     * Updates status='gap_pending', records first_gap_detection_timestamp if not set.
     * Gap-pending batches are not claimable until gap is filled.
     *
     * @param batchFilename Storage filename
     * @param tickStart First tick number in batch
     * @param tickEnd Last tick number in batch
     */
    void markGapPending(String batchFilename, long tickStart, long tickEnd);
    
    /**
     * Checks if there is a gap before the given batch start tick for this indexer.
     * <p>
     * Uses deterministic formula based on samplingInterval:
     * <pre>
     * previousBatch = find max(tick_end) WHERE tick_end < batchStartTick AND indexer_class = [this]
     * expectedNextStart = previousBatch.tick_end + samplingInterval
     * return batchStartTick != expectedNextStart
     * </pre>
     * <p>
     * Returns false if no previous batch exists (first batch of run for this indexer).
     *
     * @param batchStartTick First tick number of batch being checked
     * @param samplingInterval Sampling interval from simulation metadata
     * @return true if gap detected, false if sequential or first batch
     */
    boolean hasGapBefore(long batchStartTick, int samplingInterval);
    
    /**
     * Gets timestamp when a gap was first detected for this indexer.
     * <p>
     * Returns the first_gap_detection_timestamp for any gap_pending batch where
     * the gap is before batchStartTick for this indexer class.
     *
     * @param batchStartTick Tick number to check
     * @return Timestamp when gap was first detected, or null if no gap pending
     */
    Instant getGapPendingSince(long batchStartTick);
    
    /**
     * Releases gap-pending batches for this indexer that are now processable.
     * <p>
     * After completing a batch with tick_end, checks all gap_pending batches for this
     * indexer class to see if their gaps are now filled. Updates status: 'gap_pending' → 'ready'.
     * <p>
     * Called automatically by indexers after markCompleted().
     *
     * @param completedTickEnd The tick_end of the batch just completed
     * @param samplingInterval Sampling interval from metadata
     */
    void releaseGapPendingBatches(long completedTickEnd, int samplingInterval);
    
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
```

## Database Schema Extensions

### batch_processing Table

**Creation:** Lazy creation in `H2Database.doTryClaim()` with cached check (see "Schema Initialization Cache" section).

**Schema Definition:**

```sql
CREATE TABLE IF NOT EXISTS batch_processing (
  indexer_class VARCHAR NOT NULL,
  batch_filename VARCHAR NOT NULL,
  tick_start BIGINT NOT NULL,
  tick_end BIGINT NOT NULL,
  indexer_instance_id VARCHAR,
  claim_timestamp TIMESTAMP,
  completion_timestamp TIMESTAMP,
  first_gap_detection_timestamp TIMESTAMP,
  status VARCHAR NOT NULL,
  error_message VARCHAR,
  
  PRIMARY KEY (indexer_class, batch_filename),
  
  CONSTRAINT check_status CHECK (status IN ('claimed', 'completed', 'failed', 'gap_pending', 'ready')),
  
  INDEX idx_tick_range (tick_start, tick_end),
  INDEX idx_status (status),
  INDEX idx_tick_end (tick_end)
);
```

**Columns:**
- `indexer_class`: Fully qualified class name (e.g., `org.evochora.datapipeline.services.indexers.DummyIndexer`)
- `batch_filename`: Batch file name (e.g., `runId/tickdata/batch_0000000000_0000000999.pb`)
- Composite PRIMARY KEY ensures each indexer type processes each batch independently
- Multiple indexer types can process the same batch (DummyIndexer, EnvironmentIndexer, etc.)
- Competing consumers only within the same indexer class

**Status Values:**
- `claimed`: Batch currently being processed by an indexer
- `completed`: Batch successfully processed
- `failed`: Batch processing failed (error_message contains details)
- `gap_pending`: Batch discovered but has gap before it
- `ready`: Gap-pending batch whose gap has been filled

**Note:** The `batch_processing` table exists within each simulation run's schema (created by MetadataIndexer via `createSimulationRun()`). Therefore, no `simulation_run_id` column is needed - the schema itself provides run isolation.

## AbstractDatabaseResource Extensions

AbstractDatabaseResource must be extended with new abstract methods for coordination and metadata reading:

```java
// Coordination methods (NEW)
protected abstract void doTryClaim(Object connection, String indexerClass, String batchFilename, 
                                   long tickStart, long tickEnd, String indexerInstanceId) 
    throws BatchAlreadyClaimedException, Exception;
protected abstract void doMarkCompleted(Object connection, String indexerClass, String batchFilename) throws Exception;
protected abstract void doMarkFailed(Object connection, String indexerClass, String batchFilename, String errorMessage) throws Exception;
protected abstract void doMarkGapPending(Object connection, String indexerClass, String batchFilename, long tickStart, long tickEnd) throws Exception;
protected abstract boolean doHasGapBefore(Object connection, String indexerClass, long batchStartTick, int samplingInterval) throws Exception;
protected abstract Instant doGetGapPendingSince(Object connection, String indexerClass, long batchStartTick) throws Exception;
protected abstract void doReleaseGapPendingBatches(Object connection, String indexerClass, long completedTickEnd, int samplingInterval) throws Exception;

// Metadata reading methods (NEW)
protected abstract SimulationMetadata doGetMetadata(Object connection, String simulationRunId) throws Exception;
protected abstract boolean doHasMetadata(Object connection, String simulationRunId) throws Exception;
```

These methods are called by their respective wrappers (BatchCoordinatorWrapper, MetadataReaderWrapper) and implemented by concrete database classes (H2Database, PostgreSQLDatabase, etc.).

## H2Database Extensions

### Schema Initialization Cache

**Purpose:** Avoid repeated `CREATE TABLE IF NOT EXISTS` overhead for coordination tables.

**Implementation:**
```java
// In H2Database.java (class field)
private final Set<String> coordinationTablesInitialized = ConcurrentHashMap.newKeySet();
```

**Performance:**
- Metadata table: Created in `doInsertMetadata()` (1× per run, no caching needed)
- Coordination table: Created in `doTryClaim()` with cached check (1× per schema, then O(1) lookups)

### Coordination Methods

```java
// In H2Database.java

@Override
protected void doTryClaim(Object connection, String indexerClass, String batchFilename, 
                          long tickStart, long tickEnd, String indexerInstanceId) 
                          throws BatchAlreadyClaimedException, Exception {
    Connection conn = (Connection) connection;
    
    // Ensure batch_processing table exists (lazy creation with cache)
    String currentSchema = conn.getSchema();
    if (!coordinationTablesInitialized.contains(currentSchema)) {
        conn.createStatement().execute(
            "CREATE TABLE IF NOT EXISTS batch_processing (" +
            "  indexer_class VARCHAR(255) NOT NULL," +
            "  batch_filename VARCHAR(255) NOT NULL," +
            "  tick_start BIGINT NOT NULL," +
            "  tick_end BIGINT NOT NULL," +
            "  indexer_instance_id VARCHAR(255) NOT NULL," +
            "  claim_timestamp TIMESTAMP NOT NULL," +
            "  completion_timestamp TIMESTAMP," +
            "  first_gap_detection_timestamp TIMESTAMP," +
            "  status VARCHAR(20) NOT NULL," +
            "  error_message TEXT," +
            "  PRIMARY KEY (indexer_class, batch_filename)," +
            "  CONSTRAINT check_status CHECK (status IN ('claimed', 'completed', 'failed', 'gap_pending', 'ready'))," +
            "  INDEX idx_tick_range (tick_start, tick_end)," +
            "  INDEX idx_status (status)," +
            "  INDEX idx_tick_end (tick_end)" +
            ")"
        );
        conn.commit();
        coordinationTablesInitialized.add(currentSchema);
    }
    
    try {
        // Atomic INSERT with composite primary key (indexer_class, batch_filename)
        PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO batch_processing " +
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
        "UPDATE batch_processing " +
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
        "UPDATE batch_processing " +
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
protected void doMarkGapPending(Object connection, String indexerClass, String batchFilename, long tickStart, long tickEnd) throws Exception {
    Connection conn = (Connection) connection;
    
    // Use MERGE to handle race condition where multiple indexers detect same gap
    PreparedStatement stmt = conn.prepareStatement(
        "MERGE INTO batch_processing " +
        "(indexer_class, batch_filename, tick_start, tick_end, first_gap_detection_timestamp, status) " +
        "KEY(indexer_class, batch_filename) " +
        "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, 'gap_pending')"
    );
    
    stmt.setString(1, indexerClass);
    stmt.setString(2, batchFilename);
    stmt.setLong(3, tickStart);
    stmt.setLong(4, tickEnd);
    stmt.executeUpdate();
    conn.commit();
    
    queriesExecuted.incrementAndGet();
}

@Override
protected boolean doHasGapBefore(Object connection, String indexerClass, long batchStartTick, int samplingInterval) throws Exception {
    Connection conn = (Connection) connection;
    
    if (batchStartTick == 0) {
        return false; // First batch, no gap possible
    }
    
    // Find previous batch for this indexer class
    PreparedStatement stmt = conn.prepareStatement(
        "SELECT tick_end FROM batch_processing " +
        "WHERE indexer_class = ? " +
        "AND tick_end < ? " +
        "AND status IN ('completed', 'claimed', 'gap_pending', 'ready') " +
        "ORDER BY tick_end DESC LIMIT 1"
    );
    stmt.setString(1, indexerClass);
    stmt.setLong(2, batchStartTick);
    ResultSet rs = stmt.executeQuery();
    
    if (!rs.next()) {
        return false; // No previous batch found, this is first batch for this indexer
    }
    
    long previousTickEnd = rs.getLong("tick_end");
    long expectedNextStart = previousTickEnd + samplingInterval;
    
    queriesExecuted.incrementAndGet();
    
    return batchStartTick != expectedNextStart;
}

@Override
protected Instant doGetGapPendingSince(Object connection, String indexerClass, long batchStartTick) throws Exception {
    Connection conn = (Connection) connection;
    
    // Find any gap_pending batch for this indexer class that has a gap before batchStartTick
    PreparedStatement stmt = conn.prepareStatement(
        "SELECT first_gap_detection_timestamp FROM batch_processing " +
        "WHERE indexer_class = ? " +
        "AND status = 'gap_pending' " +
        "AND tick_start >= ? " +
        "ORDER BY tick_start ASC LIMIT 1"
    );
    stmt.setString(1, indexerClass);
    stmt.setLong(2, batchStartTick);
    ResultSet rs = stmt.executeQuery();
    
    queriesExecuted.incrementAndGet();
    
    if (rs.next()) {
        Timestamp ts = rs.getTimestamp("first_gap_detection_timestamp");
        return ts != null ? ts.toInstant() : null;
    }
    return null;
}

@Override
protected void doReleaseGapPendingBatches(Object connection, String indexerClass, long completedTickEnd, int samplingInterval) throws Exception {
    Connection conn = (Connection) connection;
    
    // Find gap_pending batches for this indexer class that can now be processed
    // Their expected previous tick is completedTickEnd
    long expectedNextStart = completedTickEnd + samplingInterval;
    
    PreparedStatement stmt = conn.prepareStatement(
        "UPDATE batch_processing " +
        "SET status = 'ready' " +
        "WHERE indexer_class = ? " +
        "AND status = 'gap_pending' " +
        "AND tick_start = ?"
    );
    stmt.setString(1, indexerClass);
    stmt.setLong(2, expectedNextStart);
    int updatedRows = stmt.executeUpdate();
    conn.commit();
    
    queriesExecuted.incrementAndGet();
    
    if (updatedRows > 0) {
        log.debug("Released {} gap-pending batches for {} after completing tick {}", 
                  updatedRows, indexerClass, completedTickEnd);
    }
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
    private final SlidingWindowPercentiles markGapPendingLatency;
    private final SlidingWindowPercentiles hasGapBeforeLatency;
    private final SlidingWindowPercentiles releaseGapPendingLatency;
    
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
        this.markGapPendingLatency = new SlidingWindowPercentiles(windowSeconds);
        this.hasGapBeforeLatency = new SlidingWindowPercentiles(windowSeconds);
        this.releaseGapPendingLatency = new SlidingWindowPercentiles(windowSeconds);
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
    public void markGapPending(String batchFilename, long tickStart, long tickEnd) {
        ensureIndexerClassSet();
        long startNanos = System.nanoTime();
        
        try {
            database.doMarkGapPending(dedicatedConnection, indexerClass, batchFilename, tickStart, tickEnd);
            gapsPending.incrementAndGet();
            markGapPendingLatency.record(System.nanoTime() - startNanos);
            
        } catch (Exception e) {
            recordError("MARK_GAP_PENDING_FAILED", "Failed to mark batch as gap pending",
                       "Batch: " + batchFilename + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to mark gap pending: " + batchFilename, e);
        }
    }
    
    @Override
    public boolean hasGapBefore(long batchStartTick, int samplingInterval) {
        ensureIndexerClassSet();
        long startNanos = System.nanoTime();
        
        try {
            boolean hasGap = database.doHasGapBefore(dedicatedConnection, indexerClass, batchStartTick, samplingInterval);
            hasGapBeforeLatency.record(System.nanoTime() - startNanos);
            
            return hasGap;
            
        } catch (Exception e) {
            recordError("HAS_GAP_BEFORE_FAILED", "Failed to check for gap",
                       "TickStart: " + batchStartTick + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to check gap before tick: " + batchStartTick, e);
        }
    }
    
    @Override
    public Instant getGapPendingSince(long batchStartTick) {
        ensureIndexerClassSet();
        try {
            return database.doGetGapPendingSince(dedicatedConnection, indexerClass, batchStartTick);
        } catch (Exception e) {
            recordError("GET_GAP_PENDING_SINCE_FAILED", "Failed to get gap pending timestamp",
                       "TickStart: " + batchStartTick + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to get gap pending since: " + batchStartTick, e);
        }
    }
    
    @Override
    public void releaseGapPendingBatches(long completedTickEnd, int samplingInterval) {
        ensureIndexerClassSet();
        long startNanos = System.nanoTime();
        
        try {
            database.doReleaseGapPendingBatches(dedicatedConnection, indexerClass, completedTickEnd, samplingInterval);
            gapsReleased.incrementAndGet();
            releaseGapPendingLatency.record(System.nanoTime() - startNanos);
            
        } catch (Exception e) {
            recordError("RELEASE_GAP_PENDING_FAILED", "Failed to release gap pending batches",
                       "CompletedTickEnd: " + completedTickEnd + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to release gap pending batches", e);
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

## AbstractIndexer Extensions

### New Fields and Configuration

```java
public abstract class AbstractIndexer extends AbstractService {
    
    // Existing fields
    protected final IBatchStorageRead storage;
    protected final Config indexerOptions;
    private final String configuredRunId;
    private final int pollIntervalMs;
    private final int maxPollDurationMs;
    private final Instant indexerStartTime;
    
    // NEW: Coordination and metadata
    protected final IBatchCoordinator coordinator;
    protected final IMetadataReader metadataReader;
    private SimulationMetadata cachedMetadata;
    private int samplingInterval;
    
    // NEW: Tick buffering
    private final List<TickData> tickBuffer = new ArrayList<>();
    private final List<String> claimedBatches = new ArrayList<>();
    private final int insertBatchSize;
    private final long flushTimeoutMs;
    private long lastBatchClaimTime;
    
    // NEW: Gap detection
    private final long gapWarningTimeoutMs;
    private final AtomicLong permanentGapsDetected = new AtomicLong(0);
    
    protected AbstractIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        
        this.storage = getRequiredResource("storage", IBatchStorageRead.class);
        this.coordinator = getRequiredResource("coordinator", IBatchCoordinator.class);
        this.metadataReader = getRequiredResource("metadataReader", IMetadataReader.class);
        
        this.indexerOptions = options;
        
        // Run discovery config
        this.configuredRunId = options.hasPath("runId") ? options.getString("runId") : null;
        this.pollIntervalMs = options.hasPath("pollIntervalMs") ? options.getInt("pollIntervalMs") : 1000;
        this.maxPollDurationMs = options.hasPath("maxPollDurationMs") ? options.getInt("maxPollDurationMs") : 300000;
        this.indexerStartTime = Instant.now();
        
        // NEW: Batch processing config
        this.insertBatchSize = options.hasPath("insertBatchSize") ? options.getInt("insertBatchSize") : 1000;
        this.flushTimeoutMs = options.hasPath("flushTimeoutMs") ? options.getLong("flushTimeoutMs") : 5000;
        
        // NEW: Gap detection config
        this.gapWarningTimeoutMs = options.hasPath("gapWarningTimeoutMs") ? 
            options.getLong("gapWarningTimeoutMs") : 60000; // 1 minute default
        
        if (insertBatchSize <= 0) {
            throw new IllegalArgumentException("insertBatchSize must be positive");
        }
        if (flushTimeoutMs <= 0) {
            throw new IllegalArgumentException("flushTimeoutMs must be positive");
        }
    }
    
    // Modified: discoverRunId now handles schema preparation
    protected String discoverRunId() throws Exception {
        // 1. Discover runId (configured or timestamp-based)
        String runId = /* existing discovery logic */;
        
        // 2. Prepare schema (template method hook - default no-op, MetadataIndexer creates schema)
        prepareSchema(runId);
        
        // 3. Set schema for all database resources
        setSchemaForAllDatabaseResources(runId);
        
        return runId;
    }
    
    /**
     * Template method hook for schema preparation (e.g., schema creation).
     * This is called by discoverRunId() after the runId is discovered but before
     * setSchemaForAllDatabaseResources() is called.
     * <p>
     * Default implementation does nothing. Override to perform schema setup tasks
     * (e.g., MetadataIndexer overrides this to create the schema).
     *
     * @param runId the simulation run ID
     * @throws Exception if schema preparation fails
     */
    protected void prepareSchema(String runId) throws Exception {
        // Default: no-op
    }
    
    // NEW: Wait for metadata to be indexed
    protected SimulationMetadata pollForMetadata(String runId) throws InterruptedException, TimeoutException {
        log.debug("Waiting for metadata to be indexed: runId={}", runId);
        
        long startTime = System.currentTimeMillis();
        
        while (!Thread.currentThread().isInterrupted()) {
            try {
                SimulationMetadata metadata = metadataReader.getMetadata(runId);
                log.info("Metadata available for runId={}", runId);
                return metadata;
                
            } catch (MetadataNotFoundException e) {
                // Expected - metadata not yet indexed
                
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
    
    // NEW: Parse batch filename to extract tick range
    protected long parseBatchStartTick(String batchFilename) {
        // Filename format: "runId/subfolder/batch_0000000000_0000000999.pb"
        // Extract start tick from "batch_XXXXX_YYYYY"
        String basename = batchFilename.substring(batchFilename.lastIndexOf('/') + 1);
        String[] parts = basename.split("_");
        if (parts.length >= 2) {
            return Long.parseLong(parts[1]);
        }
        throw new IllegalArgumentException("Invalid batch filename format: " + batchFilename);
    }
    
    protected long parseBatchEndTick(String batchFilename) {
        String basename = batchFilename.substring(batchFilename.lastIndexOf('/') + 1);
        String[] parts = basename.split("_");
        if (parts.length >= 3) {
            // Remove extension (.pb, .pb.zst, etc.)
            String endTickStr = parts[2].replaceAll("\\.[^.]+$", "");
            return Long.parseLong(endTickStr);
        }
        throw new IllegalArgumentException("Invalid batch filename format: " + batchFilename);
    }
    
    /**
     * Sets the schema for all ISchemaAwareDatabase resources.
     * <p>
     * Called automatically by discoverRunId() after prepareSchema().
     * Ensures all database wrappers (coordinator, metadata reader, etc.)
     * operate within the correct simulation run schema.
     *
     * @param runId The simulation run ID
     */
    protected void setSchemaForAllDatabaseResources(String runId) {
        for (List<IResource> resourceList : resources.values()) {
            for (IResource resource : resourceList) {
                if (resource instanceof ISchemaAwareDatabase) {
                    ((ISchemaAwareDatabase) resource).setSimulationRun(runId);
                    log.debug("Set schema for database resource: {}", resource.getResourceName());
                }
            }
        }
    }
    
    // Template method - subclasses implement specific processing
    protected abstract void processBatch(List<TickData> ticks) throws Exception;
    
    @Override
    protected void run() throws InterruptedException {
        try {
            // Step 1: Discover run ID
            String runId = discoverRunId();
            
            // Step 2: Wait for metadata
            cachedMetadata = pollForMetadata(runId);
            samplingInterval = cachedMetadata.getSamplingInterval();
            
            // Step 3: Set schema for all database resources
            // Note: Schema must already exist (created by MetadataIndexer)
            // For other indexers this is called at start of indexRun()
            setSchemaForAllDatabaseResources(runId);
            
            log.info("Starting batch processing: runId={}, samplingInterval={}, insertBatchSize={}, flushTimeout={}ms",
                    runId, samplingInterval, insertBatchSize, flushTimeoutMs);
            
            lastBatchClaimTime = System.currentTimeMillis();
            
            // Step 4: Batch processing loop
            while (!Thread.currentThread().isInterrupted()) {
                boolean claimedAny = processBatchDiscoveryIteration(runId);
                
                if (!claimedAny) {
                    // No new batches available
                    checkFlushTimeout();
                    Thread.sleep(pollIntervalMs);
                }
            }
            
            // Shutdown: Flush remaining buffer
            if (!tickBuffer.isEmpty()) {
                log.info("Shutdown: flushing {} remaining ticks from {} batches",
                        tickBuffer.size(), claimedBatches.size());
                flushBuffer();
            }
            
        } catch (TimeoutException e) {
            log.error("Indexer failed: {}", e.getMessage());
            throw new RuntimeException(e);
        } catch (Exception e) {
            log.error("Indexing failed", e);
            throw new RuntimeException(e);
        }
    }
    
    private boolean processBatchDiscoveryIteration(String runId) throws Exception {
        // List available batches from storage
        List<String> availableBatches = storage.listBatchFiles(runId + "/", null, 100)
            .getFilenames();
        
        boolean claimedAny = false;
        
        for (String batchFilename : availableBatches) {
            long tickStart = parseBatchStartTick(batchFilename);
            long tickEnd = parseBatchEndTick(batchFilename);
            
            // Check for gap before this batch
            if (coordinator.hasGapBefore(tickStart, samplingInterval)) {
                // Gap detected - mark as pending
                coordinator.markGapPending(batchFilename, tickStart, tickEnd);
                
                // Check if gap has been pending too long
                Instant gapFirstSeen = coordinator.getGapPendingSince(tickStart);
                if (gapFirstSeen != null) {
                    long gapAgeMs = System.currentTimeMillis() - gapFirstSeen.toEpochMilli();
                    
                    if (gapAgeMs > gapWarningTimeoutMs) {
                        long expectedTick = tickStart - samplingInterval;
                        log.warn("Permanent gap detected: expected batch at tick {} has been missing for {} seconds",
                                expectedTick, gapAgeMs / 1000);
                        permanentGapsDetected.incrementAndGet();
                    }
                }
                
                continue; // Try next batch
            }
            
            // No gap - try to claim
            String instanceId = serviceName + "-" + Thread.currentThread().getId();
            if (coordinator.tryClaim(batchFilename, tickStart, tickEnd, instanceId)) {
                // Claim successful!
                log.debug("Claimed batch: {}", batchFilename);
                
                List<TickData> ticks = storage.readBatch(batchFilename);
                tickBuffer.addAll(ticks);
                claimedBatches.add(batchFilename);
                lastBatchClaimTime = System.currentTimeMillis();
                claimedAny = true;
                
                // Check if buffer is full
                if (tickBuffer.size() >= insertBatchSize) {
                    flushBuffer();
                }
                
                break; // Process one batch per iteration for fair distribution
            }
            // else: Another indexer claimed it, try next batch
        }
        
        return claimedAny;
    }
    
    private void checkFlushTimeout() throws Exception {
        long timeSinceLastBatch = System.currentTimeMillis() - lastBatchClaimTime;
        
        if (timeSinceLastBatch >= flushTimeoutMs && !tickBuffer.isEmpty()) {
            log.info("Flush timeout reached: writing {} ticks from {} batches (partial batch)",
                    tickBuffer.size(), claimedBatches.size());
            flushBuffer();
        }
    }
    
    private void flushBuffer() throws Exception {
        if (tickBuffer.isEmpty()) {
            return;
        }
        
        // Process the batch (subclass implementation)
        processBatch(new ArrayList<>(tickBuffer));
        
        // Mark all claimed batches as completed
        for (String batchFilename : claimedBatches) {
            coordinator.markCompleted(batchFilename);
            
            // Release gap-pending batches that can now be processed
            long tickEnd = parseBatchEndTick(batchFilename);
            coordinator.releaseGapPendingBatches(tickEnd, samplingInterval);
        }
        
        // Clear buffer
        tickBuffer.clear();
        claimedBatches.clear();
    }
    
    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new HashMap<>();
        metrics.put("current_buffer_size", tickBuffer.size());
        metrics.put("claimed_batches_pending", claimedBatches.size());
        metrics.put("permanent_gaps_detected", permanentGapsDetected.get());
        return metrics;
    }
}
```

## DummyIndexer Implementation

```java
/**
 * Test indexer that exercises batch coordination infrastructure without writing data.
 * <p>
 * DummyIndexer:
 * <ul>
 *   <li>Discovers simulation runs</li>
 *   <li>Waits for metadata to be indexed</li>
 *   <li>Coordinates batch processing with other indexer instances</li>
 *   <li>Reads tick data from batches</li>
 *   <li>Does NOT write any data (no database operations)</li>
 *   <li>Does NOT override prepareSchema() - schema already exists from MetadataIndexer</li>
 * </ul>
 * <p>
 * Purpose: Validate competing consumer patterns, gap detection, and coordination logic
 * without the complexity of actual data processing.
 * <p>
 * <strong>Usage:</strong> Run multiple DummyIndexer instances concurrently to test
 * coordination and verify no duplicate processing occurs.
 */
public class DummyIndexer extends AbstractIndexer implements IMonitorable {
    
    private final AtomicLong batchesProcessed = new AtomicLong(0);
    private final AtomicLong ticksProcessed = new AtomicLong(0);
    private final AtomicLong cellsObserved = new AtomicLong(0);
    private final AtomicLong organismsObserved = new AtomicLong(0);
    
    public DummyIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
    }
    
    @Override
    protected void logStarted() {
        log.info("DummyIndexer started: insertBatchSize={}, flushTimeout={}ms, gapWarningTimeout={}ms",
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

### Database Resource

```hocon
mydb {
  className = "org.evochora.datapipeline.resources.database.H2Database"
  options {
    dataDirectory = "${user.home}/evochora/data"
    maxPoolSize = 10
    minIdle = 2
    connectionTimeout = 30000
    
    # Performance metrics window size for sliding window calculations (default: 5 seconds)
    metricsWindowSeconds = 5
  }
}
```

### DummyIndexer Service

```hocon
dummy-indexer-1 {
  className = "org.evochora.datapipeline.services.indexers.DummyIndexer"
  
  resources {
    storage = "storage-read:tick-storage"
    metadataReader = "db-meta-read:mydb"
    coordinator = "db-coordinator:mydb"
  }
  
  options {
    # Optional: Specific run to index (post-mortem mode)
    # If not set, uses timestamp-based discovery (parallel mode)
    # runId = "20251006143025-550e8400-e29b-41d4-a716-446655440000"
    
    # Run discovery polling (parallel mode)
    pollIntervalMs = 1000
    maxPollDurationMs = 300000  # 5 minutes
    
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
    metadataReader = "db-meta-read:mydb"
    coordinator = "db-coordinator:mydb"
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
- Test markCompleted, markFailed, markGapPending
- Test hasGapBefore with various scenarios
- Test releaseGapPendingBatches
- Mock AbstractDatabaseResource

**MetadataReaderWrapperTest:**
- Test getMetadata success and MetadataNotFoundException
- Test hasMetadata
- Mock AbstractDatabaseResource

**AbstractIndexerTest:**
- Test tick buffering (smaller/larger than storage batch size)
- Test flush timeout behavior
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
    @AllowLog(logger = "org.evochora.datapipeline.services.indexers.DummyIndexer",
              level = INFO, message = "DummyIndexer started: *")
    @AllowLog(logger = "org.evochora.datapipeline.services.indexers.DummyIndexer",
              level = INFO, message = "Starting batch processing: *")
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
    @AllowLog(logger = "org.evochora.datapipeline.services.indexers.DummyIndexer",
              level = INFO, message = "*")
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
    @AllowLog(logger = "org.evochora.datapipeline.services.indexers.DummyIndexer",
              level = INFO, message = "*")
    @AllowLog(logger = "org.evochora.datapipeline.services.indexers.DummyIndexer",
              level = WARN, message = "Permanent gap detected: *")
    void testGapDetection_TemporaryGap() throws Exception {
        // Setup: Create metadata and batches with deliberate gap
        String runId = createTestRun(samplingInterval = 10, numBatches = 5);
        
        // Simulate out-of-order arrival: batches 0, 2, 3, 4 (batch 1 missing initially)
        deleteBatchFile(runId, batchIndex = 1);
        
        DummyIndexer indexer = startIndexer(runId);
        
        // Wait briefly - indexer should mark batch 2+ as gap_pending
        Thread.sleep(2000);
        
        // Verify batch 0 processed, batch 2+ pending
        assertEquals(1, queryCompletedBatchCount(runId));
        assertTrue(queryGapPendingBatchCount(runId) > 0);
        
        // Now "deliver" the missing batch
        createBatchFile(runId, batchIndex = 1);
        
        // Wait for gap to be filled and all batches processed
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getMetrics().get("batches_processed").intValue() == 5);
        
        indexer.stop();
        
        // Verify all batches completed
        assertEquals(5, queryCompletedBatchCount(runId));
        assertEquals(0, queryGapPendingBatchCount(runId));
    }
    
    @Test
    @AllowLog(logger = "org.evochora.datapipeline.services.indexers.DummyIndexer",
              level = INFO, message = "*")
    @ExpectLog(logger = "org.evochora.datapipeline.services.indexers.DummyIndexer",
               level = WARN, 
               message = "Permanent gap detected: expected batch at tick * has been missing for * seconds")
    void testGapDetection_PermanentGap() throws Exception {
        // Setup: Batches with permanent gap (batch 1 never arrives)
        String runId = createTestRun(samplingInterval = 10, numBatches = 3);
        deleteBatchFile(runId, batchIndex = 1);
        
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
        
        // Verify: Batch 0 completed, batch 2+ still pending
        assertEquals(1, queryCompletedBatchCount(runId));
        assertTrue(queryGapPendingBatchCount(runId) > 0);
    }
    
    @Test
    @AllowLog(logger = "org.evochora.datapipeline.services.indexers.DummyIndexer",
              level = INFO, message = "*")
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
    @AllowLog(logger = "org.evochora.datapipeline.services.indexers.DummyIndexer",
              level = INFO, message = "*")
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
    @AllowLog(logger = "org.evochora.datapipeline.services.indexers.DummyIndexer",
              level = INFO, message = "*")
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
        // Query batch_processing table for completed batches
        // ... implementation ...
    }
    
    private int queryGapPendingBatchCount(String runId) {
        // Query batch_processing table for gap_pending batches
        // ... implementation ...
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

### 1. Coordination Strategy

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

Mark as 'gap_pending', release when filled, warn after timeout.

- Non-blocking (indexers process what they can)
- Automatic backfill when gaps close
- Timeout warnings detect permanent gaps
- Pipeline continues running (operational > perfect)

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

### 8. Table Creation Strategy

Lazy creation with cached check: each capability creates its tables on first use.

```java
// In H2Database
private final Set<String> coordinationTablesInitialized = ConcurrentHashMap.newKeySet();

protected void doTryClaim(...) {
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

#### 3. AbstractIndexer

**Required Metrics (all O(1)):**
```java
// Buffer status
metrics.put("current_buffer_size", tickBuffer.size());  // ArrayList.size() is O(1)
metrics.put("claimed_batches_pending", claimedBatches.size());

// Gap tracking
metrics.put("permanent_gaps_detected", permanentGapsDetected.get());
```

#### 4. DummyIndexer

**Required Metrics (all O(1)):**
```java
// Processing counters
metrics.put("batches_processed", batchesProcessed.get());
metrics.put("ticks_processed", ticksProcessed.get());
metrics.put("cells_observed", cellsObserved.get());
metrics.put("organisms_observed", organismsObserved.get());

// Inherited from AbstractIndexer
metrics.putAll(super.getMetrics());
```

#### 5. H2Database Extensions

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

### Log Levels

**DEBUG:** Repetitive operations
```java
log.debug("Claimed batch: {}", batchFilename);
log.debug("Polling for simulation runs");
log.debug("Released {} gap-pending batches", count);
```

**INFO:** Major events and completions
```java
log.info("Starting batch processing: runId={}, samplingInterval={}", runId, samplingInterval);
log.info("Metadata available for runId={}", runId);
log.info("Flush timeout reached: writing {} ticks", tickBuffer.size());
```

**WARN:** Permanent gaps and operational issues
```java
log.warn("Permanent gap detected: expected batch at tick {} has been missing for {} seconds",
        expectedTick, gapAgeMs / 1000);
```

**ERROR:** Fatal issues (but rare in this phase)
```java
log.error("Indexer failed: {}", e.getMessage());
```

### Structured Logging

Always include key context:
```java
log.info("Starting batch processing: runId={}, samplingInterval={}, insertBatchSize={}, flushTimeout={}ms",
        runId, samplingInterval, insertBatchSize, flushTimeoutMs);
```

### What NOT to Log

- Individual tick processing (too noisy)
- Detailed cell/organism data (use metrics instead)
- Gap checks that return false (not noteworthy)
- Successful claim attempts (use metrics)

## Testing Requirements

All tests must:
- Be tagged with `@Tag("unit")` or `@Tag("integration")`
- Use `@ExtendWith(LogWatchExtension.class)`
- Use `@AllowLog` or `@ExpectLog` for every expected log message
- Never use `Thread.sleep` - use Awaitility instead
- Leave no artifacts (in-memory H2, temp directories cleaned)
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
    @AllowLog(logger = "org.evochora.datapipeline.services.indexers.DummyIndexer",
              level = INFO, message = "Starting batch processing: *")
    void testSomething() {
        // Use Awaitility instead of Thread.sleep
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> someCondition());
    }
}
```

## Future Extensions

### EnvironmentIndexer (Phase 2.6)

Will use the same infrastructure:
```java
public class EnvironmentIndexer extends AbstractIndexer {
    
    private final IEnvironmentDatabase database;
    
    @Override
    protected void processBatch(List<TickData> ticks) {
        // Use database.insertCells(...)
        // Actual environment data processing
    }
}
```

### OrganismIndexer (Phase 2.7)

Will use the same infrastructure:
```java
public class OrganismIndexer extends AbstractIndexer {
    
    private final IOrganismDatabase database;
    
    @Override
    protected void processBatch(List<TickData> ticks) {
        // Use database.insertOrganisms(...)
        // Actual organism data processing
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

