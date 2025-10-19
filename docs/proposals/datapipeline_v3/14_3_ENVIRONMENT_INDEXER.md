# Data Pipeline V3 - Environment Indexer (Phase 14.3)

## Overview

This document specifies the **EnvironmentIndexer**, the first indexer that writes structured data from storage into a database. It indexes environment cell states from TickData for efficient spatial queries by the HTTP API.

## Goal

Implement EnvironmentIndexer that:
- Reads TickData batches from storage (via topic notifications)
- Converts flat_index to coordinates using EnvironmentProperties
- Writes cell states to database with MERGE for idempotency
- Supports dimension-agnostic schema (1D to N-D)
- Enables competing consumers pattern
- Provides foundation for HTTP API area queries

## Success Criteria

Upon completion:
1. EnvironmentIndexer extends AbstractBatchIndexer with METADATA + BUFFERING components
2. Database schema created idempotently with variable dimension columns
3. flat_index → coordinate conversion using EnvironmentProperties
4. MERGE-based writes ensure 100% idempotency
5. Sparse cell storage (only non-empty cells)
6. Competing consumers work correctly (multiple indexer instances)
7. All tests pass (unit + integration)
8. HTTP API can query cells by area: `WHERE tick_number = T AND pos_0 BETWEEN x1 AND x2`

## Prerequisites

- Phase 14.2.6: AbstractBatchIndexer with TickBufferingComponent (completed)
- Phase 14.2.5: DummyIndexer as reference implementation (completed)
- EnvironmentProperties extended with flatIndexToCoordinates() method

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
MERGE INTO environment_data (tick_number, pos_0, pos_1, molecule_type, molecule_value, owner_id)
KEY (tick_number, pos_0, pos_1)
VALUES (?, ?, ?, ?, ?, ?)
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

## Implementation

### 1. EnvironmentProperties Extension

**File:** `src/main/java/org/evochora/runtime/model/EnvironmentProperties.java`

Add methods for flat_index → coordinate conversion:

```java
/**
 * Converts a flat index to coordinates.
 * <p>
 * This is the inverse operation of the linearization used by Environment.
 * Uses row-major order: flatIndex = pos_0 + pos_1*stride_1 + pos_2*stride_2 + ...
 * <p>
 * <strong>Performance:</strong> Strides are cached on first call for O(1) conversion.
 *
 * @param flatIndex The flat index to convert
 * @return Coordinate array with same length as worldShape
 * @throws IllegalArgumentException if flatIndex is negative or out of bounds
 */
public int[] flatIndexToCoordinates(int flatIndex) {
    if (flatIndex < 0) {
        throw new IllegalArgumentException("Flat index must be non-negative: " + flatIndex);
    }
    
    // Lazy-initialize strides cache
    if (strides == null) {
        strides = calculateStrides();
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
 * Strides are cached to avoid repeated calculation.
 * Row-major order: stride[0]=1, stride[i]=stride[i-1]*shape[i-1]
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
private int[] strides; // Cached strides for performance
```

**Rationale:**
- ✅ Centralizes coordinate conversion logic (no duplication)
- ✅ Environment-internal format remains encapsulated
- ✅ O(1) conversion after first call (strides cached)
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
     * Creates the environment_data table idempotently with variable dimension columns.
     * <p>
     * Table schema adapts to number of dimensions:
     * <pre>
     * CREATE TABLE IF NOT EXISTS environment_data (
     *     tick_number BIGINT NOT NULL,
     *     pos_0 INT NOT NULL,
     *     pos_1 INT NOT NULL,
     *     -- pos_2, pos_3, ... (dynamically added based on dimensions)
     *     molecule_type INT NOT NULL,
     *     molecule_value INT NOT NULL,
     *     owner_id INT NOT NULL,
     *     PRIMARY KEY (tick_number, pos_0, pos_1, ...)
     * )
     * </pre>
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
     * Writes environment cells using MERGE for idempotency.
     * <p>
     * Each cell is identified by (tick_number, coordinates) and written with MERGE:
     * <ul>
     *   <li>If row exists: UPDATE (noop if same data)</li>
     *   <li>If row missing: INSERT</li>
     * </ul>
     * <p>
     * This ensures 100% idempotency even with topic redeliveries.
     * <p>
     * <strong>Performance:</strong> Uses batch operations for efficient writes.
     * Typical batch size: 1000 cells.
     * <p>
     * <strong>Coordinate Conversion:</strong> Caller must convert flat_index to
     * coordinates before calling this method (using EnvironmentProperties).
     *
     * @param tickNumber The tick number for all cells
     * @param cells List of CellState protobuf messages (with flat_index)
     * @param coordinates Pre-computed coordinates for each cell (parallel to cells list)
     * @throws SQLException if write fails
     * @throws IllegalArgumentException if cells and coordinates list lengths don't match
     */
    void writeEnvironmentCells(long tickNumber, List<CellState> cells, List<int[]> coordinates) 
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
    
    // Latency tracking (O(1) recording)
    private final SlidingWindowPercentiles writeLatency;
    
    EnvironmentDataWriterWrapper(AbstractDatabaseResource db, ResourceContext context) {
        super(db, context);  // Parent handles connection, error tracking, metrics window
        
        this.writeLatency = new SlidingWindowPercentiles(metricsWindowSeconds);
    }
    
    @Override
    public void createEnvironmentDataTable(int dimensions) throws SQLException {
        try {
            database.doCreateEnvironmentDataTable(ensureConnection(), dimensions);
            log.debug("Environment data table created with {} dimensions", dimensions);
        } catch (Exception e) {
            log.error("Failed to create environment_data table");
            recordError("CREATE_TABLE_FAILED", "Failed to create environment_data table",
                       "Dimensions: " + dimensions + ", Error: " + e.getMessage());
            throw new SQLException("Failed to create environment_data table", e);
        }
    }
    
    @Override
    public void writeEnvironmentCells(long tickNumber, List<CellState> cells, List<int[]> coordinates) 
            throws SQLException {
        if (cells.size() != coordinates.size()) {
            throw new IllegalArgumentException(
                "Cells and coordinates list lengths must match: " + cells.size() + " vs " + coordinates.size());
        }
        
        long startNanos = System.nanoTime();
        
        try {
            database.doWriteEnvironmentCells(ensureConnection(), tickNumber, cells, coordinates);
            cellsWritten.addAndGet(cells.size());
            batchesWritten.incrementAndGet();
            writeLatency.record(System.nanoTime() - startNanos);
            
        } catch (Exception e) {
            writeErrors.incrementAndGet();
            log.warn("Failed to write {} cells for tick {}", cells.size(), tickNumber);
            recordError("WRITE_CELLS_FAILED", "Failed to write environment cells",
                       "Tick: " + tickNumber + ", Cells: " + cells.size() + ", Error: " + e.getMessage());
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
        
        // Latency percentiles in milliseconds - O(windowSeconds × buckets) = O(constant)
        metrics.put("write_latency_p50_ms", writeLatency.getPercentile(50) / 1_000_000.0);
        metrics.put("write_latency_p95_ms", writeLatency.getPercentile(95) / 1_000_000.0);
        metrics.put("write_latency_p99_ms", writeLatency.getPercentile(99) / 1_000_000.0);
        metrics.put("write_latency_avg_ms", writeLatency.getAverage() / 1_000_000.0);
    }
}
```

### 4. H2 Database Implementation

**File:** `src/main/java/org/evochora/datapipeline/resources/database/H2Database.java`

Add to AbstractDatabaseResource:

```java
// New abstract methods in AbstractDatabaseResource
protected abstract void doCreateEnvironmentDataTable(Object connection, int dimensions) 
        throws Exception;

protected abstract void doWriteEnvironmentCells(Object connection, long tickNumber, 
        List<CellState> cells, List<int[]> coordinates) throws Exception;
```

Add to H2Database implementation:

```java
private PreparedStatement environmentMergeStatement;
private int cachedDimensions = -1;

@Override
protected void doCreateEnvironmentDataTable(Object connection, int dimensions) throws Exception {
    Connection conn = (Connection) connection;
    
    // Build dynamic CREATE TABLE statement
    StringBuilder sql = new StringBuilder();
    sql.append("CREATE TABLE IF NOT EXISTS environment_data (");
    sql.append("tick_number BIGINT NOT NULL, ");
    
    for (int i = 0; i < dimensions; i++) {
        sql.append("pos_").append(i).append(" INT NOT NULL, ");
    }
    
    sql.append("molecule_type INT NOT NULL, ");
    sql.append("molecule_value INT NOT NULL, ");
    sql.append("owner_id INT NOT NULL, ");
    
    // Primary key - composite key for MERGE
    sql.append("PRIMARY KEY (tick_number");
    for (int i = 0; i < dimensions; i++) {
        sql.append(", pos_").append(i);
    }
    sql.append(")");
    sql.append(")");
    
    try (Statement stmt = conn.createStatement()) {
        stmt.execute(sql.toString());
    }
    
    // Create indexes for area queries
    createEnvironmentIndexes(conn, dimensions);
    
    // Prepare MERGE statement
    prepareEnvironmentMergeStatement(conn, dimensions);
    
    this.cachedDimensions = dimensions;
}

private void createEnvironmentIndexes(Connection conn, int dimensions) throws SQLException {
    // Index for tick-based queries (most common)
    conn.createStatement().execute(
        "CREATE INDEX IF NOT EXISTS idx_env_tick ON environment_data (tick_number)"
    );
    
    // Index for spatial queries (area queries)
    StringBuilder idxSql = new StringBuilder();
    idxSql.append("CREATE INDEX IF NOT EXISTS idx_env_spatial ON environment_data (tick_number");
    for (int i = 0; i < dimensions; i++) {
        idxSql.append(", pos_").append(i);
    }
    idxSql.append(")");
    
    conn.createStatement().execute(idxSql.toString());
}

private void prepareEnvironmentMergeStatement(Connection conn, int dimensions) throws SQLException {
    // Close old statement if exists
    if (environmentMergeStatement != null) {
        environmentMergeStatement.close();
    }
    
    // Build dynamic MERGE statement
    StringBuilder sql = new StringBuilder();
    sql.append("MERGE INTO environment_data (tick_number");
    
    for (int i = 0; i < dimensions; i++) {
        sql.append(", pos_").append(i);
    }
    
    sql.append(", molecule_type, molecule_value, owner_id) ");
    
    // KEY clause - defines unique constraint for MERGE
    sql.append("KEY (tick_number");
    for (int i = 0; i < dimensions; i++) {
        sql.append(", pos_").append(i);
    }
    sql.append(") ");
    
    // VALUES clause
    sql.append("VALUES (?");  // tick_number
    for (int i = 0; i < dimensions; i++) {
        sql.append(", ?");    // pos_i
    }
    sql.append(", ?, ?, ?)"); // molecule_type, value, owner_id
    
    this.environmentMergeStatement = conn.prepareStatement(sql.toString());
}

@Override
protected void doWriteEnvironmentCells(Object connection, long tickNumber,
                                       List<CellState> cells, List<int[]> coordinates) 
        throws Exception {
    Connection conn = (Connection) connection;
    
    // Ensure merge statement is prepared
    if (environmentMergeStatement == null) {
        throw new IllegalStateException(
            "Environment data table not created. Call createEnvironmentDataTable() first.");
    }
    
    // Batch insert with MERGE
    for (int i = 0; i < cells.size(); i++) {
        CellState cell = cells.get(i);
        int[] coord = coordinates.get(i);
        
        int paramIdx = 1;
        environmentMergeStatement.setLong(paramIdx++, tickNumber);
        
        // Dynamic position columns
        for (int c : coord) {
            environmentMergeStatement.setInt(paramIdx++, c);
        }
        
        environmentMergeStatement.setInt(paramIdx++, cell.getMoleculeType());
        environmentMergeStatement.setInt(paramIdx++, cell.getMoleculeValue());
        environmentMergeStatement.setInt(paramIdx++, cell.getOwnerId());
        
        environmentMergeStatement.addBatch();
    }
    
    environmentMergeStatement.executeBatch();
    conn.commit();
}

@Override
protected void closeConnectionPool() throws Exception {
    // Clean up prepared statement
    if (environmentMergeStatement != null) {
        try {
            environmentMergeStatement.close();
        } catch (SQLException e) {
            log.debug("Error closing environment merge statement: {}", e.getMessage());
        }
        environmentMergeStatement = null;
    }
    
    // Existing cleanup code...
    if (dataSource != null && !dataSource.isClosed()) {
        dataSource.close();
    }
}
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
        // Load metadata (provided by MetadataReadingComponent)
        SimulationMetadata metadata = components.metadata.getMetadata();
        
        // Extract environment properties for coordinate conversion
        this.envProps = extractEnvironmentProperties(metadata);
        
        // Create database table (idempotent)
        int dimensions = envProps.getWorldShape().length;
        database.createEnvironmentDataTable(dimensions);
        
        log.debug("Environment schema prepared: {} dimensions", dimensions);
    }
    
    @Override
    protected void flushTicks(List<TickData> ticks) throws Exception {
        // Extract all cells from all ticks
        List<CellEntry> allCells = new ArrayList<>();
        
        for (TickData tick : ticks) {
            long tickNumber = tick.getTickNumber();
            
            // Extract cells (already sparse in protobuf - only non-empty cells)
            for (CellState cell : tick.getCellsList()) {
                allCells.add(new CellEntry(tickNumber, cell));
            }
        }
        
        if (allCells.isEmpty()) {
            log.debug("No cells to flush (all ticks empty)");
            return;
        }
        
        // Convert flat_index to coordinates (batch operation)
        List<int[]> coordinates = new ArrayList<>(allCells.size());
        for (CellEntry entry : allCells) {
            int flatIndex = entry.cell.getFlatIndex();
            int[] coord = envProps.flatIndexToCoordinates(flatIndex);
            coordinates.add(coord);
        }
        
        // Group by tick number for efficient writes
        Map<Long, TickCellBatch> batches = groupByTick(allCells, coordinates);
        
        // Write to database (MERGE ensures idempotency)
        for (TickCellBatch batch : batches.values()) {
            database.writeEnvironmentCells(
                batch.tickNumber,
                batch.cells,
                batch.coordinates
            );
        }
        
        log.debug("Flushed {} cells from {} ticks", allCells.size(), ticks.size());
    }
    
    /**
     * Extracts EnvironmentProperties from SimulationMetadata.
     */
    private EnvironmentProperties extractEnvironmentProperties(SimulationMetadata metadata) {
        // Extract world shape from metadata
        int[] worldShape = metadata.getEnvironment().getDimensionsList().stream()
            .mapToInt(SimulationMetadata.EnvironmentDimension::getSize)
            .toArray();
        
        // Extract topology
        boolean isToroidal = "TORUS".equalsIgnoreCase(
            metadata.getEnvironment().getTopology()
        );
        
        return new EnvironmentProperties(worldShape, isToroidal);
    }
    
    /**
     * Groups cells by tick number for efficient batch writes.
     */
    private Map<Long, TickCellBatch> groupByTick(List<CellEntry> cells, List<int[]> coordinates) {
        Map<Long, TickCellBatch> batches = new java.util.LinkedHashMap<>();
        
        for (int i = 0; i < cells.size(); i++) {
            CellEntry entry = cells.get(i);
            int[] coord = coordinates.get(i);
            
            batches.computeIfAbsent(entry.tickNumber, TickCellBatch::new)
                   .add(entry.cell, coord);
        }
        
        return batches;
    }
    
    /**
     * Helper record for cell with tick number.
     */
    private record CellEntry(long tickNumber, CellState cell) {}
    
    /**
     * Helper class for grouping cells by tick.
     */
    private static class TickCellBatch {
        final long tickNumber;
        final List<CellState> cells = new ArrayList<>();
        final List<int[]> coordinates = new ArrayList<>();
        
        TickCellBatch(long tickNumber) {
            this.tickNumber = tickNumber;
        }
        
        void add(CellState cell, int[] coord) {
            cells.add(cell);
            coordinates.add(coord);
        }
    }
}
```

## Configuration

**File:** `evochora.conf`

```hocon
resources {
  # Existing resources...
  
  indexDatabase {
    className = "org.evochora.datapipeline.resources.database.H2Database"
    options {
      jdbcUrl = "jdbc:h2:${user.home}/evochora/data/evochora;MODE=PostgreSQL;AUTO_SERVER=TRUE"
      username = "sa"
      password = ""
      maxPoolSize = 10
      minIdle = 2
    }
  }
}

services {
  # Existing services...
  
  environmentIndexer {
    className = "org.evochora.datapipeline.services.indexers.EnvironmentIndexer"
    
    resources {
      # Topic for batch notifications (competing consumer pattern)
      topic = "topic-read:batchTopic?consumerGroup=environmentIndexer"
      
      # Storage for reading TickData batches
      storage = "storage-read:tickStorage"
      
      # Metadata for simulation configuration
      metadata = "db-meta-read:indexDatabase"
      
      # Database for writing environment cells
      database = "db-env-write:indexDatabase"
    }
    
    options {
      # Optional: explicit runId (otherwise discovered from storage)
      runId = ${?pipeline.services.runId}
      
      # Metadata component (standardized config names)
      metadataPollIntervalMs = 1000
      metadataMaxPollDurationMs = 300000
      
      # Buffering component (standardized config names)
      insertBatchSize = 1000    # Cells per database batch (MERGE)
      flushTimeoutMs = 5000     # Flush after 5s even if buffer not full
      # Note: topicPollTimeoutMs automatically set to flushTimeoutMs
    }
  }
}
```

**Competing Consumers Configuration:**

To run multiple indexer instances for higher throughput:

```hocon
services {
  environmentIndexer1 {
    className = "org.evochora.datapipeline.services.indexers.EnvironmentIndexer"
    resources {
      topic = "topic-read:batchTopic?consumerGroup=environmentIndexer"  # Same group!
      storage = "storage-read:tickStorage"
      metadata = "db-meta-read:indexDatabase"
      database = "db-env-write:indexDatabase"
    }
    options { /* same as above */ }
  }
  
  environmentIndexer2 {
    className = "org.evochora.datapipeline.services.indexers.EnvironmentIndexer"
    resources {
      topic = "topic-read:batchTopic?consumerGroup=environmentIndexer"  # Same group!
      storage = "storage-read:tickStorage"
      metadata = "db-meta-read:indexDatabase"
      database = "db-env-write:indexDatabase"
    }
    options { /* same as above */ }
  }
}
```

**Coordination:**
- **Topic:** Consumer group ensures each batch processed by exactly ONE instance
- **Database:** MERGE ensures idempotent writes (concurrent writes safe)
- **Schema:** CREATE TABLE IF NOT EXISTS ensures only one instance creates table

## Database Schema

**Table Structure (dimension-agnostic):**

```sql
-- 2D Example
CREATE TABLE IF NOT EXISTS environment_data (
    tick_number BIGINT NOT NULL,
    pos_0 INT NOT NULL,
    pos_1 INT NOT NULL,
    molecule_type INT NOT NULL,
    molecule_value INT NOT NULL,
    owner_id INT NOT NULL,
    PRIMARY KEY (tick_number, pos_0, pos_1)
);

-- 3D Example (automatically adapts)
CREATE TABLE IF NOT EXISTS environment_data (
    tick_number BIGINT NOT NULL,
    pos_0 INT NOT NULL,
    pos_1 INT NOT NULL,
    pos_2 INT NOT NULL,
    molecule_type INT NOT NULL,
    molecule_value INT NOT NULL,
    owner_id INT NOT NULL,
    PRIMARY KEY (tick_number, pos_0, pos_1, pos_2)
);
```

**Indexes for Performance:**

```sql
-- Tick-based queries (most common)
CREATE INDEX IF NOT EXISTS idx_env_tick ON environment_data (tick_number);

-- Spatial queries (area queries)
CREATE INDEX IF NOT EXISTS idx_env_spatial ON environment_data (
    tick_number, pos_0, pos_1 /*, pos_2, ... */
);
```

**Storage Characteristics:**

- **Sparse:** Only non-empty cells stored (typ. 50% space savings)
- **Composite Primary Key:** (tick_number, pos_0, pos_1, ...) ensures uniqueness
- **MERGE-friendly:** Primary key enables efficient MERGE operations

## HTTP API Query Examples

**Area Query (primary use case):**

```sql
-- Get all cells in region at specific tick
SELECT * FROM environment_data
WHERE tick_number = 1000
  AND pos_0 BETWEEN 10 AND 20
  AND pos_1 BETWEEN 30 AND 40;
```

**Single Cell Query:**

```sql
-- Get specific cell at specific tick
SELECT * FROM environment_data
WHERE tick_number = 1000
  AND pos_0 = 15
  AND pos_1 = 35;
  
-- Returns 0 rows if cell is empty (sparse storage)
```

**Tick Diff (future feature):**

```sql
-- Client-side: Query tick X and Y, compute diff
-- Tick 1000
SELECT * FROM environment_data WHERE tick_number = 1000 AND pos_0 BETWEEN ... ;

-- Tick 2000
SELECT * FROM environment_data WHERE tick_number = 2000 AND pos_0 BETWEEN ... ;

-- Client computes diff
```

## Performance Considerations

### Memory Usage

**Per Tick (100×100 2D, 50% occupied = 5,000 cells):**

- CellEntry object: ~40 bytes
- Coordinate array: ~16 bytes (2D)
- **Total:** ~56 bytes × 5,000 = 280 KB per tick

**With insertBatchSize=1000:**
- Buffer holds ~10 ticks = 2.8 MB
- Negligible compared to JVM heap

### Database Performance

**MERGE Batch (1,000 cells):**
- H2 Batch MERGE: ~10-20ms (SSD)
- Expected throughput: ~50,000-100,000 cells/sec per indexer

**Scalability:**
- Competing consumers: Linear scalability up to ~4-8 instances
- Bottleneck: Database connection pool (increase maxPoolSize)

### Coordinate Conversion Performance

**EnvironmentProperties.flatIndexToCoordinates():**
- First call: O(dimensions) - calculate strides
- Subsequent calls: O(dimensions) - cached strides
- Typical: O(3) for 3D world = negligible overhead

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
void testGroupByTick() {
    List<CellEntry> cells = List.of(
        new CellEntry(1000, createCell(0, 1, 2)),
        new CellEntry(1000, createCell(1, 3, 4)),
        new CellEntry(2000, createCell(2, 5, 6))
    );
    
    Map<Long, TickCellBatch> batches = indexer.groupByTick(cells, coordinates);
    
    assertThat(batches).hasSize(2);
    assertThat(batches.get(1000L).cells).hasSize(2);
    assertThat(batches.get(2000L).cells).hasSize(1);
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
    
    // Verify: Coordinates converted correctly
    CellRecord cell = queryCellByFlatIndex(tick=0, flatIndex=25);
    assertThat(cell.pos_0).isEqualTo(25); // 25 % 100 = 25
    assertThat(cell.pos_1).isEqualTo(0);  // 25 / 100 = 0
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

1. ✅ EnvironmentIndexer extends AbstractBatchIndexer
2. ✅ Uses METADATA + BUFFERING components (default)
3. ✅ Database schema created idempotently with variable dimensions
4. ✅ EnvironmentProperties.flatIndexToCoordinates() implemented with caching
5. ✅ MERGE-based writes ensure 100% idempotency
6. ✅ Only non-empty cells stored (sparse)
7. ✅ Competing consumers work (multiple indexer instances)
8. ✅ Unit tests pass (extraction, grouping, conversion)
9. ✅ Integration tests pass (end-to-end, idempotency, competing consumers, dimensions)
10. ✅ HTTP API can query: `SELECT * FROM environment_data WHERE tick_number = ? AND pos_0 BETWEEN ? AND ?`

## Implementation Notes

**No backward compatibility** - this is a new indexer.

**Implementation order:**
1. EnvironmentProperties.flatIndexToCoordinates() (with tests)
2. IEnvironmentDataWriter interface
3. EnvironmentDataWriterWrapper
4. H2Database implementation
5. EnvironmentIndexer
6. Integration tests
7. Configuration

**Testing priorities:**
1. Coordinate conversion correctness (all dimensions)
2. MERGE idempotency (duplicate batches)
3. Competing consumers (no data loss)
4. Schema adaptation (1D to N-D)

---

**Phase Tracking:**
- Phase 14.2: Indexer Foundation ✅ Completed
- Phase 14.3: Environment Indexer ⏳ This Document
- Phase 14.4: Organism Indexer (future)
- Phase 14.5: HTTP API (future)

