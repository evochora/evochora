# Data Pipeline V3 - Environment Indexer (Phase 14.3) - SingleBlobStrategy

## Overview

This document specifies the **EnvironmentIndexer** with **SingleBlobStrategy** (default storage strategy). This is the first indexer that writes structured data from storage into a database. It indexes environment cell states from TickData using a **single-blob-per-tick** storage model with BYTEA serialization for compact storage.

## Goal

Implement EnvironmentIndexer with SingleBlobStrategy that:
- Reads TickData batches from storage (via topic notifications)
- Serializes cells to BYTEA for compact storage
- Writes ticks to database with MERGE for idempotency (on tick_number)
- Supports dimension-agnostic BYTEA format (1D to N-D)
- Enables competing consumers pattern
- Provides foundation for HTTP API queries (Phase 14.5)
- Achieves 1000× storage reduction compared to row-per-cell

## Success Criteria

Upon completion:
1. EnvironmentIndexer extends AbstractBatchIndexer with METADATA + BUFFERING components
2. Database schema created idempotently (environment_ticks table)
3. SingleBlobStrategy serializes cells to BYTEA (protobuf format)
4. MERGE-based writes ensure 100% idempotency (on tick_number)
5. Sparse cell storage (only non-empty cells in BYTEA)
6. Competing consumers work correctly (multiple indexer instances)
7. All tests pass (unit + integration)
8. Storage efficiency: ~1 GB for 10^6 ticks (vs. 5 TB with row-per-cell)

## Prerequisites

- Phase 14.2.6: AbstractBatchIndexer with TickBufferingComponent (completed)
- Phase 14.2.5: DummyIndexer as reference implementation (completed)
- AbstractDatabaseWrapper supports URI parameters (consistency with Storage/Queue resources)
- EnvironmentProperties extended with flatIndexToCoordinates() method
- **CellStateList protobuf message** added to tickdata_contracts.proto (used by SingleBlobStrategy)

## Architectural Design

### Component Architecture

EnvironmentIndexer follows the three-level indexer architecture:

```
AbstractIndexer (core: run discovery, schema management)
    ↓
AbstractBatchIndexer (batch processing: topic loop, buffering, ACK tracking)
    ↓
EnvironmentIndexer (only: prepareSchema() + flushTicks())
```

**Resources Required:**
- `storage` - IBatchStorageRead (read TickData batches)
- `topic` - ITopicReader<BatchInfo, ACK> (batch notifications)
- `metadata` - IMetadataReader (simulation metadata)
- `database` - IEnvironmentDataWriter (write environment cells)

**Components Used:**
- ✅ MetadataReadingComponent (default)
- ✅ TickBufferingComponent (default)
- ❌ IdempotencyComponent (not needed - MERGE provides idempotency)

### Data Flow

```
PersistenceService → Storage (TickData batches)
                  → Topic (BatchInfo notifications)
                         ↓
                    [Topic Poll - Blocking]
                         ↓
EnvironmentIndexer → Read TickData from Storage
                  → Extract CellState list
                  → Buffer cells (TickBufferingComponent)
                  → Flush → flatIndex → coordinates (EnvironmentProperties)
                         → MERGE to database (100% idempotent)
                  → ACK (only after ALL ticks of batch flushed)
```

### Idempotency Strategy

**100% idempotent via MERGE:**

```sql
MERGE INTO environment_ticks (tick_number, cells_blob)
KEY (tick_number)
VALUES (?, ?)
```

**Crash Recovery:**
```
1. Flush: 250 cells → MERGE → DB ✅
2. CRASH before ACK
3. Topic redelivery: same BatchInfo
4. Re-read storage → same cells → MERGE
   - Existing rows: UPDATE (noop, same data) ✅
   - Missing rows: INSERT ✅
5. Result: All cells exactly once in DB
```

**No IdempotencyComponent needed** - MERGE guarantees correctness even without batch-level tracking.

### Thread Safety

**Competing Consumers Run in Separate Processes:**
- Each indexer instance has its own JVM and memory space
- No shared state between instances
- Each instance has its own `EnvironmentProperties` instance

**EnvironmentProperties Thread Safety:**
- Uses **eager initialization** of `strides` field in constructor
- `strides` is marked `final` → immutable after construction
- **No race conditions** even if code is reused in multi-threaded contexts
- Lazy initialization with `if (strides == null)` would create race conditions

**Why Eager Init is Critical:**
```java
// ❌ WRONG: Race condition with lazy init
private int[] strides;  // Shared mutable state!

public int[] flatIndexToCoordinates(int flatIndex) {
    if (strides == null) {  // ⚠️ Two threads can both see null!
        strides = calculateStrides();  // ⚠️ Multiple assignments!
    }
    // ...
}

// ✅ CORRECT: Eager init in constructor
private final int[] strides;  // Immutable after construction!

public EnvironmentProperties(int[] worldShape, boolean isToroidal) {
    this.worldShape = worldShape.clone();
    this.isToroidal = isToroidal;
    this.strides = calculateStrides();  // ✅ Thread-safe by design
}
```

## Error Handling & Recovery

### flushTicks() Exception Behavior

When `flushTicks()` throws an exception, the error is handled by `AbstractBatchIndexer.processBatchMessage()`:

**Error Flow:**
```
1. flushTicks() throws SQLException (e.g., database connection lost)
   ↓
2. processBatchMessage() catches Exception
   ↓
3. log.warn("Failed to process batch...: {}: {}", batchId, e.getMessage()) + recordError()
   ↓
4. NO topic.ack(msg) - batch remains unclaimed
   ↓
5. Topic claimTimeout (300s) expires
   ↓
6. Topic reassigns batch to another consumer (or same after recovery)
   ↓
7. Batch reprocessed
   ↓
8. MERGE prevents duplicates ✅
```

**Key Points:**
- ✅ **Indexer continues running** - transient errors don't kill the service
- ✅ **No data loss** - batch is redelivered after claimTimeout
- ✅ **No duplicates** - MERGE handles redelivery idempotently
- ✅ **Error tracking** - recordError() tracks failures for monitoring

**Exception Types:**

| Exception | Source | Behavior |
|-----------|--------|----------|
| `SQLException` | Database write failed | Transient error → WARN + recordError + NO ACK → Redelivery |
| `IOException` | Storage read failed | Transient error → WARN + recordError + NO ACK → Redelivery |
| `RuntimeException` | Coordinate conversion failed | Should never happen (valid metadata) → Same as above |
| `InterruptedException` | Service shutdown | Re-thrown → Service stops gracefully |

**flushTicks() Contract:**
```java
/**
 * Flushes ticks to database using MERGE for idempotency.
 * <p>
 * <strong>Exception Handling:</strong> Implementations should let exceptions
 * propagate to AbstractBatchIndexer for graceful error handling:
 * <ul>
 *   <li>SQLException: Database errors → batch redelivered after claimTimeout</li>
 *   <li>RuntimeException: Logic errors → batch redelivered after claimTimeout</li>
 * </ul>
 * <p>
 * <strong>IMPORTANT for Database Layer:</strong> Lower-level implementations
 * (e.g., IH2EnvStorageStrategy.writeTicks()) MUST use try-catch to rollback
 * transactions before re-throwing exceptions. This ensures the database
 * connection is returned to the pool in a clean state:
 * <pre>
 * // In Strategy.writeTicks():
 * try {
 *     // ... write operations ...
 *     conn.commit();
 * } catch (SQLException e) {
 *     try {
 *         conn.rollback();  // REQUIRED - clean connection!
 *     } catch (SQLException ex) {
 *         log.warn("Rollback failed: {}", ex.getMessage());
 *     }
 *     throw e;  // Re-throw for AbstractBatchIndexer
 * }
 * </pre>
 * <p>
 * The indexer service continues running even if this method throws an exception.
 * Failed batches are reassigned by the topic system for automatic recovery.
 *
 * @param ticks Ticks to flush (already buffered by TickBufferingComponent)
 * @throws SQLException if database write fails (transient - will be retried)
 * @throws RuntimeException if logic errors occur (should never happen)
 */
protected abstract void flushTicks(List<TickData> ticks) throws Exception;
```

### Failure Scenarios

| Failure Point | Error Type | Indexer Behavior | Batch Outcome |
|---------------|------------|------------------|---------------|
| Storage read fails | IOException | WARN + recordError | NO ACK → Redelivery after 300s |
| Coordinate conversion fails | RuntimeException | WARN + recordError | NO ACK → Redelivery after 300s |
| Database connection lost | SQLException | WARN + recordError | NO ACK → Redelivery after 300s |
| MERGE constraint violation | SQLException | WARN + recordError | NO ACK → Redelivery after 300s |
| Service shutdown | InterruptedException | Service stops | NO ACK → Redelivery on restart |

**Recovery Guarantees:**
- ✅ **Automatic recovery**: Topic reassignment after claimTimeout (300s)
- ✅ **No data loss**: Failed batches are redelivered
- ✅ **Idempotency**: MERGE prevents duplicates on redelivery
- ✅ **Service availability**: Indexer continues processing other batches

### Graceful Shutdown

When the indexer receives a shutdown signal, it performs a **graceful shutdown** to minimize data loss and redelivery:

**Shutdown Flow:**
```
1. Service receives shutdown signal (Thread.interrupt())
   ↓
2. Topic poll loop exits (isInterrupted() == true)
   ↓
3. finally-block executes (GUARANTEED - even on interrupt)
   ↓
4. TickBufferingComponent checks buffer size
   ↓
5. If buffer not empty: flushAndAcknowledge() called
   ↓
6. All buffered ticks written to database (MERGE)
   ↓
7. Database commits transaction
   ↓
8. Topic ACKs sent for all processed batches
   ↓
9. Indexer terminates cleanly
```

**Implementation (AbstractBatchIndexer):**
```java
} finally {
    // Final flush of remaining buffered ticks (always executed, even on interrupt!)
    if (components != null && components.buffering != null 
        && components.buffering.getBufferSize() > 0) {
        try {
            flushAndAcknowledge();
        } catch (Exception e) {
            log.error("Final flush failed during shutdown");
            throw e;  // Service goes to ERROR state
        }
    }
}
```

**Shutdown Guarantees:**
- ✅ **No buffered data loss**: All buffered ticks written before shutdown
- ✅ **Clean acknowledgments**: All processed batches ACKed (no redelivery on restart)
- ✅ **Minimized replay**: Only unclaimed batches need reprocessing on restart
- ✅ **Transaction safety**: Database transaction committed before ACK
- ✅ **Fast restart**: No need to reprocess already-flushed batches

**Exception During Final Flush:**

If the final flush fails (e.g., database unavailable):
```
1. log.error("Final flush failed during shutdown")
2. Exception thrown → Service goes to ERROR state
3. Buffered ticks NOT ACKed → Redelivered on restart
4. MERGE ensures no duplicates when reprocessed
```

This is intentional: Better to replay data than lose it or create duplicates.

**Comparison with Crash:**

| Scenario | Buffered Ticks | ACKs Sent | Restart Behavior |
|----------|----------------|-----------|------------------|
| **Graceful Shutdown** | Flushed to DB | Sent | Only unclaimed batches replayed |
| **Crash/Kill -9** | Lost from buffer | Not sent | All unclaimed batches replayed |
| **Final Flush Fails** | Lost from buffer | Not sent | All unclaimed batches replayed (same as crash) |

**Best Practice:** Use graceful shutdown (SIGTERM) instead of force kill (SIGKILL) to minimize replay overhead.

**Example - Database Connection Lost:**
```
1. Indexer processes batch_0 → Success → ACK ✅
2. Database connection lost
3. Indexer processes batch_1 → SQLException in flushTicks()
   → log.warn() + recordError()
   → NO ACK
   → Indexer continues to batch_2 ✅
4. After 300s: Topic reassigns batch_1
5. Database reconnects (automatic via connection pool)
6. Indexer reprocesses batch_1 → Success → ACK ✅
```

**Why NOT stop the indexer:**
- Database errors are often transient (connection timeout, disk full recovery)
- Other batches can still be processed successfully
- Competing consumers can take over failed batches
- Service restart would lose all buffered state

## Implementation

### 1. EnvironmentProperties Extension

**File:** `src/main/java/org/evochora/runtime/model/EnvironmentProperties.java`

Add methods for flat_index → coordinate conversion:

```java
/**
 * Converts a flat index to coordinates.
 * <p>
 * This is the inverse operation of the linearization used by Environment.
 * Uses row-major order: flatIndex = coord[0] + coord[1]*stride[1] + coord[2]*stride[2] + ...
 * <p>
 * <strong>Performance:</strong> Strides are eagerly initialized in constructor for O(1) conversion.
 * <p>
 * <strong>Thread Safety:</strong> This method is thread-safe because strides is final and immutable.
 * While competing consumers run in separate processes/JVMs, eager initialization eliminates
 * potential race conditions if code is reused in multi-threaded contexts.
 *
 * @param flatIndex The flat index to convert
 * @return Coordinate array with same length as worldShape
 * @throws IllegalArgumentException if flatIndex is negative or out of bounds
 */
public int[] flatIndexToCoordinates(int flatIndex) {
    if (flatIndex < 0) {
        throw new IllegalArgumentException("Flat index must be non-negative: " + flatIndex);
    }
    
    int[] coord = new int[worldShape.length];
    int remaining = flatIndex;
    
    for (int i = 0; i < worldShape.length; i++) {
        coord[i] = remaining / strides[i];
        remaining %= strides[i];
    }
    
    return coord;
}

/**
 * Calculates strides for flat index conversion.
 * <p>
 * Row-major order: stride[0]=1, stride[i]=stride[i-1]*shape[i-1]
 * <p>
 * Called once during construction for eager initialization.
 *
 * @return Strides array
 */
private int[] calculateStrides() {
    int[] s = new int[worldShape.length];
    s[0] = 1;
    for (int i = 1; i < worldShape.length; i++) {
        s[i] = s[i-1] * worldShape[i-1];
    }
    return s;
}

// Field for caching (add to class)
private final int[] strides; // Eagerly initialized in constructor (thread-safe!)

// Update constructor to initialize strides:
public EnvironmentProperties(int[] worldShape, boolean isToroidal) {
    this.worldShape = worldShape.clone();
    this.isToroidal = isToroidal;
    this.strides = calculateStrides(); // ✅ Eager init = thread-safe by design
}
```

**Rationale:**
- ✅ Centralizes coordinate conversion logic (no duplication)
- ✅ Environment-internal format remains encapsulated
- ✅ O(1) conversion (strides eagerly initialized in constructor)
- ✅ Thread-safe by design (`final` field = immutable)
- ✅ Can evolve with Environment without breaking indexer

### 2. Database Capability Interface

**File:** `src/main/java/org/evochora/datapipeline/api/resources/database/IEnvironmentDataWriter.java`

```java
package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.contracts.CellState;
import org.evochora.datapipeline.api.resources.IMonitorable;

import java.sql.SQLException;
import java.util.List;

/**
 * Database capability for writing environment cell data.
 * <p>
 * Provides write operations for environment cells with dimension-agnostic schema.
 * Used by EnvironmentIndexer to persist cell states for HTTP API queries.
 * <p>
 * Extends {@link ISchemaAwareDatabase} - AbstractIndexer automatically calls
 * {@code setSimulationRun()} after run discovery to set the schema.
 * <p>
 * Implements {@link AutoCloseable} to enable try-with-resources pattern for
 * automatic connection cleanup.
 */
public interface IEnvironmentDataWriter extends ISchemaAwareDatabase, IMonitorable, AutoCloseable {
    
    /**
     * Creates the environment_ticks table idempotently.
     * <p>
     * Table schema (dimension-agnostic via BYTEA):
     * <pre>
     * CREATE TABLE IF NOT EXISTS environment_ticks (
     *     tick_number BIGINT PRIMARY KEY,
     *     cells_blob BYTEA NOT NULL
     * )
     * </pre>
     * 
     * <strong>Note:</strong> BYTEA is used instead of BLOB for PostgreSQL MODE compatibility in H2.
     * <p>
     * Implementations should:
     * <ul>
     *   <li>Use CREATE TABLE IF NOT EXISTS (idempotent)</li>
     *   <li>Create spatial indexes for area queries</li>
     *   <li>Prepare MERGE statement for writeEnvironmentCells()</li>
     * </ul>
     *
     * @param dimensions Number of dimensions (e.g., 2 for 2D, 3 for 3D)
     * @throws SQLException if table creation fails
     */
    void createEnvironmentDataTable(int dimensions) throws SQLException;
    
    /**
     * Writes environment cells for multiple ticks using MERGE for idempotency.
     * <p>
     * All ticks are written in one JDBC batch with one commit for maximum performance.
     * Delegates to IH2EnvStorageStrategy for actual storage implementation.
     * <p>
     * Each tick is identified by tick_number and written with MERGE:
     * <ul>
     *   <li>If tick exists: UPDATE (overwrite with new data)</li>
     *   <li>If tick missing: INSERT</li>
     * </ul>
     * <p>
     * This ensures 100% idempotency even with topic redeliveries.
     * <p>
     * <strong>Performance:</strong> All ticks written in one JDBC batch with one commit.
     * This reduces commit overhead by ~1000× compared to per-tick commits.
     * <p>
     * <strong>Strategy-Specific:</strong> SingleBlobStrategy serializes cells to BYTEA.
     * EnvironmentProperties passed for strategies that need coordinate conversion.
     *
     * @param ticks List of ticks with their cell data to write
     * @param envProps Environment properties for coordinate conversion (if needed by strategy)
     * @throws SQLException if database write fails
     */
    void writeEnvironmentCells(List<TickData> ticks, EnvironmentProperties envProps) 
            throws SQLException;
    
    /**
     * Closes the database wrapper and releases its dedicated connection back to the pool.
     * <p>
     * This method is automatically called when used with try-with-resources.
     * Implementations must ensure the connection is properly closed even if errors occur.
     */
    @Override
    void close();
}
```

### 3. Database Wrapper Implementation

**File:** `src/main/java/org/evochora/datapipeline/resources/database/EnvironmentDataWriterWrapper.java`

```java
package org.evochora.datapipeline.resources.database;

import org.evochora.datapipeline.api.contracts.CellState;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IEnvironmentDataWriter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowPercentiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Database-agnostic wrapper for environment data writing operations.
 * <p>
 * Extends {@link AbstractDatabaseWrapper} to inherit common functionality:
 * connection management, schema setting, error tracking, metrics infrastructure.
 * <p>
 * <strong>Performance Contract:</strong> All metrics use O(1) recording.
 */
class EnvironmentDataWriterWrapper extends AbstractDatabaseWrapper implements IEnvironmentDataWriter {
    private static final Logger log = LoggerFactory.getLogger(EnvironmentDataWriterWrapper.class);
    
    // Metrics (O(1) atomic operations)
    private final AtomicLong cellsWritten = new AtomicLong(0);
    private final AtomicLong batchesWritten = new AtomicLong(0);
    private final AtomicLong writeErrors = new AtomicLong(0);
    
    // Performance tracking (O(1) recording)
    private final SlidingWindowCounter cellThroughput;
    private final SlidingWindowCounter batchThroughput;
    
    // Latency tracking (O(1) recording)
    private final SlidingWindowPercentiles writeLatency;
    
    EnvironmentDataWriterWrapper(AbstractDatabaseResource db, ResourceContext context) {
        super(db, context);  // Parent handles connection, error tracking, metricsWindowSeconds
        
        // Note: metricsWindowSeconds inherited from parent (AbstractDatabaseWrapper)
        // Configuration hierarchy: URI parameter > Resource option > Default (60)
        
        // Initialize throughput trackers (same window as latency for consistency)
        this.cellThroughput = new SlidingWindowCounter(metricsWindowSeconds);
        this.batchThroughput = new SlidingWindowCounter(metricsWindowSeconds);
        this.writeLatency = new SlidingWindowPercentiles(metricsWindowSeconds);
    }
    
    @Override
    public void createEnvironmentDataTable(int dimensions) throws SQLException {
        try {
            database.doCreateEnvironmentDataTable(ensureConnection(), dimensions);
            log.debug("Environment ticks table created with {} dimensions", dimensions);
        } catch (Exception e) {
            log.error("Failed to create environment_ticks table with {} dimensions: {}", dimensions, e.getMessage());
            throw new SQLException("Failed to create environment_ticks table", e);
        }
    }
    
    @Override
    public void writeEnvironmentCells(List<TickData> ticks, EnvironmentProperties envProps) 
            throws SQLException {
        long startNanos = System.nanoTime();
        
        try {
            database.doWriteEnvironmentCells(ensureConnection(), ticks, envProps);
            
            // Calculate total cells across all ticks
            int totalCells = ticks.stream()
                .mapToInt(t -> t.getCellsList().size())
                .sum();
            
            // Update metrics
            cellsWritten.addAndGet(totalCells);
            batchesWritten.incrementAndGet();
            cellThroughput.recordSum(totalCells);  // O(1) - track cells/sec
            batchThroughput.recordCount();         // O(1) - track batches/sec (1 batch = all ticks)
            writeLatency.record(System.nanoTime() - startNanos);
            
        } catch (Exception e) {
            // Transient error - wrapper continues functioning
            writeErrors.incrementAndGet();
            log.warn("Failed to write {} ticks: {}", ticks.size(), e.getMessage());
            recordError("WRITE_FAILED", "Failed to write environment cells", 
                       "Ticks: " + ticks.size() + ", Error: " + e.getClass().getSimpleName());
            throw new SQLException("Failed to write environment cells", e);
        }
    }
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Include parent metrics from AbstractDatabaseWrapper
        
        // Counters - O(1)
        metrics.put("cells_written", cellsWritten.get());
        metrics.put("batches_written", batchesWritten.get());
        metrics.put("write_errors", writeErrors.get());
        
        // Throughput - O(windowSeconds) = O(60) = O(constant)
        metrics.put("cells_per_second", cellThroughput.getRate());
        metrics.put("batches_per_second", batchThroughput.getRate());
        
        // Latency percentiles in milliseconds - O(windowSeconds × buckets) = O(constant)
        metrics.put("write_latency_p50_ms", writeLatency.getPercentile(50) / 1_000_000.0);
        metrics.put("write_latency_p95_ms", writeLatency.getPercentile(95) / 1_000_000.0);
        metrics.put("write_latency_p99_ms", writeLatency.getPercentile(99) / 1_000_000.0);
        metrics.put("write_latency_avg_ms", writeLatency.getAverage() / 1_000_000.0);
    }
}
```

### 4. Storage Strategy Architecture

**File:** `src/main/java/org/evochora/datapipeline/resources/database/h2/IH2EnvStorageStrategy.java`

```java
package org.evochora.datapipeline.resources.database.h2;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.runtime.model.EnvironmentProperties;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * H2-specific strategy interface for storing environment data.
 * <p>
 * Different strategies trade off between storage size, query performance,
 * and write performance. This interface is H2-specific and cannot be used
 * with other database backends.
 * <p>
 * <strong>Rationale:</strong> Storage requirements for environment data can vary
 * dramatically based on environment size and tick count:
 * <ul>
 *   <li>Small runs (1000×1000, 10^6 ticks): ~15 GB with row-per-cell</li>
 *   <li>Large runs (10000×10000, 10^8 ticks): ~500 TB with row-per-cell ❌</li>
 * </ul>
 * Different storage strategies enable different trade-offs without code changes.
 * <p>
 * <strong>Phase 14.3:</strong> Only write operations needed. Query methods
 * will be added in Phase 14.5 (HTTP API implementation).
 */
public interface IH2EnvStorageStrategy {
    
    /**
     * Creates the necessary schema (tables, indexes) for this storage strategy.
     * <p>
     * Must be idempotent - calling multiple times should be safe.
     *
     * @param conn Database connection (with autoCommit=false)
     * @param dimensions Number of spatial dimensions
     * @throws SQLException if schema creation fails
     */
    void createSchema(Connection conn, int dimensions) throws SQLException;
    
    /**
     * Writes environment data for multiple ticks using this storage strategy.
     * <p>
     * <strong>Transaction Management:</strong> This method is executed within a transaction
     * managed by the caller (H2Database). Implementations should <strong>NOT</strong> call
     * {@code commit()} or {@code rollback()} themselves. If an exception is thrown, the
     * caller is responsible for rolling back the transaction.
     * <p>
     * <strong>Rationale:</strong> Keeps strategy focused on SQL operations only, while
     * H2Database manages transaction lifecycle consistently across all methods.
     *
     * @param conn Database connection (with autoCommit=false, transaction managed by caller)
     * @param ticks List of ticks with cell data to write
     * @param envProps Environment properties for coordinate conversion
     * @throws SQLException if write fails (caller will rollback)
     */
    void writeTicks(Connection conn, List<TickData> ticks, 
                    EnvironmentProperties envProps) throws SQLException;
}
```

**Abstract Base Class:**

**File:** `src/main/java/org/evochora/datapipeline/resources/database/h2/AbstractH2EnvStorageStrategy.java`

```java
package org.evochora.datapipeline.resources.database.h2;

import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for H2 environment storage strategies.
 * <p>
 * Enforces constructor contract: All strategies MUST accept Config parameter.
 * <p>
 * Provides common infrastructure:
 * <ul>
 *   <li>Config options access (protected final)</li>
 *   <li>Logger instance (protected final)</li>
 * </ul>
 * <p>
 * <strong>Rationale:</strong> Ensures all strategies can be instantiated via reflection
 * with consistent constructor signature. The compiler enforces that subclasses call
 * super(options), preventing runtime errors from missing constructors.
 */
public abstract class AbstractH2EnvStorageStrategy implements IH2EnvStorageStrategy {
    
    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final Config options;
    
    /**
     * Creates storage strategy with configuration.
     * <p>
     * <strong>Subclass Requirement:</strong> All subclasses MUST call super(options).
     * The compiler enforces this.
     * 
     * @param options Strategy configuration (may be empty, never null)
     */
    protected AbstractH2EnvStorageStrategy(Config options) {
        this.options = java.util.Objects.requireNonNull(options, "options cannot be null");
    }
    
    // createSchema() and writeTicks() remain abstract - too strategy-specific
}
```

**Default Strategy Implementation:**

For Phase 14.3, implement `SingleBlobStrategy` (compact storage):

```java
package org.evochora.datapipeline.resources.database.h2;

/**
 * Stores environment data as one row per tick with cells serialized in a BYTEA.
 * <p>
 * <strong>Storage:</strong> ~1 KB per tick (with compression)
 * <ul>
 *   <li>100×100 environment, 10% occupancy = 1000 cells/tick</li>
 *   <li>1000 cells × 20 bytes (protobuf) = 20 KB raw</li>
 *   <li>With compression: ~1-2 KB per tick</li>
 *   <li>10^6 ticks = 1-2 GB total ✅</li>
 * </ul>
 * <p>
 * <strong>Query Performance:</strong> Slower (must deserialize entire tick)
 * <p>
 * <strong>Write Performance:</strong> Excellent (one row per tick)
 * <p>
 * <strong>Best For:</strong> Large runs where storage size is critical.
 * <p>
 * <strong>Implementation Details:</strong> To be specified (table schema, blob format, etc.)
 */
public class SingleBlobStrategy extends AbstractH2EnvStorageStrategy {
    
    private final ICompressionCodec codec;
    private String mergeSql;  // SQL string
    private PreparedStatement cachedStmt;  // Cached PreparedStatement
    private Connection cachedConn;  // Track which connection owns the cached stmt
    
    /**
     * Creates SingleBlobStrategy with optional compression.
     * 
     * @param options Config with optional compression block
     */
    public SingleBlobStrategy(Config options) {
        super(options);  // Required by abstract base class
        
        // Load compression codec (optional - defaults to NoneCodec if missing)
        this.codec = CompressionCodecFactory.create(options);
        
        log.debug("SingleBlobStrategy initialized with compression: {}", codec.getName());
    }
    
    @Override
    public void createSchema(Connection conn, int dimensions) throws SQLException {
        // Create table (dimension_count NOT stored - retrieved from metadata)
        // Use H2SchemaUtil to handle concurrent initialization race conditions
        Statement stmt = conn.createStatement();
        H2SchemaUtil.executeDdlIfNotExists(
            stmt,
            "CREATE TABLE IF NOT EXISTS environment_ticks (" +
            "  tick_number BIGINT PRIMARY KEY," +
            "  cells_blob BYTEA NOT NULL" +
            ")",
            "environment_ticks"
        );
        
        // Create index (also use DDL helper for race condition)
        H2SchemaUtil.executeDdlIfNotExists(
            stmt,
            "CREATE INDEX IF NOT EXISTS idx_env_tick ON environment_ticks (tick_number)",
            "idx_env_tick"
        );
        
        // Cache SQL string for PreparedStatement creation per connection
        // NOTE: PreparedStatement objects are bound to specific Connection instances!
        // Strategy must create new PreparedStatement per writeTicks() call.
        this.mergeSql = "MERGE INTO environment_ticks (tick_number, cells_blob) " +
                       "KEY (tick_number) VALUES (?, ?)";
    }
    
    @Override
    public void writeTicks(Connection conn, List<TickData> ticks, 
                          EnvironmentProperties envProps) throws SQLException {
        if (ticks.isEmpty()) {
            return;
        }
        
        // Skip ticks with empty cell lists (resilience - shouldn't happen in practice)
        List<TickData> validTicks = ticks.stream()
            .filter(tick -> {
                if (tick.getCellsList().isEmpty()) {
                    log.warn("Tick {} has empty cell list - skipping database write", tick.getTickNumber());
                    return false;
                }
                return true;
            })
            .toList();
        
        if (validTicks.isEmpty()) {
            log.warn("All {} ticks had empty cell lists - no database writes performed", ticks.size());
            return;
        }
        
        // Connection-safe PreparedStatement caching
        // If connection changed (pool rotation), recreate PreparedStatement
        if (cachedStmt == null || cachedConn != conn) {
            cachedStmt = conn.prepareStatement(mergeSql);
            cachedConn = conn;
            log.debug("Created new PreparedStatement for connection");
        }
        
        cachedStmt.clearBatch();  // Clear previous batch state
        
        for (TickData tick : validTicks) {
            byte[] cellsBlob = serializeTickCells(tick);
            
            cachedStmt.setLong(1, tick.getTickNumber());
            cachedStmt.setBytes(2, cellsBlob);
            cachedStmt.addBatch();
        }
        
        cachedStmt.executeBatch();
        
        log.debug("Wrote {} ticks to environment_ticks table (skipped {} empty ticks)", 
                 validTicks.size(), ticks.size() - validTicks.size());
        // NOTE: Transaction management (commit/rollback) is handled by H2Database caller
    }
    
    private byte[] serializeTickCells(TickData tick) throws SQLException {
        try {
            // Build CellStateList message
            CellStateList cellsList = CellStateList.newBuilder()
                .addAllCells(tick.getCellsList())
                .build();
            
            // Serialize to bytes with optional compression
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (OutputStream compressed = codec.wrapOutputStream(baos)) {
                cellsList.writeTo(compressed);
            }
            
            return baos.toByteArray();
            
        } catch (IOException e) {
            throw new SQLException("Failed to serialize cells for tick: " + tick.getTickNumber(), e);
        }
    }
}
```

**Note:** Alternative strategies (e.g., RowPerCellStrategy, RegionBasedStrategy) can be added later without changes to the core architecture.

### 5. H2 Database Implementation

**File:** `src/main/java/org/evochora/datapipeline/resources/database/H2Database.java`

**Add new abstract methods to AbstractDatabaseResource:**

```java
/**
 * Creates environment_ticks table with BYTEA-based schema.
 * <p>
 * <strong>Transaction Handling:</strong> Must commit on success, rollback on failure.
 * <strong>Storage Strategy:</strong> Delegates to IH2EnvStorageStrategy for actual schema creation.
 * 
 * @param connection Database connection (from acquireDedicatedConnection)
 * @param dimensions Number of spatial dimensions (stored in table for validation)
 * @throws Exception if table creation fails
 */
protected abstract void doCreateEnvironmentDataTable(Object connection, int dimensions) 
        throws Exception;

/**
 * Writes environment cells for multiple ticks to database using MERGE for idempotency.
 * <p>
 * <strong>Transaction Handling:</strong> Must commit on success, rollback on failure.
 * <strong>Performance:</strong> All ticks written in one JDBC batch with one commit.
 * 
 * @param connection Database connection (from acquireDedicatedConnection)
 * @param ticks List of ticks with their cell data to write
 * @param envProps Environment properties for coordinate conversion
 * @throws Exception if write fails
 */
protected abstract void doWriteEnvironmentCells(Object connection, List<TickData> ticks,
        EnvironmentProperties envProps) throws Exception;
```

**Note:** Transaction contract (autoCommit=false, rollback on error) is already implemented in AbstractDatabaseResource and H2Database.

**Add storage strategy to H2Database:**

```java
// Field for storage strategy (loaded via reflection)
private IH2EnvStorageStrategy envStorageStrategy;

public H2Database(String name, Config options) {
    super(name, options);
    
    // ... existing HikariCP initialization ...
    
    // Load storage strategy via reflection (with options)
    if (options.hasPath("h2EnvironmentStrategy")) {
        Config strategyConfig = options.getConfig("h2EnvironmentStrategy");
        String strategyClassName = strategyConfig.getString("className");
        
        // Extract options for strategy (defaults to empty config if missing)
        Config strategyOptions = strategyConfig.hasPath("options")
            ? strategyConfig.getConfig("options")
            : ConfigFactory.empty();
        
        this.envStorageStrategy = createStorageStrategy(strategyClassName, strategyOptions);
        log.debug("Loaded environment storage strategy: {} with compression: {}", 
                 strategyClassName, strategyOptions.hasPath("compression.codec") 
                     ? strategyOptions.getString("compression.codec") 
                     : "none");
    } else {
        // Default: SingleBlobStrategy without compression
        Config emptyConfig = ConfigFactory.empty();
        this.envStorageStrategy = createStorageStrategy(
            "org.evochora.datapipeline.resources.database.h2.SingleBlobStrategy",
            emptyConfig
        );
        log.debug("Using default SingleBlobStrategy (no compression)");
    }
}

private IH2EnvStorageStrategy createStorageStrategy(String className, Config strategyConfig) {
    try {
        Class<?> strategyClass = Class.forName(className);
        
        // Try constructor with Config parameter (enforced by AbstractH2EnvStorageStrategy)
            return (IH2EnvStorageStrategy) strategyClass
                .getDeclaredConstructor(Config.class)
                .newInstance(strategyConfig);
        
    } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException(
            "Storage strategy class not found: " + className + 
            ". Make sure the class exists and is in the classpath.", e);
    } catch (ClassCastException e) {
        throw new IllegalArgumentException(
            "Storage strategy class must implement IH2EnvStorageStrategy: " + className, e);
    } catch (NoSuchMethodException e) {
        throw new IllegalArgumentException(
            "Storage strategy must have public constructor(Config): " + className + 
            ". Extend AbstractH2EnvStorageStrategy to satisfy this contract.", e);
    } catch (Exception e) {
        throw new IllegalArgumentException(
            "Failed to instantiate storage strategy: " + className + 
            ". Error: " + e.getMessage(), e);
    }
}

@Override
protected void doCreateEnvironmentDataTable(Object connection, int dimensions) throws Exception {
    Connection conn = (Connection) connection;
    
    // Delegate to storage strategy
    envStorageStrategy.createSchema(conn, dimensions);
}

// NOTE: Actual implementation is in SingleBlobStrategy class
// H2Database only delegates to the strategy

@Override
protected void doWriteEnvironmentCells(Object connection, List<TickData> ticks,
                                       EnvironmentProperties envProps) 
        throws Exception {
    Connection conn = (Connection) connection;
    
    try {
        // Delegate to storage strategy for SQL operations
    envStorageStrategy.writeTicks(conn, ticks, envProps);
        
        // Commit transaction on success
        conn.commit();
        
    } catch (SQLException e) {
        // Rollback transaction on failure to keep connection clean
        try {
            conn.rollback();
        } catch (SQLException rollbackEx) {
            log.warn("Rollback failed (connection may be closed): {}", rollbackEx.getMessage());
        }
        throw e;
    }
}

// NOTE: closeConnectionPool() remains unchanged - no strategy-specific cleanup needed
```

Update `getWrappedResource()` in AbstractDatabaseResource:

```java
@Override
public final IWrappedResource getWrappedResource(ResourceContext context) {
    String usageType = context.usageType();
    IWrappedResource wrapper = switch (usageType) {
        case "db-meta-write" -> new MetadataWriterWrapper(this, context);
        case "db-meta-read" -> new MetadataReaderWrapper(this, context);
        case "db-env-write" -> new EnvironmentDataWriterWrapper(this, context);  // NEW
        default -> throw new IllegalArgumentException(
                "Unknown database usage type: " + usageType + 
                ". Supported: db-meta-write, db-meta-read, db-env-write");
    };
    
    // Track wrapper for cleanup
    if (wrapper instanceof AutoCloseable) {
        activeWrappers.add((AutoCloseable) wrapper);
    }
    
    return wrapper;
}
```

### 5. EnvironmentIndexer Implementation

**File:** `src/main/java/org/evochora/datapipeline/services/indexers/EnvironmentIndexer.java`

```java
package org.evochora.datapipeline.services.indexers;

import com.google.protobuf.Message;
import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.CellState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.IEnvironmentDataWriter;
import org.evochora.runtime.model.EnvironmentProperties;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Indexes environment cell states from TickData for efficient spatial queries.
 * <p>
 * This indexer:
 * <ul>
 *   <li>Reads TickData batches from storage (via topic notifications)</li>
 *   <li>Extracts CellState list (sparse - only non-empty cells)</li>
 *   <li>Converts flat_index to coordinates using EnvironmentProperties</li>
 *   <li>Writes to database with MERGE for 100% idempotency</li>
 *   <li>Supports dimension-agnostic schema (1D to N-D)</li>
 * </ul>
 * <p>
 * <strong>Resources Required:</strong>
 * <ul>
 *   <li>{@code storage} - IBatchStorageRead for reading TickData batches</li>
 *   <li>{@code topic} - ITopicReader for batch notifications</li>
 *   <li>{@code metadata} - IMetadataReader for simulation metadata</li>
 *   <li>{@code database} - IEnvironmentDataWriter for writing cells</li>
 * </ul>
 * <p>
 * <strong>Components Used:</strong>
 * <ul>
 *   <li>MetadataReadingComponent - waits for metadata before processing</li>
 *   <li>TickBufferingComponent - buffers cells for efficient batch writes</li>
 * </ul>
 * <p>
 * <strong>Competing Consumers:</strong> Multiple instances can run in parallel
 * using the same consumer group. Topic distributes batches across instances,
 * and MERGE ensures idempotent writes even with concurrent access.
 *
 * @param <ACK> The acknowledgment token type (implementation-specific)
 */
public class EnvironmentIndexer<ACK> extends AbstractBatchIndexer<ACK> {
    
    private final IEnvironmentDataWriter database;
    private EnvironmentProperties envProps;
    
    public EnvironmentIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.database = getRequiredResource("database", IEnvironmentDataWriter.class);
    }
    
    // Use default components: METADATA + BUFFERING
    // No override needed - AbstractBatchIndexer provides correct defaults
    
    @Override
    protected void prepareSchema(String runId) throws Exception {
        // Load metadata (provided by MetadataReadingComponent via getMetadata())
        SimulationMetadata metadata = getMetadata();
        
        // Extract environment properties for coordinate conversion
        this.envProps = extractEnvironmentProperties(metadata);
        
        // Create database table (idempotent)
        int dimensions = envProps.getWorldShape().length;
        database.createEnvironmentDataTable(dimensions);
        
        log.debug("Environment schema prepared: {} dimensions", dimensions);
    }
    
    @Override
    protected void flushTicks(List<TickData> ticks) throws Exception {
        if (ticks.isEmpty()) {
            log.debug("No ticks to flush");
            return;
        }

        // Write ALL ticks in one JDBC batch with one commit
        // Coordinate conversion happens in database layer
        database.writeEnvironmentCells(ticks, envProps);
        
        int totalCells = ticks.stream()
            .mapToInt(t -> t.getCellsList().size())
            .sum();
        
        log.debug("Flushed {} cells from {} ticks", totalCells, ticks.size());
    }
    
    /**
     * Extracts EnvironmentProperties from SimulationMetadata.
     */
    private EnvironmentProperties extractEnvironmentProperties(SimulationMetadata metadata) {
        // Extract world shape from metadata
        int[] worldShape = metadata.getEnvironment().getShapeList().stream()
            .mapToInt(Integer::intValue)
            .toArray();
        
        // Extract topology - check if ALL dimensions are toroidal
        // (In practice, all dimensions have same topology for now)
        boolean isToroidal = !metadata.getEnvironment().getToroidalList().isEmpty() 
            && metadata.getEnvironment().getToroidal(0);
        
        return new EnvironmentProperties(worldShape, isToroidal);
    }
    
}
```

## Configuration

**File:** `evochora.conf`

```hocon
resources {
  # Existing resources...
  
  index-database {
    className = "org.evochora.datapipeline.resources.database.H2Database"
    options {
      jdbcUrl = "jdbc:h2:${user.home}/evochora/data/evochora;MODE=PostgreSQL;AUTO_SERVER=TRUE"
      username = "sa"
      password = ""
      maxPoolSize = 10
      minIdle = 2
      metricsWindowSeconds = 60  # Time window for throughput/latency metrics (default: 60)
    }
  }
}

services {
  # Existing services...
  
  environment-indexer {
    className = "org.evochora.datapipeline.services.indexers.EnvironmentIndexer"
    
    resources {
      # Topic for batch notifications (competing consumer pattern)
      topic = "topic-read:batch-topic?consumerGroup=environment"
      
      # Storage for reading TickData batches
      storage = "storage-read:tick-storage"
      
      # Metadata for simulation configuration
      metadata = "db-meta-read:index-database"
      
      # Database for writing environment cells
      # URI parameters: metricsWindowSeconds (optional, overrides resource-level config)
      # Example with override: database = "db-env-write:index-database?metricsWindowSeconds=30"
      database = "db-env-write:index-database"
    }
    
    options {
      # Optional: explicit runId (otherwise discovered from storage)
      runId = ${?pipeline.services.runId}
      
      # Metadata component (standardized config names)
      metadataPollIntervalMs = 1000
      metadataMaxPollDurationMs = 300000
      
      # Buffering component (standardized config names)
      insertBatchSize = 1000    # Number of ticks to buffer before flush (all written in one JDBC batch + commit)
      flushTimeoutMs = 5000     # Flush after 5s even if buffer not full
      # Note: topicPollTimeoutMs automatically set to flushTimeoutMs
      # Performance: 1000 ticks × ~5000 cells = ~5M cells per commit (massive throughput!)
    }
  }
}
```

**Database Configuration (Storage Strategy):**

```hocon
resources {
  index-database {
    className = "org.evochora.datapipeline.resources.database.H2Database"
    options {
      jdbcUrl = "jdbc:h2:file:${user.home}/evochora/index"
      maxPoolSize = 10
      minIdle = 2
      
      # Storage strategy (optional - defaults to SingleBlobStrategy without compression)
      h2EnvironmentStrategy {
        className = "org.evochora.datapipeline.resources.database.h2.SingleBlobStrategy"
        options {
          # Compression is optional - if section missing, no compression used
          compression {
            enabled = true
            codec = "zstd"
            level = 3  # 1 (fastest) to 22 (best compression), default: 3
          }
        }
      }
      
      # Alternative strategies can be implemented later:
      # h2EnvironmentStrategy {
      #   className = "com.mycompany.custom.MyCustomStrategy"
      #   options { /* custom options */ }
      # }
    }
  }
}
```

**Competing Consumers Configuration:**

To run multiple indexer instances for higher throughput:

```hocon
services {
  environment-indexer-1 {
    className = "org.evochora.datapipeline.services.indexers.EnvironmentIndexer"
    resources {
      topic = "topic-read:batch-topic?consumerGroup=environment"  # Same group!
      storage = "storage-read:tick-storage"
      metadata = "db-meta-read:index-database"
      database = "db-env-write:index-database"
    }
    options { /* same as above */ }
  }
  
  environment-indexer-2 {
    className = "org.evochora.datapipeline.services.indexers.EnvironmentIndexer"
    resources {
      topic = "topic-read:batch-topic?consumerGroup=environment"  # Same group!
      storage = "storage-read:tick-storage"
      metadata = "db-meta-read:index-database"
      database = "db-env-write:index-database"
    }
    options { /* same as above */ }
  }
}
```

**Coordination:**
- **Topic:** Consumer group ensures each batch processed by exactly ONE instance
- **Database:** MERGE ensures idempotent writes (concurrent writes safe)
- **Schema:** `H2SchemaUtil.executeDdlIfNotExists()` handles concurrent DDL race conditions (H2 bug workaround)

## Database Schema (SingleBlobStrategy)

**Table Structure:**

```sql
CREATE TABLE IF NOT EXISTS environment_ticks (
    tick_number BIGINT PRIMARY KEY,
    cells_blob BYTEA NOT NULL
);

-- Note: BYTEA is PostgreSQL-compatible and works in H2 with MODE=PostgreSQL
```

**Note:** dimension_count is NOT stored - it's retrieved from metadata table per run_id.
This avoids redundancy (all ticks in a run have same dimension count).

**Index:**

```sql
-- Primary key index on tick_number (automatic)
-- Enables fast tick-based queries
CREATE INDEX IF NOT EXISTS idx_env_tick ON environment_ticks (tick_number);
```

**BYTEA Format:**

The `cells_blob` column contains a serialized `CellStateList` protobuf message:

```protobuf
// In tickdata_contracts.proto
message CellStateList {
  repeated CellState cells = 1;
}
```

**Serialization:**
```java
// Write: Serialize to bytes
CellStateList cellsList = CellStateList.newBuilder()
    .addAllCells(tick.getCellsList())
    .build();
byte[] blob = cellsList.toByteArray();
```

**Deserialization:**
```java
// Read: Parse from bytes
CellStateList cellsList = CellStateList.parseFrom(blob);
List<CellState> cells = cellsList.getCellsList();
```

Each CellState in the list contains:
- `flat_index` - Position in environment (converted to coordinates for queries)
- `molecule_type` - Type of molecule at this cell
- `molecule_value` - Value of molecule
- `owner_id` - Organism ID owning this cell

**Storage Characteristics:**

- **Compact:** One row per tick (~1-2 KB per tick with compression)
- **Sparse:** Only non-empty cells included in blob (same as protobuf)
- **Simple Primary Key:** tick_number only (no composite key needed)
- **MERGE-friendly:** Simple tick_number key enables efficient MERGE
- **Dimension-agnostic:** Blob format works for any number of dimensions

**Storage Size Comparison:**

| Strategy | Rows | Size per Tick | 10^6 Ticks | 10^8 Ticks |
|----------|------|---------------|------------|------------|
| **Row-Per-Tick** | 10^6 | ~1 KB | ~1 GB | ~100 GB |
| Row-Per-Cell | 10^9 | ~50 bytes | ~5 TB | ~500 TB |

**→ Row-Per-Tick is 1000× more compact!**

## HTTP API Query Examples (Phase 14.5 - Future)

**Note:** With SingleBlobStrategy, HTTP API queries require deserialization:

```sql
-- Step 1: Read tick blob
SELECT cells_blob FROM environment_ticks WHERE tick_number = 1000;

-- Step 2: Deserialize blob in Java
List<CellState> cells = deserializeCellsBlob(blob);

-- Step 3: Filter cells by area in Java
List<CellState> filtered = cells.stream()
    .filter(cell -> {
        int[] coords = envProps.flatIndexToCoordinates(cell.getFlatIndex());
        return coords[0] >= 10 && coords[0] <= 20
            && coords[1] >= 30 && coords[1] <= 40;
    })
    .collect(Collectors.toList());
```

**Performance:**
- ✅ Excellent for "get entire tick" queries
- ⚠️ Slower for small area queries (must deserialize entire tick)
- ✅ Much better storage efficiency (1000× reduction)

**Single Cell Query:**

```sql
-- Get specific cell at specific tick (requires deserialization)
SELECT cells_blob FROM environment_ticks WHERE tick_number = 1000;

-- Then in Java: deserialize and find by flat_index or coordinates
```

**Tick Diff (future feature):**

```sql
-- Query both ticks
SELECT cells_blob FROM environment_ticks WHERE tick_number IN (1000, 2000);

-- Then in Java: deserialize both, compute diff
```

## Performance Considerations

### Memory Usage

**Indexer Memory (Write Path):**

Per Tick (100×100 2D, 50% occupied = 5,000 cells):
- CellState protobuf: ~20 bytes per cell
- CellStateList overhead: ~10 bytes
- **Total per tick:** ~100 KB

With insertBatchSize=1000:
- Buffer holds ~1000 ticks = 100 MB in memory
- Serialized to BYTEA: ~1000 × 100 KB = 100 MB
- Acceptable for JVM heap (configure with -Xmx2G or more)

**API Server Memory (Read Path - Phase 14.5):**

Per Request for one tick:
- Must load entire BYTEA into memory
- Deserialize to List<CellState>
- Filter in Java
- **Memory spike: ~100 KB to ~8 MB** depending on environment size
- High traffic → multiple concurrent requests → significant memory pressure

### Write Performance (Indexer)

**MERGE Batch (1,000 ticks with SingleBlobStrategy):**
- Serialization (CellStateList.toByteArray()): ~10 ms for 1000 ticks
- H2 Batch MERGE: ~50 ms for 1000 rows (SSD)
- Commit: ~5 ms
- **Total: ~70 ms = ~14,000 ticks/sec per indexer**
- **Cell throughput: ~14k ticks × 5k cells = 70M cells/sec** 🚀

**Write Scalability:**
- Competing consumers: Linear scalability up to ~4-8 instances
- Bottleneck: Database connection pool (increase maxPoolSize)
- **Storage:** ~1 GB per 10^6 ticks (vs. 5 TB with row-per-cell)

### Read Performance (HTTP API - Phase 14.5)

**Critical Trade-off:** The storage savings come at the cost of read performance and memory.

**Read Path for Area Query:**
```
1. SQL: SELECT cells_blob FROM environment_ticks WHERE tick_number = ?
   → ~5 ms (fast - single row lookup)
   
2. Deserialize BYTEA: CellStateList.parseFrom(blob)
   → ~50 ms for 5,000 cells (CPU-bound)
   
3. Filter in Java: Stream filter by coordinates
   → ~10 ms for 5,000 cells
   
Total: ~65 ms per tick query
```

**Memory Impact on API Server:**

| Environment Size | Cells/Tick (10% occupied) | BYTEA Size | API Server Memory |
|------------------|---------------------------|-----------|-------------------|
| 100×100 | 1,000 | ~20 KB | Negligible |
| 1000×1000 | 100,000 | ~2 MB | Acceptable |
| 2000×2000 | 400,000 | ~8 MB | ⚠️ Significant per request |
| 10000×10000 | 10,000,000 | ~200 MB | ❌ Problematic! |

**Implications for HTTP API (Phase 14.5):**
- ✅ **Small environments (<1000×1000):** SingleBlobStrategy works well
- ⚠️ **Medium environments (2000×2000):** Consider caching, request throttling
- ❌ **Large environments (>5000×5000):** May need different strategy (RegionBasedStrategy)

**Read Scalability:**
- API server must handle BYTEA deserialization + filtering per request
- High query load → high CPU usage (deserialization) + memory (BYTEA in RAM)
- Mitigation: Result caching, query rate limiting, CDN for static ticks

**The Design Trade-off:**

| Aspect | SingleBlobStrategy | RowPerCellStrategy |
|--------|-------------------|-------------------|
| **Database Storage** | ~1 GB (10^6 ticks) | ~5 TB (10^6 ticks) |
| **Write Performance** | Excellent (14k ticks/sec) | Good (slower commits) |
| **Read Performance** | Slow (deserialize entire tick) | Fast (SQL filtered) |
| **API Server Memory** | High (~200 MB for large tick) | Low (only filtered rows) |
| **API Server CPU** | High (deserialization) | Low (DB does filtering) |

**Recommendation:** SingleBlobStrategy is optimal for workstation/development use where:
- Storage is limited (laptop SSD)
- Write performance is critical (simulation can't wait)
- Read queries are infrequent (mostly offline analysis)

For production with high-traffic HTTP API, consider RegionBasedStrategy (future work).

**Performance Monitoring:**

Available metrics provide comprehensive performance visibility:

| Metric | Type | Purpose |
|--------|------|---------|
| `cells_written` | Counter | Total cells written (lifetime) |
| `batches_written` | Counter | Total DB batches written (lifetime) |
| `cells_per_second` | Throughput | Real-time write throughput (configurable window, default: 60s) |
| `batches_per_second` | Throughput | Real-time batch rate (configurable window, default: 60s) |
| `write_latency_p50_ms` | Latency | Median write latency |
| `write_latency_p95_ms` | Latency | 95th percentile latency |
| `write_latency_p99_ms` | Latency | 99th percentile latency |
| `write_errors` | Counter | Failed write operations |

**Configuration Hierarchy:**
1. **URI Parameter** (highest priority): `database = "db-env-write:indexDatabase?metricsWindowSeconds=30"`
2. **Resource Option**: `indexDatabase.options.metricsWindowSeconds = 60`
3. **Default**: 60 seconds

This allows per-service override of metrics window when needed (e.g., shorter window for high-frequency indexers).

**All metrics use O(1) recording** - no performance impact on critical path.

### Coordinate Conversion Performance

**EnvironmentProperties.flatIndexToCoordinates():**
- Constructor: O(dimensions) - eagerly calculate strides (one-time cost)
- Every call: O(dimensions) - use cached strides
- Typical: O(3) for 3D world = negligible overhead
- Thread-safe: `final` field eliminates race conditions

## Testing Strategy

### Unit Tests

**File:** `src/test/java/org/evochora/datapipeline/services/indexers/EnvironmentIndexerTest.java`

```java
@Test
@Tag("unit")
void testExtractEnvironmentProperties_2D() {
    SimulationMetadata metadata = createMetadata(new int[]{100, 200}, "TORUS");
    
    EnvironmentProperties props = indexer.extractEnvironmentProperties(metadata);
    
    assertThat(props.getWorldShape()).isEqualTo(new int[]{100, 200});
    assertThat(props.isToroidal()).isTrue();
}

@Test
@Tag("unit")
void testExtractEnvironmentProperties_3D() {
    SimulationMetadata metadata = createMetadata(new int[]{10, 20, 30}, "BOUNDED");
    
    EnvironmentProperties props = indexer.extractEnvironmentProperties(metadata);
    
    assertThat(props.getWorldShape()).isEqualTo(new int[]{10, 20, 30});
    assertThat(props.isToroidal()).isFalse();
}

@Test
@Tag("unit")
void testFlushTicks_SingleTick() throws Exception {
    // Setup: Mock database
    IEnvironmentDataWriter mockDatabase = mock(IEnvironmentDataWriter.class);
    EnvironmentIndexer indexer = createIndexerWithMockDatabase(mockDatabase);
    
    // Create tick with 3 cells
    TickData tick = TickData.newBuilder()
        .setTickNumber(1000)
        .addCells(createCell(0, 1, 5))
        .addCells(createCell(1, 2, 10))
        .addCells(createCell(2, 3, 15))
        .build();
    
    // Execute
    indexer.flushTicks(List.of(tick));
    
    // Verify: database.writeEnvironmentCells() called once with list of ticks
    ArgumentCaptor<List<TickData>> ticksCaptor = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<EnvironmentProperties> envPropsCaptor = ArgumentCaptor.forClass(EnvironmentProperties.class);
    
    verify(mockDatabase, times(1)).writeEnvironmentCells(
        ticksCaptor.capture(),
        envPropsCaptor.capture()
    );
    
    // Verify single tick passed
    assertThat(ticksCaptor.getValue()).hasSize(1);
    assertThat(ticksCaptor.getValue().get(0).getTickNumber()).isEqualTo(1000L);
    assertThat(ticksCaptor.getValue().get(0).getCellsList()).hasSize(3);
}

@Test
@Tag("unit")
void testFlushTicks_MultipleTicks() throws Exception {
    IEnvironmentDataWriter mockDatabase = mock(IEnvironmentDataWriter.class);
    EnvironmentIndexer indexer = createIndexerWithMockDatabase(mockDatabase);
    
    // Create 3 ticks with different cell counts
    TickData tick1 = createTickWithCells(1000, 5);
    TickData tick2 = createTickWithCells(1001, 3);
    TickData tick3 = createTickWithCells(1002, 7);
    
    // Execute
    indexer.flushTicks(List.of(tick1, tick2, tick3));
    
    // Verify: writeEnvironmentCells() called ONCE with all 3 ticks
    verify(mockDatabase, times(1)).writeEnvironmentCells(anyList(), any());
    
    // Verify all ticks passed in one call
    ArgumentCaptor<List<TickData>> ticksCaptor = ArgumentCaptor.forClass(List.class);
    verify(mockDatabase).writeEnvironmentCells(ticksCaptor.capture(), any());
    
    assertThat(ticksCaptor.getValue()).hasSize(3);
    assertThat(ticksCaptor.getValue().get(0).getTickNumber()).isEqualTo(1000L);
    assertThat(ticksCaptor.getValue().get(1).getTickNumber()).isEqualTo(1001L);
    assertThat(ticksCaptor.getValue().get(2).getTickNumber()).isEqualTo(1002L);
}

@Test
@Tag("unit")
void testFlushTicks_EmptyTicks() throws Exception {
    IEnvironmentDataWriter mockDatabase = mock(IEnvironmentDataWriter.class);
    EnvironmentIndexer indexer = createIndexerWithMockDatabase(mockDatabase);
    
    // Create ticks with NO cells (empty environment)
    TickData emptyTick = TickData.newBuilder()
        .setTickNumber(1000)
        .build();  // No cells added
    
    // Execute
    indexer.flushTicks(List.of(emptyTick));
    
    // Verify: database IS called (but with empty tick - database can skip efficiently)
    verify(mockDatabase, times(1)).writeEnvironmentCells(anyList(), any());
}

@Test
@Tag("unit")
void testFlushTicks_MixedEmptyAndNonEmpty() throws Exception {
    IEnvironmentDataWriter mockDatabase = mock(IEnvironmentDataWriter.class);
    EnvironmentIndexer indexer = createIndexerWithMockDatabase(mockDatabase);
    
    // Mix: empty tick, non-empty, empty, non-empty
    TickData tick1 = TickData.newBuilder().setTickNumber(1000).build(); // Empty
    TickData tick2 = createTickWithCells(1001, 5);  // 5 cells
    TickData tick3 = TickData.newBuilder().setTickNumber(1002).build(); // Empty
    TickData tick4 = createTickWithCells(1003, 3);  // 3 cells
    
    // Execute
    indexer.flushTicks(List.of(tick1, tick2, tick3, tick4));
    
    // Verify: ONE call with all 4 ticks (database handles empty ticks efficiently)
    verify(mockDatabase, times(1)).writeEnvironmentCells(anyList(), any());
    
    // Verify all ticks passed (including empty ones)
    ArgumentCaptor<List<TickData>> ticksCaptor = ArgumentCaptor.forClass(List.class);
    verify(mockDatabase).writeEnvironmentCells(ticksCaptor.capture(), any());
    assertEquals(4, ticksCaptor.getValue().size());
}

@Test
@Tag("unit")
void testPrepareSchema() throws Exception {
    IEnvironmentDataWriter mockDatabase = mock(IEnvironmentDataWriter.class);
    EnvironmentIndexer indexer = createIndexerWithMockDatabase(mockDatabase);
    
    // Create metadata with 3 dimensions
    SimulationMetadata metadata = createMetadata(new int[]{10, 20, 30}, "TORUS");
    
    // Mock component to return metadata
    when(indexer.components.metadata.getMetadata()).thenReturn(metadata);
    
    // Execute
    indexer.prepareSchema("test-run-id");
    
    // Verify: createEnvironmentDataTable called with 3 dimensions
    verify(mockDatabase).createEnvironmentDataTable(3);
    
    // Verify: envProps cached correctly
    assertThat(indexer.envProps).isNotNull();
    assertThat(indexer.envProps.getWorldShape()).isEqualTo(new int[]{10, 20, 30});
}

// Helper methods
private CellState createCell(int flatIndex, int type, int value) {
    return CellState.newBuilder()
        .setFlatIndex(flatIndex)
        .setMoleculeType(type)
        .setMoleculeValue(value)
        .setOwnerId(0)
        .build();
}

private TickData createTickWithCells(long tickNumber, int cellCount) {
    TickData.Builder builder = TickData.newBuilder().setTickNumber(tickNumber);
    for (int i = 0; i < cellCount; i++) {
        builder.addCells(createCell(i, 1, i * 10));
    }
    return builder.build();
}

private SimulationMetadata createMetadata(int[] shape, String topology) {
    SimulationMetadata.Builder builder = SimulationMetadata.newBuilder();
    EnvironmentConfig.Builder envBuilder = EnvironmentConfig.newBuilder();
    envBuilder.setTopology(topology);
    for (int size : shape) {
        envBuilder.addDimensions(
            SimulationMetadata.EnvironmentDimension.newBuilder().setSize(size)
        );
    }
    builder.setEnvironment(envBuilder);
    return builder.build();
}
```

### Integration Tests

**File:** `src/test/java/org/evochora/datapipeline/services/indexers/EnvironmentIndexerIntegrationTest.java`

```java
@Test
@Tag("integration")
void testEnvironmentIndexerEndToEnd() throws Exception {
    // Setup: In-memory H2, fake topic, fake storage
    EnvironmentIndexer indexer = createIndexer();
    
    // Send: Metadata
    sendMetadata(runId, 2 /* dimensions */);
    
    // Send: 3 Batches with cells
    sendBatch(runId, "batch_0", 0, 100, createCells(100, 50)); // 50 cells
    sendBatch(runId, "batch_1", 100, 100, createCells(100, 75)); // 75 cells
    sendBatch(runId, "batch_2", 200, 100, createCells(100, 60)); // 60 cells
    
    // Wait for indexer to process
    await().atMost(5, SECONDS).until(() -> getCellCountInDb() == 185);
    
    // Verify: All cells in database with correct coordinates
    assertThat(getCellCountInDb()).isEqualTo(185);
    
    // Verify: Tick written to database with correct blob
    byte[] blob = queryTickBlob(tick=0);
    List<CellState> cells = deserializeBlob(blob);
    
    // Verify flat_index 25 is in the blob
    assertThat(cells).anyMatch(c -> c.getFlatIndex() == 25);
}

@Test
@Tag("integration")
void testMergeIdempotency() throws Exception {
    EnvironmentIndexer indexer = createIndexer();
    
    sendMetadata(runId, 2);
    sendBatch(runId, "batch_0", 0, 100, createCells(100, 50));
    
    await().until(() -> getCellCountInDb() == 50);
    
    // Send same batch again (Topic redelivery simulation)
    sendBatch(runId, "batch_0", 0, 100, createCells(100, 50));
    
    await().atMost(2, SECONDS).until(() -> getBatchesProcessed() == 2);
    
    // Verify: Still only 50 cells (MERGE prevents duplicates)
    assertThat(getCellCountInDb()).isEqualTo(50);
}

@Test
@Tag("integration")
void testCompetingConsumers() throws Exception {
    // Start 2 indexers with same consumer group
    EnvironmentIndexer indexer1 = createIndexer("environmentIndexer1");
    EnvironmentIndexer indexer2 = createIndexer("environmentIndexer2");
    
    indexer1.start();
    indexer2.start();
    
    sendMetadata(runId, 2);
    
    // Send 10 batches
    for (int i = 0; i < 10; i++) {
        sendBatch(runId, "batch_" + i, i * 100, 100, createCells(100, 50));
    }
    
    // Wait for all batches processed
    await().atMost(10, SECONDS).until(() -> getCellCountInDb() == 500);
    
    // Verify: All batches processed exactly once
    assertThat(getCellCountInDb()).isEqualTo(500);
    
    // Verify: Both indexers participated
    assertThat(indexer1.getBatchesProcessed() + indexer2.getBatchesProcessed()).isEqualTo(10);
}

@Test
@Tag("integration")
void testMultipleDimensions() throws Exception {
    // Test 1D, 2D, 3D, 4D
    for (int dimensions = 1; dimensions <= 4; dimensions++) {
        testWithDimensions(dimensions);
    }
}

private void testWithDimensions(int dimensions) throws Exception {
    EnvironmentIndexer indexer = createIndexer();
    
    int[] shape = new int[dimensions];
    Arrays.fill(shape, 10); // 10×10×10×...
    
    sendMetadata(runId, dimensions, shape);
    sendBatch(runId, "batch_0", 0, 10, createCellsForShape(shape, 5));
    
    await().until(() -> getCellCountInDb() == 5);
    
    // Verify: Schema has correct number of pos_i columns
    assertThat(getPositionColumnCount()).isEqualTo(dimensions);
}
```

### Test Utilities

```java
private List<CellState> createCells(int count, int nonEmpty) {
    List<CellState> cells = new ArrayList<>();
    for (int i = 0; i < nonEmpty; i++) {
        cells.add(CellState.newBuilder()
            .setFlatIndex(i)
            .setMoleculeType(1)
            .setMoleculeValue(42)
            .setOwnerId(1)
            .build());
    }
    return cells;
}
```

## Future Enhancements

### Partitioning (Not in Phase 14.3)

**Challenge:** H2 does not support native table partitioning or UNION VIEW optimization.

**Current Approach:** Single table per schema (run ID). Works for:
- Development/testing (smaller runs)
- Moderate production runs (<10^7 ticks)

**Future Options (when needed):**
1. **Application-level partitioning:** Multiple tables, indexer routes based on tick range
2. **Database migration:** PostgreSQL with native PARTITION BY RANGE
3. **Hybrid:** Old ticks archived to compressed Protobuf Binary

**Decision:** Defer until performance monitoring shows need. Initial implementation validates architecture with smaller runs.

### Additional Query Patterns

**Current:** Area queries only (tick + spatial range)

**Future possibilities:**
- Time-series queries: "Cell (x,y) over time"
- Change detection: "Modified cells between tick X and Y"
- Pattern matching: "Cells matching criteria"

**Decision:** Implement when HTTP API requirements clarify. Schema supports these patterns.

## Success Criteria Verification

Upon completion, verify:

1. ✅ AbstractDatabaseWrapper supports URI parameters (all database wrappers benefit)
2. ✅ EnvironmentProperties.flatIndexToCoordinates() with eagerly initialized strides (thread-safe)
3. ✅ IEnvironmentDataWriter interface (delegates to strategy)
4. ✅ EnvironmentDataWriterWrapper with O(1) throughput/latency metrics
5. ✅ IH2EnvStorageStrategy interface (createSchema, writeTicks)
6. ✅ SingleBlobStrategy implementation (BYTEA-based storage)
7. ✅ H2Database: Strategy loading via reflection + delegation
8. ✅ H2Database MERGE implementation (100% idempotency on tick_number)
9. ✅ EnvironmentIndexer extends AbstractBatchIndexer (METADATA + BUFFERING)
10. ✅ Only non-empty cells stored (sparse in BYTEA)
11. ✅ Competing consumers work (multiple indexer instances)
12. ✅ Unit tests pass (serialization, schema creation, MERGE idempotency)
13. ✅ Integration tests pass (end-to-end, idempotency, competing consumers, dimensions)
14. ✅ Storage efficiency: ~1 GB for 10^6 ticks (1000× better than row-per-cell)

## H2 Concurrent DDL Handling

**Problem: CREATE TABLE IF NOT EXISTS Race Condition**

H2 Database (version 2.2.224) has a known bug where `CREATE TABLE IF NOT EXISTS` can fail with "object already exists" error when multiple connections attempt to create the same table concurrently:

```
Instance 1: CREATE TABLE IF NOT EXISTS  → Checks → Table missing
Instance 2: CREATE TABLE IF NOT EXISTS  → Checks → Table missing
Instance 1: Creates table ✅
Instance 2: Creates table ❌ ERROR: Table already exists (code 42101 or 50000)
```

**Solution: H2SchemaUtil.executeDdlIfNotExists()**

All DDL statements with IF NOT EXISTS must use `H2SchemaUtil.executeDdlIfNotExists()` which catches and ignores "already exists" errors:

```java
// ✅ CORRECT - Handles concurrent initialization
H2SchemaUtil.executeDdlIfNotExists(
    stmt,
    "CREATE TABLE IF NOT EXISTS environment_ticks (...)",
    "environment_ticks"
);

// ❌ WRONG - May fail with competing consumers
stmt.execute("CREATE TABLE IF NOT EXISTS environment_ticks (...)");
``` 

This utility is used consistently across:
- `H2Database.doInsertMetadata()` (metadata table)
- `H2TopicResource.createTablesInSchema()` (topic tables)
- `SingleBlobStrategy.createSchema()` (environment_ticks table)

## Implementation Phases

**Strategy:** Incremental implementation with testable checkpoints. Each phase is self-contained and can be reviewed independently.

**Prerequisites (Already Completed):**
- ✅ AbstractDatabaseWrapper URI parameter support
- ✅ Transaction contract (AbstractDatabaseResource JavaDoc + H2Database rollback)
- ✅ H2SchemaUtil.executeDdlIfNotExists() utility (H2 DDL race condition workaround)
- ✅ CellStateList protobuf message (wrapper for repeated CellState)

---

### **Phase 1: Foundation - Coordinate Conversion**

**Goal:** Implement and test the core coordinate conversion logic.

**Deliverables:**
1. `EnvironmentProperties.flatIndexToCoordinates(int flatIndex)` method
2. `EnvironmentProperties.calculateStrides()` private method
3. Add `private final int[] strides` field (eager initialization)
4. Unit tests for all dimensions (1D, 2D, 3D, N-D)

**Files to modify:**
- `src/main/java/org/evochora/runtime/model/EnvironmentProperties.java`
- `src/test/java/org/evochora/runtime/model/EnvironmentPropertiesTest.java` (new)

**Tests:**
```java
@Test void testFlatIndexToCoordinates_2D()
@Test void testFlatIndexToCoordinates_3D()
@Test void testFlatIndexToCoordinates_1D()
@Test void testFlatIndexToCoordinates_ThreadSafety()
```

**✅ Checkpoint:** Coordinate conversion works correctly for all dimensions.

---

### **Phase 2: Storage Architecture - Interfaces**

**Goal:** Define the strategy pattern architecture without implementation.

**Deliverables:**
1. `IH2EnvStorageStrategy` interface (createSchema, writeTicks)
2. `AbstractH2EnvStorageStrategy` base class (Config + Logger)
3. `IEnvironmentDataWriter` interface (writeEnvironmentCells)

**Files to create:**
- `src/main/java/org/evochora/datapipeline/resources/database/h2/IH2EnvStorageStrategy.java`
- `src/main/java/org/evochora/datapipeline/resources/database/h2/AbstractH2EnvStorageStrategy.java`
- `src/main/java/org/evochora/datapipeline/api/resources/database/IEnvironmentDataWriter.java`

**No tests yet** - interfaces only.

**✅ Checkpoint:** Architecture compiles and follows Strategy Pattern correctly.

---

### **Phase 3: Storage Implementation - SingleBlobStrategy**

**Goal:** Implement BYTEA-based storage with compression support.

**Deliverables:**
1. `SingleBlobStrategy` class (createSchema + writeTicks)
2. BYTEA serialization with CellStateList protobuf
3. Optional compression via CompressionCodecFactory
4. Unit tests for serialization and schema creation

**Files to create:**
- `src/main/java/org/evochora/datapipeline/resources/database/h2/SingleBlobStrategy.java`
- `src/test/java/org/evochora/datapipeline/resources/database/h2/SingleBlobStrategyTest.java`

**Tests:**
```java
@Test void testSerializeCells_WithCompression()
@Test void testSerializeCells_WithoutCompression()
@Test void testCreateSchema_Idempotent()
@Test void testWriteTicks_MultipleTicks()
```

**✅ Checkpoint:** Strategy works in isolation (no H2Database integration yet).

---

### **Phase 4: Database Integration**

**Goal:** Integrate strategy into H2Database and create wrapper.

**Deliverables:**
1. `H2Database`: Strategy loading via reflection + delegation
2. `EnvironmentDataWriterWrapper` implementation
3. `AbstractDatabaseResource.getWrappedResource()` update (add "db-env-write")
4. Unit tests for strategy loading and wrapper

**Files to modify:**
- `src/main/java/org/evochora/datapipeline/resources/database/H2Database.java`
- `src/main/java/org/evochora/datapipeline/resources/database/EnvironmentDataWriterWrapper.java` (new)
- `src/main/java/org/evochora/datapipeline/resources/database/AbstractDatabaseResource.java`

**Tests:**
```java
@Test void testStrategyLoading_Reflection()
@Test void testStrategyLoading_InvalidClass()
@Test void testEnvironmentDataWriterWrapper_Metrics()
@Test void testEnvironmentDataWriterWrapper_ErrorHandling()
```

**✅ Checkpoint:** Database layer works end-to-end (can write cells to H2).

---

### **Phase 5: Indexer Service**

**Goal:** Implement EnvironmentIndexer service with batch processing.

**Deliverables:**
1. `EnvironmentIndexer` class (flushTicks + prepareSchema)
2. Component configuration (METADATA + BUFFERING)
3. Integration tests (end-to-end pipeline)

**Files to create:**
- `src/main/java/org/evochora/datapipeline/services/indexers/EnvironmentIndexer.java`
- `src/test/java/org/evochora/datapipeline/services/indexers/EnvironmentIndexerTest.java`

**Tests:**
```java
@Test void testFlushTicks_SingleBatch()
@Test void testFlushTicks_MultipleBatches()
@Test void testFlushTicks_Idempotency()
@Test void testCompetingConsumers_NoDataLoss()
@Test void testErrorRecovery_DatabaseFailure()
@Test void testDimensionAgnostic_1D_2D_3D()
```

**✅ Checkpoint:** Full pipeline works (PersistenceService → EnvironmentIndexer → H2).

---

### **Phase 6: Configuration & Polish**

**Goal:** Production-ready configuration and documentation.

**Deliverables:**
1. `evochora.conf` updates (environment-indexer service + resources)
2. Configuration validation
3. Performance verification (1M ticks < 10s)
4. Documentation updates

**Files to modify:**
- `evochora.conf`
- `docs/proposals/datapipeline_v3/14_3_ENVIRONMENT_INDEXER_SINGLE_BLOB.md`

**Verification:**
- ✅ Configuration loads without errors
- ✅ Competing consumers work correctly
- ✅ Storage efficiency: ~1 GB for 10^6 ticks
- ✅ All tests pass (unit + integration)

**✅ Checkpoint:** Production ready!

---

### **Testing Strategy (Per Phase)**

**Phase 1:** Unit tests only (coordinate conversion)
**Phase 2:** No tests (interfaces)
**Phase 3:** Unit tests (strategy in isolation)
**Phase 4:** Unit tests (database integration)
**Phase 5:** Integration tests (end-to-end)
**Phase 6:** Performance tests + validation

**Test Priorities:**
1. Coordinate conversion correctness (all dimensions)
2. MERGE idempotency (duplicate batches)
3. Competing consumers (no data loss)
4. Schema adaptation (1D to N-D)
5. Error recovery (rollback, redelivery)

**Transaction Contract:**
- All `doWrite*()` methods in `H2Database` MUST use try-catch with rollback
- All wrappers use `log.warn()` + `recordError()` for transient errors (database writes)
- Fatal errors (schema creation) still use `log.error()` without `recordError()`
- Connection pool receives only clean connections (committed or rolled back)

---

**Phase Tracking:**
- Phase 14.2: Indexer Foundation ✅ Completed
- Phase 14.3: Environment Indexer ⏳ This Document
- Phase 14.4: Organism Indexer (future)
- Phase 14.5: HTTP API (future)

