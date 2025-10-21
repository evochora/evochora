# Data Pipeline V3 - HTTP API Database Reader (Phase 15)

## Goal

Implement thread-safe, database-agnostic read access for HTTP API controllers with per-request schema selection (run-id). Controllers need to query indexed data (environment cells, metadata, organisms) from the database without blocking each other or creating connection leaks.

## Scope

**This phase implements:**
1. IDatabaseReaderProvider factory interface (stateless, ServiceRegistry-compatible)
2. IDatabaseReader per-request product interface (bundles all read capabilities)
3. IEnvironmentDataReader capability interface (spatial-query ready)
4. H2Database implementation of IDatabaseReaderProvider
5. H2DatabaseReader implementation of IDatabaseReader (all capabilities in one class)
6. HttpServerProcess integration (DI via ServiceRegistry)
7. EnvironmentController example implementation
8. Configuration schema for controller database access

**This phase does NOT implement:**
- Organism data reading (IOrganismDataReader) - future phase
- This is shown in "Future Extensions" section for context only

## Success Criteria

Upon completion:
1. IDatabaseReaderProvider registered in ServiceRegistry (stateless factory)
2. Controllers can create per-request IDatabaseReader via factory
3. Each IDatabaseReader holds dedicated connection from pool with schema set
4. Connection automatically returned to pool via try-with-resources
5. Multiple concurrent requests with different run-ids work correctly (thread-safe)
6. API responds within 100-250ms for typical queries (1000×1000 environment, viewport regions)
7. No connection leaks under load or error conditions
8. Database implementation is abstracted (H2-specific code isolated)
9. New database backends can be implemented without modifying existing controllers
10. Integration tests verify concurrent access and error handling
11. Example EnvironmentController works with real data

## Prerequisites

- Phase 14.3: EnvironmentIndexer writes environment_ticks table (completed)
- Phase 13: H2Database with HikariCP connection pooling (completed)
- Phase 8: HttpServerProcess with ServiceRegistry DI (completed)
- Phase 8: AbstractController pattern (completed)
- IH2EnvStorageStrategy interface exists (write methods already defined)
- EnvironmentProperties.flatIndexToCoordinates() method exists
- CellStateList protobuf message in tickdata_contracts.proto

## Package Structure

```
org.evochora.datapipeline
├── api
│   ├── resources
│   │   └── database
│   │       ├── IDatabaseReaderProvider.java       // Factory interface (ServiceRegistry)
│   │       ├── IDatabaseReader.java               // Product interface (bundles capabilities)
│   │       ├── IEnvironmentDataReader.java        // Capability: environment queries
│   │       ├── IMetadataReader.java               // Capability: metadata queries
│   │       ├── IOrganismDataReader.java           // Capability: organism queries (future)
│   │       ├── SpatialRegion.java                 // N-D bounding box for queries
│   │       ├── CellWithCoordinates.java           // Response DTO (coordinates + cell data)
│   │       └── MetadataNotFoundException.java     // Exception for missing metadata
│   └── controllers
│       └── EnvironmentController.java             // Example HTTP controller
│
├── resources
│   └── database
│       ├── AbstractDatabaseResource.java          // Base class (existing)
│       ├── H2Database.java                        // H2 implementation (IDatabaseReaderProvider)
│       │                                          // Also implements: IMetadataReader (for indexers)
│       ├── MetadataReaderWrapper.java             // Wrapper for IMetadataReader (existing)
│       └── h2
│           ├── IH2EnvStorageStrategy.java         // Strategy interface (existing, extended)
│           ├── AbstractH2EnvStorageStrategy.java  // Base class (existing)
│           ├── SingleBlobStrategy.java            // BLOB storage strategy (extended with read)
│           └── H2DatabaseReader.java              // Per-request reader (all capabilities)
│
└── utils
    └── H2SchemaUtil.java                          // Schema operations (existing)

org.evochora.runtime
└── model
    └── EnvironmentProperties.java                 // Coordinate conversion (extended)
```

**Key Principles:**
- **API Interfaces** (`api.resources.database`): Public contracts, database-agnostic
- **Implementation** (`resources.database`): Concrete implementations, H2-specific code isolated
- **H2-Specific** (`resources.database.h2`): Strategy pattern for storage flexibility
- **Controllers** (`api.controllers`): HTTP endpoints, depend only on API interfaces

**Dependency Flow:**
```
EnvironmentController
    ↓ (depends on)
IDatabaseReaderProvider (interface)
    ↓ (implemented by)
H2Database
    ↓ (creates)
H2DatabaseReader
    ↓ (uses)
IH2EnvStorageStrategy (e.g., SingleBlobStrategy)
```

## Architectural Context

### Problem Statement

HTTP API Controllers in `HttpServerProcess` need thread-safe, read-only database access with per-request schema selection (run-id). The challenges:

**Per-Request Schema Selection:**
- Each HTTP request may query different run-id: `GET /api/:tick/environment?runId=X`
- Database uses schemas for run isolation: `SET SCHEMA run_20251021_...`
- Concurrent requests with different run-ids must not interfere

**Thread Safety Requirements:**
- Multiple concurrent HTTP requests
- Shared connection pool (HikariCP)
- No blocking between requests
- No connection leaks

**Abstraction Requirements:**
- Controllers must be database-agnostic (work with H2, PostgreSQL, etc.)
- No H2-specific code in controllers
- Database implementation swappable via configuration

**ServiceRegistry Constraints:**
- Registry should only contain stateless factories
- No stateful connections or readers in registry
- Prevent connection leaks in long-lived registry objects

**Extensibility Requirements:**
- New database backends easy to implement (minimal files)
- Clear implementation checklist for new backends
- Copy-paste existing implementation as template

### Design Consistency

This design follows established patterns from other pipeline components:

**Queue Pattern (Reference):**
```
ServiceManager → IInputResource (per-service wrapper)
                      ↓
                InMemoryBlockingQueue (shared pool)
                      ↓
                Service.take() (per-call operation)
```

**Storage Pattern (Reference):**
```
ServiceManager → IStorageReadResource (per-service wrapper)
                      ↓
                FileSystemStorage (shared filesystem)
                      ↓
                Service.openReader() (per-call stream)
```

**Database Reader Pattern (This Design):**
```
ServiceRegistry → IDatabaseReaderProvider (stateless factory)
                      ↓
                H2Database (shared connection pool)
                      ↓
                provider.createReader(runId) (per-request connection)
```

**Key Difference:** Controllers use ServiceRegistry (not ServiceManager) because they're not pipeline services. HttpServerProcess bridges the gap by registering database provider from ServiceManager into its internal controller registry.

## Solution: Factory Pattern with Per-Request Readers

We use a **Factory Pattern** where:
- **Factory** (`IDatabaseReaderProvider`) is a stateless singleton registered in `ServiceRegistry`
- **Product** (`IDatabaseReader`) is a stateful per-request object with dedicated connection

```
ServiceRegistry → IDatabaseReaderProvider (Factory, stateless)
                        ↓ createReader(runId)
                  IDatabaseReader (Product, per-request, AutoCloseable)
```

## 3. Architecture Overview

### 3.1 Key Interfaces

#### IDatabaseReaderProvider (Factory)
```java
package org.evochora.datapipeline.api.resources.database;

/**
 * Factory for creating per-request database readers with run-specific schemas.
 * <p>
 * Thread-safe singleton that can be registered in ServiceRegistry.
 * Implementations must manage connection pools internally and provide
 * dedicated connections for each reader instance.
 */
public interface IDatabaseReaderProvider {
    /**
     * Creates a new database reader for the given simulation run.
     * <p>
     * The returned reader:
     * <ul>
     *   <li>Holds a dedicated connection from the pool</li>
     *   <li>Has schema set to the specified runId</li>
     *   <li>MUST be closed after use (try-with-resources)</li>
     * </ul>
     * 
     * @param runId Simulation run ID (schema name)
     * @return Per-request reader (must be closed to return connection to pool)
     * @throws RuntimeException if connection cannot be acquired or schema cannot be set
     */
    IDatabaseReader createReader(String runId);
    
    /**
     * Finds the latest (most recent) run-id in the database.
     * <p>
     * Used for fallback when no explicit run-id is specified.
     * Run-IDs have chronologically sortable timestamp prefix.
     * 
     * @return Latest run-id or null if database is empty (no runs)
     * @throws SQLException if database query fails
     */
    String findLatestRunId() throws SQLException;
}
```

#### IDatabaseReader (Product)
```java
package org.evochora.datapipeline.api.resources.database;

/**
 * Per-request database reader with run-specific schema.
 * <p>
 * Bundles all read capabilities (environment, metadata, organisms, etc.).
 * Each instance holds a dedicated connection with schema already set.
 * <p>
 * <strong>Thread Safety:</strong> Thread-safe because each instance has its own connection.
 * <p>
 * <strong>Usage:</strong> MUST be used with try-with-resources to ensure connection
 * is returned to pool:
 * <pre>
 * try (IDatabaseReader reader = provider.createReader(runId)) {
 *     List&lt;Cell&gt; cells = reader.readEnvironmentRegion(tick, region);
 *     // ...
 * } // Automatic connection return to pool
 * </pre>
 */
public interface IDatabaseReader extends IEnvironmentDataReader, 
                                        IMetadataReader,
                                        // Future: IOrganismDataReader
                                        AutoCloseable {
    /**
     * Closes this reader and returns the connection to the pool.
     * <p>
     * Automatically called when used with try-with-resources.
     */
    @Override
    void close();
}
```

#### IEnvironmentDataReader (Capability)
```java
package org.evochora.datapipeline.api.resources.database;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import org.evochora.datapipeline.api.contracts.CellState;
import java.sql.SQLException;
import java.util.List;

/**
 * Capability for reading environment cell data from database.
 * <p>
 * Design is spatial-query ready: region parameter allows future strategies
 * to implement efficient spatial filtering at the database level.
 */
public interface IEnvironmentDataReader {
    
    /**
     * Reads environment cells for a tick within an optional spatial region.
     * <p>
     * Returns cells with coordinates (not flatIndex) for client-side rendering.
     * <p>
     * <strong>Current Implementation (SingleBlobStrategy):</strong>
     * <ul>
     *   <li>Reads entire tick BLOB from database</li>
     *   <li>Decompresses if compression enabled</li>
     *   <li>Deserializes Protobuf CellStateList</li>
     *   <li>Filters by region in Java (if region != null)</li>
     *   <li>Converts flatIndex → coordinates</li>
     * </ul>
     * <p>
     * <strong>Future Strategy (SpatialIndexStrategy):</strong>
     * Region filtering at database level with spatial indexes.
     * 
     * @param tickNumber Tick to read
     * @param region Spatial bounds (null = all cells, future: database-level filtering)
     * @return List of cells with coordinates within region
     * @throws SQLException if database read fails
     */
    List<CellWithCoordinates> readEnvironmentRegion(long tickNumber, SpatialRegion region) 
        throws SQLException;
}

/**
 * Cell data with coordinates for client rendering.
 * <p>
 * Uses Java Record with {@code @JsonAutoDetect} for efficient JSON serialization.
 * Direct field access avoids reflection overhead (~25-30% faster than POJO serialization).
 * <p>
 * <strong>Performance (50k cells):</strong>
 * <ul>
 *   <li>Record + Jackson: ~22-25ms (coordinate conversion + serialization)</li>
 *   <li>POJO + Reflection: ~31-36ms (baseline)</li>
 *   <li>Streaming JSON: ~7-8ms (optimal, see below)</li>
 * </ul>
 * <p>
 * <strong>Optimization Potential:</strong> For very large viewports (&gt;100k cells),
 * streaming JSON with {@code JsonGenerator} can save additional ~15-20ms by eliminating
 * intermediate objects entirely. However, this requires manual JSON generation and
 * significantly reduces code maintainability. For typical viewports (250×250 = ~31k cells),
 * the Record-based approach provides the best balance of performance and simplicity.
 * 
 * @see com.fasterxml.jackson.core.JsonGenerator for streaming alternative
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public record CellWithCoordinates(
    int[] coordinates,   // e.g., [x, y] or [x, y, z]
    int moleculeType,
    int moleculeValue,
    int ownerId
) {}

/**
 * Spatial region bounds for n-dimensional filtering.
 * <p>
 * Pure data class - no logic. Filtering logic is strategy-specific:
 * <ul>
 *   <li><b>SingleBlobStrategy:</b> Java-based filtering using bounds</li>
 *   <li><b>SpatialIndexStrategy:</b> SQL WHERE clause using bounds</li>
 * </ul>
 * <p>
 * <strong>Format:</strong> Interleaved min/max pairs per dimension:
 * <ul>
 *   <li>1D: [min_x, max_x]</li>
 *   <li>2D: [min_x, max_x, min_y, max_y]</li>
 *   <li>3D: [min_x, max_x, min_y, max_y, min_z, max_z]</li>
 *   <li>ND: [min_0, max_0, min_1, max_1, ..., min_n, max_n]</li>
 * </ul>
 * <p>
 * Array length must be 2 × dimensions.
 * <p>
 * <strong>Example Queries:</strong>
 * <ul>
 *   <li>2D: {@code region=0,50,0,50} → x:[0,50], y:[0,50]</li>
 *   <li>3D: {@code region=0,100,0,100,0,50} → x:[0,100], y:[0,100], z:[0,50]</li>
 * </ul>
 */
class SpatialRegion {
    public final int[] bounds;  // Interleaved min/max pairs: [min_0, max_0, min_1, max_1, ...]
    
    /**
     * Returns number of dimensions.
     * @return bounds.length / 2
     */
    public int getDimensions() {
        return bounds.length / 2;
    }
}
```

### 3.2 HTTP API Request/Response Format

#### Environment Data Endpoint

**Request:**
```http
GET /visualizer/api/:tick/environment?region=<bounds>[&runId=<id>]
```

**Path Parameters:**
- `tick` (long): Tick number to query

**Query Parameters:**
- `region` (string, required): Comma-separated N-D bounding box
  - Format: `min_0,max_0,min_1,max_1,...,min_N,max_N`
  - 2D example: `0,100,0,100` → x:[0,100], y:[0,100]
  - 3D example: `0,100,0,100,0,50` → x:[0,100], y:[0,100], z:[0,50]
- `runId` (string, optional): Simulation run ID
  - Fallback hierarchy: query param → controller config → latest from DB

**Response (Success - 200 OK):**
```json
{
  "tick": 12345,
  "runId": "20251021_143025_AB12CD",
  "cellCount": 1523,
  "cells": [
    {
      "coordinates": [42, 73],
      "moleculeType": 1,
      "moleculeValue": 255,
      "ownerId": 7
    },
    {
      "coordinates": [43, 73],
      "moleculeType": 2,
      "moleculeValue": 100,
      "ownerId": 7
    }
  ]
}
```

**Response Fields:**
- `tick` (long): Requested tick number
- `runId` (string): Active simulation run ID
- `cellCount` (int): Number of cells in response
- `cells` (array): Cell data
  - `coordinates` (int[]): N-dimensional coordinates
  - `moleculeType` (int): Type of molecule (1=CODE, 2=DATA, 3=ENERGY, etc.)
  - `moleculeValue` (int): Molecule value (instruction or data)
  - `ownerId` (int): Organism ID that owns this cell (0=unowned)

**Response (Error - 400 Bad Request):**
```json
{
  "error": "Invalid region format",
  "message": "Region must have even number of values (min/max pairs)",
  "details": "Provided: '0,100,0' (3 values)"
}
```

**Response (Error - 404 Not Found):**
```json
{
  "error": "Tick not found",
  "message": "No data for tick 12345 in run '20251021_143025_AB12CD'",
  "details": "Run exists but tick not yet indexed or out of range"
}
```

**Response (Error - 404 Not Found - No Run):**
```json
{
  "error": "Run not found",
  "message": "No simulation runs found in database",
  "details": "Database is empty or no runs indexed yet"
}
```

**Response (Error - 500 Internal Server Error):**
```json
{
  "error": "Database error",
  "message": "Failed to read environment data",
  "details": "Connection timeout or database unavailable"
}
```

#### Metadata Endpoint

**Request:**
```http
GET /visualizer/api/metadata[?runId=<id>]
```

**Query Parameters:**
- `runId` (string, optional): Simulation run ID (fallback: controller config → latest)

**Response (Success - 200 OK):**
```json
{
  "runId": "20251021_143025_AB12CD",
  "dimensions": 2,
  "shape": [1000, 1000],
  "isToroidal": true,
  "startTick": 0,
  "endTick": 150000,
  "seed": 42,
  "samplingInterval": 10
}
```

**Response Fields:**
- `runId` (string): Simulation run ID
- `dimensions` (int): Number of dimensions (2, 3, 4, etc.)
- `shape` (int[]): Size of each dimension
- `isToroidal` (boolean): Whether world wraps at edges
- `startTick` (long): First tick in database
- `endTick` (long): Last tick in database
- `seed` (int): Random seed used for simulation
- `samplingInterval` (int): Ticks between indexed samples

**Performance:**
- Environment query: 100-250ms (1000×1000 @ 50% occupancy, 250×250 viewport)
- Metadata query: <10ms (cached in memory)

### 3.3 Implementation in H2Database

```java
public class H2Database extends AbstractDatabaseResource 
                         implements IDatabaseReaderProvider,
                                   IMetadataReader,  // For indexers
                                   AutoCloseable {
    
    private final HikariDataSource dataSource;
    private final IH2EnvStorageStrategy envStorageStrategy;
    
    // Metadata cache: Immutable after simulation start, safe to cache aggressively
    // Uses LinkedHashMap with LRU eviction (no external dependencies needed)
    // Typical usage: Few active run-ids (1-5), many requests per run-id (viewport scrolling)
    // Cache hit rate: >99% in production
    private final Map<String, SimulationMetadata> metadataCache;
    private final int maxCacheSize;
    
    public H2Database(String name, Config options) {
        super(name, options);
        
        // ... existing HikariCP setup ...
        
        // Initialize metadata cache (LRU with automatic size limit)
        this.maxCacheSize = options.hasPath("metadataCacheSize") 
            ? options.getInt("metadataCacheSize") 
            : 100;  // Default: 100 run-ids (~100-200 KB)
        
        this.metadataCache = Collections.synchronizedMap(
            new LinkedHashMap<String, SimulationMetadata>(maxCacheSize, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SimulationMetadata> eldest) {
                    return size() > maxCacheSize;
                }
            }
        );
        
        log.debug("Metadata cache initialized with LRU eviction (maxSize={})", maxCacheSize);
    }
    
    @Override
    public IDatabaseReader createReader(String runId) {
        try {
            // 1. Get connection from pool
            Connection conn = dataSource.getConnection();
            
            // 2. Set schema for this connection only
            H2SchemaUtil.setSchema(conn, runId);
            
            // 3. Return per-request reader with dedicated connection
            return new H2DatabaseReader(conn, this, envStorageStrategy, runId);
            
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create reader for runId: " + runId, e);
        }
    }
    
    /**
     * Gets metadata with LRU caching.
     * <p>
     * Metadata is immutable after simulation start, making it safe to cache indefinitely.
     * LRU eviction ensures memory bounds (oldest unused entries removed automatically).
     * Cache dramatically reduces DB load for typical traffic patterns where
     * many viewport queries hit the same run-id.
     * <p>
     * <strong>Performance:</strong>
     * <ul>
     *   <li>Cache miss: ~5-10ms (SQL query + deserialization)</li>
     *   <li>Cache hit: ~0.05ms (synchronized Map lookup)</li>
     *   <li>Cache hit rate: >99% in production</li>
     * </ul>
     * <p>
     * <strong>Thread Safety:</strong> Uses {@code Collections.synchronizedMap} for
     * concurrent access. {@code computeIfAbsent} is atomic.
     * 
     * @param conn Database connection (used only on cache miss)
     * @param runId Simulation run ID
     * @return Cached or freshly loaded metadata
     * @throws SQLException if database query fails (cache miss only)
     */
    SimulationMetadata getMetadataInternal(Connection conn, String runId) 
            throws SQLException {
        try {
            return metadataCache.computeIfAbsent(runId, key -> {
                try {
                    return (SimulationMetadata) doGetMetadata(conn, key);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to load metadata for runId: " + key, e);
                }
            });
        } catch (RuntimeException e) {
            // Unwrap SQLException from computeIfAbsent
            if (e.getCause() instanceof SQLException) {
                throw (SQLException) e.getCause();
            }
            throw e;
        }
    }
    
    boolean hasMetadataInternal(Connection conn, String runId) throws SQLException {
        // Existing implementation from AbstractDatabaseResource
        return doHasMetadata(conn, runId);
    }
    
    @Override
    public String findLatestRunId() throws SQLException {
        // Delegates to IMetadataReader.getRunIdInCurrentSchema()
        // See implementation in H2Database.doGetRunIdInCurrentSchema() (already implemented)
        
        try (Connection conn = dataSource.getConnection()) {
            // Step 1: Find latest simulation schema
            String latestSchema;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA " +
                     "WHERE SCHEMA_NAME LIKE 'SIM\\_%' ESCAPE '\\' " +
                     "ORDER BY SCHEMA_NAME DESC " +
                     "LIMIT 1")) {
                if (!rs.next()) {
                    return null;  // No simulation runs found
                }
                latestSchema = rs.getString("SCHEMA_NAME");
            }
            
            // Step 2: Set schema and read run-id (maintains encapsulation)
            conn.createStatement().execute("SET SCHEMA \"" + latestSchema + "\"");
            
            try {
                return doGetRunIdInCurrentSchema(conn);
            } catch (MetadataNotFoundException e) {
                return null;  // Schema exists but no metadata yet
            }
        }
    }
}
```

### 3.3 SingleBlobStrategy Read Implementation

```java
package org.evochora.datapipeline.resources.database.h2;

/**
 * SingleBlobStrategy: Stores all cells of a tick in a single BLOB.
 * <p>
 * Read operations:
 * <ul>
 *   <li>Read BLOB from database</li>
 *   <li>Decompress (ZSTD level 3)</li>
 *   <li>Deserialize Protobuf</li>
 *   <li>Filter by region in Java</li>
 * </ul>
 */
public class SingleBlobStrategy extends AbstractH2EnvStorageStrategy {
    
    private final ICompressionCodec codec;
    
    // ... existing write code ...
    
    @Override
    public List<CellState> readTick(Connection conn, long tickNumber, 
                                   SpatialRegion region, EnvironmentProperties envProps) 
            throws SQLException {
        
        // 1. Read BLOB from database
        String sql = "SELECT cells_blob FROM environment_ticks WHERE tick_number = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, tickNumber);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    // Tick not found - return empty list (not an error)
                    log.debug("Tick {} not found in database", tickNumber);
                    return Collections.emptyList();
                }
                
                byte[] compressedBlob = rs.getBytes("cells_blob");
                
                // 2. Auto-detect compression from magic bytes (robust against config changes)
                ICompressionCodec detectedCodec = CompressionCodecFactory.detectFromMagicBytes(compressedBlob);
                
                // 3. Decompress BLOB
                byte[] decompressed;
                try (InputStream in = detectedCodec.wrapInputStream(new ByteArrayInputStream(compressedBlob))) {
                    decompressed = in.readAllBytes();
                } catch (IOException e) {
                    throw new SQLException("Failed to decompress cells blob for tick: " + tickNumber, e);
                }
                
                // 3. Deserialize Protobuf
                CellStateList cellsList = CellStateList.parseFrom(decompressed);
                List<CellState> allCells = cellsList.getCellsList();
                
                log.debug("Read {} cells from BLOB for tick {}", allCells.size(), tickNumber);
                
                // 4. Filter by region (in Java - strategy-specific!)
                if (region != null) {
                    List<CellState> filtered = filterByRegion(allCells, region, envProps);
                    log.debug("Filtered {} cells to {} cells for region {}", 
                             allCells.size(), filtered.size(), Arrays.toString(region.bounds));
                    return filtered;
                }
                
                return allCells;
            }
        } catch (IOException e) {
            throw new SQLException("Failed to read environment cells for tick: " + tickNumber, e);
        }
    }
    
    /**
     * Filters cells by spatial region.
     * <p>
     * <strong>Performance-Critical Hotpath:</strong>
     * This method is called for every viewport query and processes all cells in tick.
     * Uses specialized implementations for maximum performance:
     * <ul>
     *   <li>2D environments: Direct coordinate calculation (2.2x faster)</li>
     *   <li>N-D environments: Array reuse to minimize allocations (1.2x faster)</li>
     * </ul>
     * <p>
     * Benchmark results (1000 iterations):
     * <ul>
     *   <li>2D 1000×1000, 500k cells: 15.6ms (vs 34.5ms stream-based)</li>
     *   <li>4D 50×50×10×10, 125k cells: 12.1ms (vs 15.1ms stream-based)</li>
     * </ul>
     *
     * @param cells All cells from tick (flatIndex format)
     * @param region Spatial bounds (dimensions already validated)
     * @param envProps Environment properties for coordinate conversion
     * @return Filtered cells within region bounds
     */
    private List<CellState> filterByRegion(List<CellState> cells, 
                                          SpatialRegion region, 
                                          EnvironmentProperties envProps) {
        int dimensions = envProps.getDimensions();
        
        // Fast path: 2D environments (most common case)
        if (dimensions == 2) {
            return filterByRegion2D(cells, region, envProps.getWorldShape()[0]);
        }
        
        // Generic N-D path with array reuse
        return filterByRegionND(cells, region, envProps);
    }
    
    /**
     * Specialized 2D filtering with automatic bit-operations optimization.
     * <p>
     * <strong>Performance Auto-Tuning:</strong>
     * <ul>
     *   <li>Power-of-2 width (1024, 2048, etc.): Bit-ops (~3ms for 31k cells) ⚡</li>
     *   <li>Non-power-of-2 width (1000, 2500, etc.): Modulo/division (~7ms for 31k cells)</li>
     * </ul>
     * <p>
     * <strong>Why only width matters:</strong> FlatIndex formula is {@code y * width + x}.
     * Height is irrelevant for coordinate extraction. Only environment width (worldShape[0])
     * must be power-of-2 for bit-ops optimization.
     * <p>
     * <strong>Benchmark:</strong>
     * <ul>
     *   <li>1000×1000 @ 500k cells: ~15.6ms (modulo/div)</li>
     *   <li>1024×1024 @ 500k cells: ~13ms (bit-ops, ~15% faster) ⚡</li>
     * </ul>
     *
     * @param cells All cells from tick
     * @param region 2D spatial bounds [minX, maxX, minY, maxY]
     * @param width Environment width (worldShape[0] - only dimension that matters!)
     * @return Filtered cells within region bounds
     */
    private List<CellState> filterByRegion2D(List<CellState> cells, 
                                             SpatialRegion region, 
                                             int width) {
        List<CellState> result = new ArrayList<>(cells.size() / 4); // Pre-size estimate
        
        // Auto-detect: Can we use bit-operations? (check ONCE before loop)
        boolean isPowerOfTwo = (width & (width - 1)) == 0;
        int widthMask = isPowerOfTwo ? (width - 1) : 0;
        int widthBits = isPowerOfTwo ? Integer.numberOfTrailingZeros(width) : 0;
        
        int minX = region.bounds[0];
        int maxX = region.bounds[1];
        int minY = region.bounds[2];
        int maxY = region.bounds[3];
        
        for (CellState cell : cells) {
            int flatIndex = cell.getFlatIndex();
            
            // Coordinate calculation: Auto-select fastest method
            int x, y;
            if (isPowerOfTwo) {
                // Bit-operations (ultra-fast for power-of-2 widths)
                x = flatIndex & widthMask;        // Bitwise AND instead of modulo
                y = flatIndex >>> widthBits;      // Unsigned shift instead of division
            } else {
                // Standard math (works for all widths)
                x = flatIndex % width;
                y = flatIndex / width;
            }
            
            if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
                result.add(cell);
            }
        }
        
        return result;
    }
    
    /**
     * Generic N-D filtering with coordinate array reuse.
     * <p>
     * <strong>Performance:</strong> 1.2x faster than stream-based filtering.
     * Reuses single coordinate array instead of allocating one per cell.
     * <p>
     * <strong>Benchmark:</strong> 4D 50×50×10×10 @ 125k cells → 12.1ms (vs 15.1ms stream-based)
     *
     * @param cells All cells from tick
     * @param region N-D spatial bounds
     * @param envProps Environment properties for coordinate conversion
     * @return Filtered cells within region bounds
     */
    private List<CellState> filterByRegionND(List<CellState> cells, 
                                             SpatialRegion region, 
                                             EnvironmentProperties envProps) {
        List<CellState> result = new ArrayList<>(cells.size() / 4); // Pre-size estimate
        int[] coords = new int[envProps.getDimensions()]; // Reuse for all cells!
        
        for (CellState cell : cells) {
            // Reuse coordinate array (no allocation per cell!)
            envProps.flatIndexToCoordinates(cell.getFlatIndex(), coords);
            
            // Inline bounds check with early exit
            boolean inRegion = true;
            for (int i = 0; i < coords.length; i++) {
                int coord = coords[i];
                int min = region.bounds[i * 2];
                int max = region.bounds[i * 2 + 1];
                if (coord < min || coord > max) {
                    inRegion = false;
                    break; // Early exit on first dimension mismatch
                }
            }
            
            if (inRegion) {
                result.add(cell);
            }
        }
        
        return result;
    }
}
```

**Performance Characteristics (SingleBlobStrategy):**
- ✅ Simple: No complex SQL, just BLOB read
- ✅ Works for all dimensions (filtering in Java)
- ✅ Minimal code - easy to understand
- ❌ Reads entire tick even if region is small (viewport 25% → reads 100%)
- ❌ Decompression + deserialization overhead for entire tick

**Future: SpatialIndexStrategy** would filter at database level:
```java
@Override
public List<CellState> readTick(Connection conn, long tick, 
                               SpatialRegion region, EnvironmentProperties envProps) {
    // Build SQL with WHERE clause for region
    String sql = "SELECT x, y, molecule_type, molecule_value, owner_id " +
                "FROM environment_cells " +
                "WHERE tick_number = ?";
    
    if (region != null) {
        sql += " AND x BETWEEN ? AND ? AND y BETWEEN ? AND ?";  // Dynamic per dimension
    }
    
    // Execute query - database filters, returns only relevant cells
    return executeQuery(conn, sql, tick, region);
}
```

### 3.4 H2DatabaseReader Implementation

```java
package org.evochora.datapipeline.resources.database.h2;

import org.evochora.datapipeline.api.resources.database.*;
import org.evochora.datapipeline.resources.database.H2Database;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Per-request H2 database reader.
 * <p>
 * Holds a dedicated connection from HikariCP pool with schema already set.
 * Implements all read capabilities (environment, metadata, organisms).
 * <p>
 * <strong>Architecture:</strong>
 * <ul>
 *   <li><b>Environment reads:</b> Direct usage of {@link IH2EnvStorageStrategy}</li>
 *   <li><b>Organism reads:</b> Own SQL implementation (future)</li>
 *   <li><b>Metadata reads:</b> Delegation to {@link H2Database} (shared with indexers)</li>
 * </ul>
 * <p>
 * <strong>Design Rationale:</strong>
 * This class is NOT a pure delegation layer. It implements reading capabilities directly:
 * <ul>
 *   <li>Environment: Uses strategy pattern (same as writes)</li>
 *   <li>Organism: Will have own SQL (no strategy needed)</li>
 *   <li>Metadata: Delegates to H2Database (because MetadataIndexer uses same logic)</li>
 * </ul>
 * <p>
 * This keeps H2Database focused on writes (indexing) while readers have their own implementation.
 */
class H2DatabaseReader implements IDatabaseReader {
    
    private static final Logger log = LoggerFactory.getLogger(H2DatabaseReader.class);
    
    private final Connection connection;
    private final H2Database database;  // Only for metadata delegation
    private final IH2EnvStorageStrategy envStrategy;  // Direct access for environment reads
    private final String runId;
    private volatile boolean closed = false;
    
    // Cached metadata and environment properties (read once per reader)
    private SimulationMetadata metadata;
    private EnvironmentProperties envProps;
    
    /**
     * Creates reader with dedicated connection.
     * 
     * @param connection Dedicated connection from pool (schema already set)
     * @param database Parent database for metadata delegation
     * @param envStrategy Storage strategy for environment reads
     * @param runId Run-ID for this reader (already set as schema on connection)
     */
    H2DatabaseReader(Connection connection, H2Database database, 
                    IH2EnvStorageStrategy envStrategy, String runId) {
        this.connection = connection;
        this.database = database;
        this.envStrategy = envStrategy;
        this.runId = runId;
    }
    
    // ========================================================================
    // IEnvironmentDataReader Implementation
    // ========================================================================
    
    @Override
    public List<CellWithCoordinates> readEnvironmentRegion(long tickNumber, SpatialRegion region) 
            throws SQLException {
        ensureNotClosed();
        
        // Lazy-load metadata and environment properties (once per reader)
        ensureMetadataLoaded();
        
        // Validate region dimensions match environment (ONCE before strategy call)
        if (region != null) {
            int envDims = envProps.getWorldShape().length;
            int regionDims = region.getDimensions();
            if (regionDims != envDims) {
                throw new IllegalArgumentException(
                    "Region dimensions (" + regionDims + ") " +
                    "do not match environment dimensions (" + envDims + ")"
                );
            }
        }
        
        // Direct usage of strategy (same as writes use strategy)
        // Strategy handles: BLOB read, decompression, deserialization, region filtering
        List<CellState> cells = envStrategy.readTick(connection, tickNumber, region, envProps);
        
        // Convert flatIndex → coordinates (only transformation done here)
        return convertToCoordinates(cells);
    }
    
    /**
     * Lazy-loads metadata and extracts environment properties.
     * Called once per reader instance, cached for subsequent calls.
     */
    private void ensureMetadataLoaded() throws SQLException {
        if (metadata == null) {
            metadata = database.getMetadataInternal(connection, runId);
            envProps = extractEnvironmentProperties(metadata);
        }
    }
    
    private EnvironmentProperties extractEnvironmentProperties(SimulationMetadata metadata) {
        int[] worldShape = metadata.getEnvironment().getShapeList().stream()
            .mapToInt(Integer::intValue)
            .toArray();
        boolean isToroidal = !metadata.getEnvironment().getToroidalList().isEmpty() 
            && metadata.getEnvironment().getToroidal(0);
        return new EnvironmentProperties(worldShape, isToroidal);
    }
    
    /**
     * Converts cells from flatIndex format to coordinates.
     * <p>
     * This is the ONLY transformation done in reader - all other logic in Strategy.
     * Uses Record constructor for efficient object creation (~1ms for 31k cells).
     * <p>
     * <strong>Performance (31k cells):</strong>
     * <ul>
     *   <li>Coordinate conversion: ~5ms (int[] allocation per cell)</li>
     *   <li>Record creation: ~1ms (compact constructor)</li>
     *   <li>Total: ~6ms</li>
     * </ul>
     * <p>
     * <strong>Future Optimization:</strong> For very large viewports (&gt;100k cells),
     * streaming JSON with {@code JsonGenerator} can save ~15-20ms by eliminating
     * intermediate objects entirely. Trade-off: significantly higher code complexity.
     * 
     * @param cells Cells with flatIndex (from strategy)
     * @return Cells with coordinates (for JSON response)
     */
    private List<CellWithCoordinates> convertToCoordinates(List<CellState> cells) {
        return cells.stream()
            .map(cell -> new CellWithCoordinates(
                envProps.flatIndexToCoordinates(cell.getFlatIndex()),
                cell.getMoleculeType(),
                cell.getMoleculeValue(),
                cell.getOwnerId()
            ))
            .collect(Collectors.toList());
    }
    
    // ========================================================================
    // IMetadataReader Implementation
    // ========================================================================
    
    @Override
    public SimulationMetadata getMetadata(String simulationRunId) throws SQLException {
        ensureNotClosed();
        // Delegate to H2Database (shared implementation with MetadataIndexer)
        return database.getMetadataInternal(connection, simulationRunId);
    }
    
    @Override
    public boolean hasMetadata(String simulationRunId) throws SQLException {
        ensureNotClosed();
        // Delegate to H2Database (shared implementation with MetadataIndexer)
        return database.hasMetadataInternal(connection, simulationRunId);
    }
    
    // ========================================================================
    // Future: IOrganismDataReader Implementation (Phase 16)
    // ========================================================================
    
    // Will implement own SQL directly (no strategy pattern for organisms)
    // Example:
    // @Override
    // public List<OrganismState> readOrganisms(long tick, SpatialRegion region) {
    //     String sql = "SELECT * FROM organisms WHERE tick = ?";
    //     // ... own SQL implementation ...
    // }
    
    // ========================================================================
    // AutoCloseable Implementation
    // ========================================================================
    
    @Override
    public void close() {
        if (closed) {
            return;
        }
        
        try {
            connection.close(); // Returns to HikariCP pool
            closed = true;
        } catch (SQLException e) {
            log.warn("Failed to close database reader connection", e);
        }
    }
    
    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("Reader already closed");
        }
    }
}
```

## 4. Integration with HttpServerProcess

### 4.1 Configuration (evochora.conf)

**Database Resource Configuration:**
```hocon
pipeline {
  resources {
    index-database {
      className = "org.evochora.datapipeline.resources.database.H2Database"
      options {
        dbPath = "/home/user/evochora/data/indexdb"
        username = "sa"
        password = ""
        maxPoolSize = 10
        minIdle = 2
        
        # Metadata caching with LRU eviction
        metadataCacheSize = 100   # Max run-ids to cache (default: 100, ~100-200 KB)
        
        h2EnvironmentStrategy {
          className = "org.evochora.datapipeline.resources.database.h2.SingleBlobStrategy"
          options {
            compression {
              enabled = true
              codec = "zstd"
              level = 3
            }
          }
        }
      }
    }
  }
}
```

**HTTP Server Configuration:**
```hocon
node {
  processes {
    http {
      className = "org.evochora.node.processes.http.HttpServerProcess"
      
      # Dependency on ServiceManager
      require = {
        serviceManager = "pipeline"
      }
      
      options {
        # Database provider for API controllers
        databaseProviderResourceName = "index-database"
        
        network {
          host = "localhost"
          port = 8080
        }
        
        routes {
          "/visualizer" {
            api {
              "$controller" {
                className = "org.evochora.node.processes.http.api.visualizer.EnvironmentController"
                options {
                  # Optional: Default run-id (fallback if not in query)
                  runId = ${?pipeline.services.runId}
                }
              }
            }
            "$static" = "/web/visualizer"
          }
        }
      }
    }
  }
}
```

### 4.2 HttpServerProcess Implementation

```java
public class HttpServerProcess extends AbstractProcess {
    
    private final ServiceRegistry controllerRegistry;
    
    public HttpServerProcess(String processName, Map<String, Object> dependencies, Config options) {
        super(processName, dependencies, options);
        
        // Get ServiceManager dependency
        ServiceManager sm = getDependency("serviceManager", ServiceManager.class);
        
        // Read database provider name from config
        String dbProviderName = options.hasPath("databaseProviderResourceName")
            ? options.getString("databaseProviderResourceName")
            : "index-database"; // default
        
        // Get database provider factory from ServiceManager
        IDatabaseReaderProvider dbProvider = sm.getResource(
            dbProviderName, 
            IDatabaseReaderProvider.class
        );
        
        // Create controller registry
        this.controllerRegistry = new ServiceRegistry();
        this.controllerRegistry.register(ServiceManager.class, sm);
        this.controllerRegistry.register(IDatabaseReaderProvider.class, dbProvider);
        
        parseRoutes();
    }
    
    // ... rest of implementation ...
}
```

## 5. Controller Implementation

### 5.1 EnvironmentController

```java
package org.evochora.node.processes.http.api.visualizer;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.evochora.datapipeline.api.resources.database.*;
import org.evochora.node.processes.http.AbstractController;
import org.evochora.node.spi.ServiceRegistry;

public class EnvironmentController extends AbstractController {
    
    private final IDatabaseReaderProvider dbProvider;
    private final String defaultRunId;
    
    public EnvironmentController(ServiceRegistry registry, Config options) {
        super(registry, options);
        this.dbProvider = registry.get(IDatabaseReaderProvider.class);
        this.defaultRunId = options.hasPath("runId") 
            ? options.getString("runId") 
            : null;
    }
    
    @Override
    public void registerRoutes(Javalin app, String basePath) {
        // GET /visualizer/api/:tick/environment?runId=X&region=min_x,max_x,min_y,max_y,...
        app.get(basePath + "/:tick/environment", this::getEnvironmentRegion);
        
        // GET /visualizer/api/metadata?runId=X
        app.get(basePath + "/metadata", this::getMetadata);
        
        // GET /visualizer/api/runs (future: list available runs)
        // app.get(basePath + "/runs", this::listRuns);
    }
    
    private void getEnvironmentRegion(Context ctx) {
        try {
            // Parse parameters (no DB access needed)
            long tick = Long.parseLong(ctx.pathParam("tick"));
            String regionParam = ctx.queryParam("region"); // "min_x,max_x,min_y,max_y,..." (dimension-agnostic)
            SpatialRegion region = parseRegion(regionParam);
            
            // Resolve run-id (query → config → latest)
            String runId = resolveRunId(ctx);
            
            // OPTIMIZATION: Release connection immediately after DB read (~90ms)
            // JSON serialization + HTTP (~20ms) happens WITHOUT holding connection
            List<CellWithCoordinates> cells;
            try (IDatabaseReader reader = dbProvider.createReader(runId)) {
                cells = reader.readEnvironmentRegion(tick, region);
            } // Connection returned to pool after ~90ms (not ~110ms)
            
            // HTTP Cache Headers: Tick data is IMMUTABLE after indexing
            // Aggressive caching enables client-side cache with 0ms latency on repeated queries
            // Browser/Client can cache indefinitely - data NEVER changes once indexed
            ctx.header("Cache-Control", "public, max-age=31536000, immutable");  // 1 year + immutable
            ctx.header("ETag", String.format("\"%s_%d_%d\"", runId, tick, region.hashCode()));
            
            // JSON serialization + HTTP response (no connection needed)
            ctx.json(Map.of(
                "tick", tick,
                "runId", runId,
                "region", region,
                "cells", cells
            ));
            
        } catch (NoRunIdException e) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of(
                "error", "No simulation runs available",
                "message", "Database is empty. Start a simulation first or specify runId parameter."
            ));
        } catch (IllegalArgumentException e) {
            // Region parsing errors
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of(
                "error", "Invalid region parameter",
                "message", e.getMessage()
            ));
        } catch (NumberFormatException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of(
                "error", "Invalid tick number: " + ctx.pathParam("tick")
            ));
        } catch (RuntimeException e) {
            if (e.getCause() instanceof SQLException) {
                // Schema doesn't exist - invalid run-id
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of(
                    "error", "Run ID not found",
                    "message", "The specified run ID does not exist in the database."
                ));
            } else {
                throw e; // Unexpected error
            }
        } catch (SQLException e) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of(
                "error", "Database error: " + e.getMessage()
            ));
        }
    }
    
    /**
     * Resolves run-id from request with fallback hierarchy.
     * 
     * @param ctx HTTP request context
     * @return Resolved run-id
     * @throws NoRunIdException if no run-id found anywhere
     * @throws SQLException if database query fails
     */
    private String resolveRunId(Context ctx) throws SQLException {
        // 1. Query parameter (highest priority)
        String runId = ctx.queryParam("runId");
        if (runId != null) return runId;
        
        // 2. Controller config default (second priority)
        if (defaultRunId != null) return defaultRunId;
        
        // 3. Latest run-id from database (fallback)
        String latest = dbProvider.findLatestRunId();
        if (latest == null) {
            throw new NoRunIdException("No simulation runs found in database");
        }
        return latest;
    }
    
    /**
     * Exception thrown when no run-id can be resolved.
     */
    private static class NoRunIdException extends RuntimeException {
        public NoRunIdException(String message) {
            super(message);
        }
    }
    
    private void getMetadata(Context ctx) {
        String runId = ctx.queryParam("runId", defaultRunId);
        
        if (runId == null) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of(
                "error", "runId parameter required"
            ));
            return;
        }
        
        try (IDatabaseReader reader = dbProvider.createReader(runId)) {
            SimulationMetadata metadata = reader.getMetadata(runId);
            
            // HTTP Cache Headers: Metadata is IMMUTABLE after simulation start
            ctx.header("Cache-Control", "public, max-age=31536000, immutable");
            ctx.header("ETag", String.format("\"%s_metadata\"", runId));
            
            ctx.json(Map.of(
                "runId", runId,
                "dimensions", metadata.getEnvironment().getShapeCount(),
                "shape", metadata.getEnvironment().getShapeList(),
                "toroidal", metadata.getEnvironment().getToroidalList()
            ));
            
        } catch (SQLException e) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of(
                "error", "Database error: " + e.getMessage()
            ));
        }
    }
    
    /**
     * Parses region query parameter into dimension-agnostic SpatialRegion.
     * <p>
     * Format: Interleaved min/max pairs per dimension
     * <ul>
     *   <li>2D: {@code region=min_x,max_x,min_y,max_y}</li>
     *   <li>3D: {@code region=min_x,max_x,min_y,max_y,min_z,max_z}</li>
     *   <li>ND: {@code region=min_0,max_0,min_1,max_1,...}</li>
     * </ul>
     * 
     * @param regionParam Comma-separated bounds (must have even number of values)
     * @return SpatialRegion or null if no filtering
     * @throws IllegalArgumentException if format invalid
     */
    private SpatialRegion parseRegion(String regionParam) {
        if (regionParam == null) {
            return null; // No filtering
        }
        
        String[] parts = regionParam.split(",");
        
        // Validate: must have even number of values (min/max pairs)
        if (parts.length % 2 != 0) {
            throw new IllegalArgumentException(
                "Region must have even number of values (min/max pairs): " + regionParam
            );
        }
        
        int[] bounds = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                bounds[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "Invalid region coordinate at position " + i + ": " + parts[i]
                );
            }
        }
        
        // Validate: min <= max for each dimension
        for (int i = 0; i < bounds.length / 2; i++) {
            int min = bounds[i * 2];
            int max = bounds[i * 2 + 1];
            if (min > max) {
                throw new IllegalArgumentException(
                    "Region min > max for dimension " + i + ": [" + min + ", " + max + "]"
                );
            }
        }
        
        return new SpatialRegion(bounds);
    }
}
```

### 3.5 Best Practice: Early Connection Release

**Pattern: Release connection immediately after database read**

The EnvironmentController demonstrates the **Early Connection Release** pattern for optimal connection pool utilization:

```java
// ✅ OPTIMIZED: Connection held only for DB read (~90ms)
List<CellWithCoordinates> cells;
try (IDatabaseReader reader = dbProvider.createReader(runId)) {
    cells = reader.readEnvironmentRegion(tick, region);  // ~90ms DB read
} // Connection back to pool

// JSON serialization + HTTP without connection (~20ms)
ctx.json(Map.of("tick", tick, "runId", runId, "cells", cells));
```

**vs. Naive Approach:**
```java
// ❌ SUBOPTIMAL: Connection held for DB + JSON + HTTP (~110ms)
try (IDatabaseReader reader = dbProvider.createReader(runId)) {
    cells = reader.readEnvironmentRegion(tick, region);  // ~90ms
    ctx.json(Map.of("tick", tick, "cells", cells));      // +20ms
} // Connection back to pool (total ~110ms)
```

**Performance Impact:**

| Metric | Naive | Optimized | Improvement |
|--------|-------|-----------|-------------|
| Connection-hold time | ~110ms | ~90ms | **-18%** |
| Effective pool capacity | 10 req/s | 11.8 req/s | **+18%** |
| Burst traffic (20 requests, pool=10) | 10 timeout | 0-2 timeout | **~80% fewer** |

**Why This Works:**

1. **Single DB Query:** Controller makes only 1 database call
2. **No Lazy Loading:** All data fetched immediately
3. **Independent Operations:** JSON serialization doesn't need DB connection
4. **Error Handling:** All error cases handled after DB read

**When NOT to Use:**

```java
// ❌ Multiple queries need connection
try (IDatabaseReader reader = dbProvider.createReader(runId)) {
    metadata = reader.getMetadata(runId);              // Query 1
    cells = reader.readEnvironmentRegion(tick, region); // Query 2
    organisms = reader.readOrganisms(tick);            // Query 3
} // Keep connection for all queries
```

**Key Takeaway:** For single-query endpoints, **extract data first, process later** to maximize connection pool throughput.

### 3.6 JSON Serialization Performance

**Design Choice: Java Records with @JsonAutoDetect**

The `CellWithCoordinates` response DTO uses Java Records for optimal JSON serialization:

```java
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public record CellWithCoordinates(
    int[] coordinates,
    int moleculeType,
    int moleculeValue,
    int ownerId
) {}
```

**Performance Comparison (50k cells, 2500×2500 @ 80% occupancy):**

| Approach | Post-DB Time | Code Lines | Maintainability | Memory |
|----------|--------------|------------|-----------------|--------|
| POJO + Reflection | ~31-36 ms | 2 | ✅ High | ~5 MB |
| **Record + @JsonAutoDetect** | **~22-25 ms** | **2** | **✅ High** | **~5 MB** |
| Streaming (JsonGenerator) | ~7-8 ms | ~30 | ❌ Low | ~0 KB |

**Why Records:**
- ✅ **25-30% faster** than POJO+Reflection (~9-11ms savings)
- ✅ **Same simplicity** (2 lines vs 2 lines)
- ✅ **Type-safe** and easy to test
- ✅ **No new dependencies** (Jackson already in project)

**Streaming JSON Alternative:**
- ⚡ **Additional ~15-20ms savings** possible with `JsonGenerator`
- ❌ **15× more code** (30 lines vs 2 lines)
- ❌ **Manual JSON structure** (error-prone, hard to maintain)
- ❌ **Only worthwhile for very large viewports** (>100k cells)

**Decision:** Use Records for optimal balance of performance and maintainability. Document streaming alternative in JavaDoc for future consideration if viewport sizes grow significantly.

## Thread Safety

### Connection Pool Isolation

**Each Request Gets Dedicated Connection:**
```java
try (IDatabaseReader reader = dbProvider.createReader(runId)) {
    // reader holds Connection from HikariCP pool
    // Schema already set to runId
    // No other request can interfere
    List<CellWithCoordinates> cells = reader.readEnvironmentRegion(tick, region);
} // Connection automatically returned to pool
```

**Why This Works:**
- `H2Database.createReader()` calls `dataSource.getConnection()` → dedicated connection from pool
- `H2SchemaUtil.setSchema(conn, runId)` sets schema only for this connection
- Other requests get different connections with different schemas
- No locking, no contention, no race conditions

**Connection Pool Sizing:**
```hocon
pipeline.database {
  maxPoolSize = 10  # Max 10 concurrent API requests
  minIdle = 2       # Keep 2 connections warm
}
```

For typical workloads (< 10 concurrent requests), pool never exhausts. If pool full, request blocks until connection available (HikariCP default behavior).

### Schema Switching Cost

**H2 Schema Switch Performance:**
```sql
SET SCHEMA run_20251021_143025;  -- ~0.1ms (metadata lookup)
```

Schema switching is fast (metadata operation, no data movement). Per-request switching adds negligible latency (~100 μs).

**Alternative Considered: Schema Caching**
```java
// ❌ NOT IMPLEMENTED: Would cache connection per run-id
Map<String, Connection> schemaCache = ...;
```

**Rejected because:**
- Adds complexity (cache invalidation, size limits, TTL)
- Minimal performance gain (schema switch is already fast)
- Worse resource usage (connections not returned to pool)
- YAGNI (premature optimization)

Current design: Simple, correct, fast enough.

### Client-Side Caching Strategy

**HTTP Cache Headers enable aggressive client-side caching with zero server changes.**

The API returns immutable data with aggressive cache headers:
```http
Cache-Control: public, max-age=31536000, immutable
ETag: "20251021_143025_AB12CD_12345_1234567"
```

**Why This Works:**
- ✅ **Tick data is IMMUTABLE:** Once indexed, data never changes
- ✅ **Metadata is IMMUTABLE:** Environment properties fixed at simulation start
- ✅ **1-year TTL:** Browser/Client can cache indefinitely
- ✅ **`immutable` directive:** Browser skips revalidation entirely

**Client-Side Cache Implementation (Visualizer):**

```javascript
// Simple in-memory cache with IndexedDB fallback
class EnvironmentCache {
    constructor() {
        this.memoryCache = new Map();  // Fast access
        this.db = null;  // IndexedDB for persistence
        this.initIndexedDB();
    }
    
    async initIndexedDB() {
        const request = indexedDB.open('evochora_cache', 1);
        request.onupgradeneeded = (e) => {
            const db = e.target.result;
            db.createObjectStore('viewports', { keyPath: 'cacheKey' });
        };
        request.onsuccess = (e) => {
            this.db = e.target.result;
        };
    }
    
    getCacheKey(runId, tick, region) {
        return `${runId}_${tick}_${region.join('_')}`;
    }
    
    async get(runId, tick, region) {
        const key = this.getCacheKey(runId, tick, region);
        
        // L1: Memory cache (0ms)
        if (this.memoryCache.has(key)) {
            return this.memoryCache.get(key);
        }
        
        // L2: IndexedDB (5-10ms, persists across sessions)
        if (this.db) {
            const tx = this.db.transaction(['viewports'], 'readonly');
            const store = tx.objectStore('viewports');
            const result = await new Promise((resolve) => {
                const req = store.get(key);
                req.onsuccess = () => resolve(req.result);
            });
            if (result) {
                this.memoryCache.set(key, result.data);  // Promote to L1
                return result.data;
            }
        }
        
        // L3: Fetch from server (85-115ms)
        const response = await fetch(
            `/visualizer/api/${tick}/environment?runId=${runId}&region=${region.join(',')}`
        );
        const json = await response.json();
        
        // Store in both caches
        this.memoryCache.set(key, json.cells);
        if (this.db) {
            const tx = this.db.transaction(['viewports'], 'readwrite');
            tx.objectStore('viewports').put({ cacheKey: key, data: json.cells });
        }
        
        return json.cells;
    }
}
```

**Performance Impact:**

| Cache Level | Latency | Hit Rate (typical) | Use Case |
|-------------|---------|-------------------|----------|
| **Memory Cache (L1)** | **0ms** | 20-30% | Recent viewports (user navigates back/forth) |
| **IndexedDB (L2)** | 5-10ms | 30-50% | Session persistence (user reopens tab) |
| **Server (L3)** | 85-115ms | 20-50% | New viewports |
| **Effective Avg** | **~25-40ms** | - | **60-70% faster than no cache!** |

**Benefits:**
- ✅ **No server complexity:** Backend stays simple
- ✅ **No server dependencies:** No Redis, no Caffeine
- ✅ **Offline-capable:** IndexedDB persists data
- ✅ **Instant navigation:** Back/forward in tick timeline = 0ms
- ✅ **Browser-native:** HTTP cache + IndexedDB built-in

**Memory Management:**
```javascript
// LRU eviction for memory cache
if (this.memoryCache.size > 100) {  // 100 viewports ≈ 500 MB
    const oldest = this.memoryCache.keys().next().value;
    this.memoryCache.delete(oldest);
}
```

**Recommendation:** Client-side caching is the PRIMARY caching strategy for this API. Server-side caching (Redis/Caffeine) is unnecessary complexity with minimal additional benefit.

### Concurrent Access Patterns

**Multiple Requests, Same Run-ID:**
```
Request 1: GET /api/100/environment?runId=X&region=0,50,0,50      (2D: x:[0,50], y:[0,50])
Request 2: GET /api/101/environment?runId=X&region=50,100,50,100  (2D: x:[50,100], y:[50,100])
```
- Both get different connections from pool
- Both set schema to X (idempotent)
- Both read different ticks → no database contention
- HikariCP ensures efficient connection reuse

**Multiple Requests, Different Run-IDs:**
```
Request 1: GET /api/100/environment?runId=X&region=...
Request 2: GET /api/100/environment?runId=Y&region=...
```
- Different connections, different schemas
- Completely isolated (different database schemas)
- Zero interference

## Error Handling & Recovery

### Connection Acquisition Errors

**Pool Exhausted:**
```java
try (IDatabaseReader reader = dbProvider.createReader(runId)) {
    List<CellWithCoordinates> cells = reader.readEnvironmentRegion(tick, region);
    ctx.json(cells);
} catch (RuntimeException e) {
    if (e.getCause() instanceof SQLException) {
        // Pool exhausted - all connections in use
        ctx.status(503).json(Map.of("error", "Service temporarily unavailable"));
    }
}
```

**Behavior:** Request blocks until connection available (HikariCP default). Timeout configured via:
```hocon
hikariConfig.connectionTimeout = 30000  # 30 seconds
```

**Schema Not Found (Invalid Run-ID):**
```java
try (IDatabaseReader reader = dbProvider.createReader("invalid_run_id")) {
    // SET SCHEMA fails → SQLException wrapped in RuntimeException
    List<CellWithCoordinates> cells = reader.readEnvironmentRegion(tick, region);
    ctx.json(cells);
} catch (RuntimeException e) {
    if (e.getCause() instanceof SQLException) {
        ctx.status(404).json(Map.of("error", "Run ID not found: invalid_run_id"));
    }
}
```

**No Run-ID Available (Empty Database):**
```java
// In request handler
try {
    String runId = resolveRunId(ctx);
    try (IDatabaseReader reader = dbProvider.createReader(runId)) {
        List<CellWithCoordinates> cells = reader.readEnvironmentRegion(tick, region);
        ctx.json(Map.of(
            "tick", tick,
            "runId", runId,
            "cells", cells
        ));
    }
} catch (NoRunIdException e) {
    ctx.status(404).json(Map.of(
        "error", "No simulation runs available",
        "message", "Database is empty. Start a simulation first or specify runId explicitly."
    ));
}
```

### Query Errors

**Tick Not Found:**
```java
try (IDatabaseReader reader = dbProvider.createReader(runId)) {
    List<CellWithCoordinates> cells = reader.readEnvironmentRegion(999999, null);
    // Returns empty list (not an error)
    ctx.json(Map.of("cells", cells));  // Empty response
}
```

**Database Read Error:**
```java
try (IDatabaseReader reader = dbProvider.createReader(runId)) {
    List<CellWithCoordinates> cells = reader.readEnvironmentRegion(tick, region);
    ctx.json(cells);
} catch (SQLException e) {
    log.error("Database read failed for tick {}: {}", tick, e.getMessage());
    ctx.status(500).json(Map.of("error", "Database error"));
}
```

### Connection Leak Prevention

**Automatic Cleanup:**
```java
try (IDatabaseReader reader = dbProvider.createReader(runId)) {
    List<CellWithCoordinates> cells = reader.readEnvironmentRegion(tick, region);
    ctx.json(cells);
} // close() called automatically, even if exception thrown
```

**Close Implementation:**
```java
@Override
public void close() {
    try {
        connection.close(); // Returns to HikariCP pool
    } catch (SQLException e) {
        log.warn("Failed to close reader connection", e);
        // Connection cleanup best-effort
        // HikariCP handles abandoned connections via leak detection
    }
}
```

**Leak Detection:**
HikariCP tracks connection age and logs warnings for abandoned connections:
```
WARN - Connection leak detected (age > leakDetectionThreshold)
```

## Implementation Requirements

### Package Structure

```
api/resources/database/
├── IDatabaseReaderProvider.java  (factory interface)
├── IDatabaseReader.java          (product interface - bundles all capabilities)
├── IEnvironmentDataReader.java   (capability interface)
├── IMetadataReader.java          (capability interface - already exists)
└── (future) IOrganismDataReader.java

resources/database/
├── H2Database.java               (implements IDatabaseReaderProvider, IMetadataReader)
└── h2/
    ├── H2DatabaseReader.java     (implements IDatabaseReader)
    └── (existing) SingleBlobStrategy.java

node/processes/http/api/visualizer/
└── EnvironmentController.java    (example controller)

node/processes/http/
└── HttpServerProcess.java        (updated for provider registration)
```

### Database Backend Extensibility

To add PostgreSQL support, implementer needs:

**Core Files:**
```
PostgresDatabase.java              // implements IDatabaseReaderProvider, IMetadataReader
PostgresDatabaseReader.java        // implements IDatabaseReader
```

**Implementation checklist:**
1. `PostgresDatabase extends AbstractDatabaseResource implements IDatabaseReaderProvider, IMetadataReader`
2. Implement `createReader(runId)` → get connection, set schema, return reader
3. `PostgresDatabaseReader implements IDatabaseReader` (in `postgres/` subpackage)
4. Implement environment reads (own SQL or strategy pattern - your choice)
5. Implement organism reads (own SQL)
6. Delegate metadata reads to PostgresDatabase
7. Implement `close()` to return connection to pool

**Copy-paste from H2 as starting point** - same interfaces, only SQL/connection logic differs.

**Key principle:** Controllers remain unchanged. Internal architecture (strategy vs. direct SQL) is up to implementer.

### Easy to Extend (New Capabilities)

To add `IOrganismDataReader`:
1. Create `IOrganismDataReader` interface
2. Add to `IDatabaseReader extends` list
3. Implement methods in `H2DatabaseReader` (own SQL)
4. Controllers immediately have access via `reader.readOrganism(...)`

## 7. API Endpoints (Example)

### Environment Endpoint

**2D Example:**
```
GET /visualizer/api/:tick/environment?runId=X&region=0,50,0,50

Query Parameters:
- tick: 100 (path parameter)
- runId: X (optional - falls back to config or latest)
- region: 0,50,0,50 (min_x,max_x,min_y,max_y)

Response:
{
  "tick": 100,
  "runId": "20251021-143025-550e8400-...",
  "dimensions": 2,
  "region": { "bounds": [0, 50, 0, 50] },
  "cells": [
    { "coordinates": [5, 10], "moleculeType": 0, "moleculeValue": 42, "ownerId": 1 },
    { "coordinates": [6, 10], "moleculeType": 2, "moleculeValue": 100, "ownerId": 0 }
  ]
}
```

**3D Example:**
```
GET /visualizer/api/:tick/environment?region=0,100,0,100,0,50

Query Parameters:
- region: 0,100,0,100,0,50 (min_x,max_x,min_y,max_y,min_z,max_z)

Response:
{
  "tick": 100,
  "runId": "20251021-143025-550e8400-...",
  "dimensions": 3,
  "region": { "bounds": [0, 100, 0, 100, 0, 50] },
  "cells": [
    { "coordinates": [5, 10, 25], "moleculeType": 0, "moleculeValue": 42, "ownerId": 1 }
  ]
}
```

**No Region (All Cells):**
```
GET /visualizer/api/:tick/environment

Response:
{
  "tick": 100,
  "runId": "...",
  "dimensions": 2,
  "region": null,
  "cells": [ /* all cells */ ]
}
```

### Metadata Endpoint
```
GET /visualizer/api/metadata?runId=X

Response:
{
  "runId": "20251021-143025-550e8400-...",
  "dimensions": 2,
  "shape": [100, 100],
  "toroidal": [true, true]
}
```

## Testing Strategy

### Unit Tests

**IDatabaseReaderProvider Tests:**
```java
@Test
void testCreateReader_setsSchemaCorrectly() {
    // Given: H2Database with schema
    // When: createReader("run_123")
    // Then: Connection has schema set to "run_123"
}

@Test
void testCreateReader_throwsOnInvalidSchema() {
    // Given: Invalid run-id
    // When: createReader("invalid")
    // Then: RuntimeException with SQLException cause
}
```

**H2DatabaseReader Tests:**
```java
@Test
void testClose_returnsConnectionToPool() {
    // Given: Reader with connection
    // When: close()
    // Then: Connection available in pool again
}

@Test
void testReadEnvironmentRegion_convertsCoordinates() {
    // Given: BLOB with flatIndex cells (via strategy)
    // When: readEnvironmentRegion()
    // Then: Returns cells with coordinates
}

@Test
void testReadEnvironmentRegion_usesStrategyDirectly() {
    // Given: Reader with strategy
    // When: readEnvironmentRegion()
    // Then: Strategy.readTick() is called (not H2Database delegation)
}
```

### Integration Tests

**Concurrent Access Test:**
```java
@Test
@Tag("integration")
void testConcurrentRequests_differentRunIds() throws Exception {
    // Given: 10 threads, each with different run-id
    // When: All threads query simultaneously
    // Then: All get correct data, no exceptions
    // Verify: Connection pool metrics show reuse
}
```

**Connection Leak Test:**
```java
@Test
@Tag("integration")
void testConnectionLeak_preventedByAutoClose() {
    // Given: 100 requests, some throw exceptions
    // When: All requests complete (with/without errors)
    // Then: Connection pool size unchanged
    // Verify: No leaked connections in HikariCP metrics
}
```

**Performance Test:**
```java
@Test
@Tag("integration")
void testResponseTime_under250ms() {
    // Given: 1000×1000 environment with varying occupancy
    // When: Query viewport region (0,250,0,250) = x:[0,250], y:[0,250]
    // Then: Response time < 250ms even at high occupancy
    // Measure: Strategy.readTick() + coordinate conversion
}
```

**Strategy-Specific Tests:**
```java
@Test
@Tag("integration")
void testSingleBlobStrategy_regionFiltering() {
    // Given: BLOB with 500k cells
    // When: readTick(tick, region, envProps) with small region
    // Then: Returns only cells within region
    // Measure: Filtering performance (Java stream)
}
```

### HTTP API Tests

**EnvironmentController Tests:**
```java
@Test
@Tag("integration")
void testGetEnvironment_validRequest_2D() {
    // GET /visualizer/api/100/environment?runId=X&region=0,50,0,50
    // Expected: 200 OK with JSON response (2D region)
}

@Test
@Tag("integration")
void testGetEnvironment_validRequest_3D() {
    // GET /visualizer/api/100/environment?region=0,100,0,100,0,50
    // Expected: 200 OK with JSON response (3D region)
}

@Test
@Tag("integration")
void testGetEnvironment_invalidRegion_oddCount() {
    // GET /visualizer/api/100/environment?region=0,50,0
    // Expected: 400 Bad Request (must have even number of values)
}

@Test
@Tag("integration")
void testGetEnvironment_invalidRegion_minGreaterThanMax() {
    // GET /visualizer/api/100/environment?region=50,0,50,0
    // Expected: 400 Bad Request (min > max)
}

@Test
@Tag("integration")
void testGetEnvironment_invalidRunId() {
    // GET /visualizer/api/100/environment?runId=invalid
    // Expected: 404 Not Found
}

@Test
@Tag("integration")
void testGetEnvironment_missingRunId_fallsBackToLatest() {
    // GET /visualizer/api/100/environment (no runId param)
    // Expected: 200 OK with data from latest run-id
}

@Test
@Tag("integration")
void testGetEnvironment_noRunsInDatabase() {
    // Given: Empty database (no schemas)
    // When: GET /visualizer/api/100/environment (no runId param, no default)
    // Expected: 404 Not Found with message "No simulation runs available"
}
```

## Performance Considerations

### SingleBlobStrategy (Current Implementation)

**Performance depends heavily on occupancy** (sparse vs. dense environment):

**Scenario A: Low Occupancy (~10% - early simulation):**
```
Environment: 1000×1000, ~100k occupied cells
BLOB size: ~2 MB raw → ~200 KB compressed (ZSTD level 3)

1. SQL Query:          SELECT cells_blob WHERE tick_number = ?     ~1-2 ms
2. BLOB Transfer:      Network/Disk → JVM heap (~200 KB)           ~2-3 ms
3. Decompression:      ZSTD level 3 (200 KB → 2 MB)               ~5-10 ms
4. Deserialization:    Protobuf → List<CellState> (~100k cells)    ~10-15 ms
5. Region Filtering:   Java stream filter (viewport 250×250)       ~3-5 ms
6. Coord Conversion:   flatIndex → coordinates (viewport cells)    ~1-2 ms
───────────────────────────────────────────────────────────────────────────
Total:                                                              ~25-40 ms
```

**Scenario B: High Occupancy, Non-Power-of-2 Width (~50% - mature simulation):**
```
Environment: 1000×1000, ~500k occupied cells
BLOB size: ~10 MB raw → ~1 MB compressed (ZSTD level 3)

1. Metadata Query:     Cache hit (first request: ~5-10ms)          ~0.05 ms ⚡
2. SQL Query:          SELECT cells_blob WHERE tick_number = ?     ~1-2 ms
3. BLOB Transfer:      Network/Disk → JVM heap (~1 MB)             ~5-10 ms
4. Decompression:      ZSTD level 3 (1 MB → 10 MB)                ~20-30 ms
5. Deserialization:    Protobuf → List<CellState> (~500k cells)    ~50-70 ms
6. Region Filtering:   2D with modulo/div (viewport 250×250)       ~7 ms ⚡
7. Coord Conversion:   Already done in filtering                   ~0 ms ⚡
───────────────────────────────────────────────────────────────────────────
Total (first request):                                              ~90-120 ms
Total (cached):                                                     ~85-115 ms ⚡
```

**Scenario C: High Occupancy, Power-of-2 Width (~50% - optimized dimensions):**
```
Environment: 1024×1024, ~524k occupied cells
BLOB size: ~10.5 MB raw → ~1.05 MB compressed (ZSTD level 3)

1. Metadata Query:     Cache hit (first request: ~5-10ms)          ~0.05 ms ⚡
2. SQL Query:          SELECT cells_blob WHERE tick_number = ?     ~1-2 ms
3. BLOB Transfer:      Network/Disk → JVM heap (~1.05 MB)          ~5-10 ms
4. Decompression:      ZSTD level 3 (1.05 MB → 10.5 MB)           ~21-31 ms
5. Deserialization:    Protobuf → List<CellState> (~524k cells)    ~52-73 ms
6. Region Filtering:   2D with bit-ops! (viewport 256×256)         ~3 ms ⚡⚡
7. Coord Conversion:   Already done in filtering                   ~0 ms ⚡
───────────────────────────────────────────────────────────────────────────
Total (first request):                                              ~86-117 ms
Total (cached):                                                     ~81-112 ms ⚡⚡
```

**Note:** Power-of-2 dimensions (1024, 2048, 4096) enable bit-operations optimization,
saving additional ~4ms in filtering phase (~57% faster coordinate calculation).

**Performance Improvements Applied:**
- ⚡ Metadata caching (LRU): -5-10ms (99% cache hit rate)
- ⚡ 2D-specialized filtering: -8-13ms (vs stream-based)
- ⚡ Bit-ops auto-tuning: -4ms additional (only for power-of-2 widths like 1024, 2048)
- ⚡ Record-based JSON: -6-11ms (vs POJO+Reflection for 31k cells)
- ⚡ Early connection release: +18% pool capacity

**Target Response Time:** 100-250 ms
- Database read (cached, high occupancy): ~85-115 ms (~40-50% of budget) ⚡
- JSON serialization (Record-based): ~13-15 ms (viewport 250×250 ≈ 31k cells) ⚡
- HTTP overhead: ~5-10 ms
- Network latency: varies
- **Result:** Comfortably within target even at 50% occupancy ✅

**Scalability:**
- 10 concurrent requests: All proceed in parallel (10 connections)
- 100 concurrent requests: Queue builds up at pool limit (increase maxPoolSize)

**Environment Size Limits (at 50% occupancy):**
- 250×250: ~30k cells → ~15-25 ms read ✅
- 500×500: ~125k cells → ~40-60 ms read ✅
- 1000×1000 (target): ~500k cells → ~90-135 ms read ✅
- 2000×2000: ~2M cells → ~400-600 ms read ❌ (exceeds budget)
- **For 2000×2000+:** SpatialIndexStrategy required

### Future: SpatialIndexStrategy

**For Very Large Environments (2000×2000+):**
```sql
-- Spatial index on coordinates
CREATE INDEX idx_env_spatial ON environment_cells (tick_number, x, y);

-- Query only region
SELECT x, y, molecule_type, molecule_value, owner_id
FROM environment_cells
WHERE tick_number = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ?;
```

**Benefits:**
- Only reads relevant cells (viewport vs. entire environment)
- Database-level filtering (faster than Java)
- Scales to massive environments (2000×2000+ at high occupancy)

**Trade-offs:**
- More storage (row per cell vs. single BLOB)
- More complex schema
- Strategy switch required (not drop-in replacement)

## Implementation Steps

**Recommended: 3 testable phases with clear verification points**

### Phase 1: Database Reader Infrastructure (2-3 hours)

**Implementation:**
1. Create API interfaces in `api/resources/database/`:
   - `IDatabaseReaderProvider.java`
   - `IDatabaseReader.java`
   - `IEnvironmentDataReader.java`
   - `SpatialRegion.java` (data class only)
   - `CellWithCoordinates.java` (Record, stub for now)

2. Extend `H2Database.java`:
   - Implement `IDatabaseReaderProvider`
   - Add `createReader(String runId)` method
   - Add `findLatestRunId()` method (use existing `doGetRunIdInCurrentSchema`)
   - Add metadata cache (LinkedHashMap with LRU)
   - Update `getMetadataInternal()` to use cache

3. Create `H2DatabaseReader.java`:
   - Constructor with Connection, H2Database, Strategy, runId
   - Implement `close()` (return connection to pool)
   - Implement `getMetadata()` delegation
   - Stub `readEnvironmentRegion()` (throw UnsupportedOperationException)

**Verification Tests (can run immediately):**
```java
/**
 * Phase 1 tests: Database reader infrastructure
 * Uses in-memory H2 database (AGENTS.md: "If database needed: use in-memory")
 */
@Tag("integration")  // Uses database (H2 in-memory)
class DatabaseReaderInfrastructureTest {
    
    private H2Database database;
    private String testRunId;
    
    @BeforeEach
    void setUp() {
        // In-memory H2 database (no filesystem I/O, fast tests)
        Config config = ConfigFactory.parseString(
            "jdbcUrl = \"jdbc:h2:mem:test-phase1-" + System.nanoTime() + ";MODE=PostgreSQL\"\n" +
            "maxPoolSize = 5\n" +
            "metadataCacheSize = 10\n"
        );
        database = new H2Database("test-db", config);
        testRunId = "20251021_140000_TEST";
        
        // Setup: Create schema + metadata for tests
        setupTestMetadata(testRunId);
    }
    
    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();  // Close connection pool
        }
    }
    
    @Test
    void createReader_setsSchemaAndReturnsReader() {
        try (IDatabaseReader reader = database.createReader(testRunId)) {
            assertNotNull(reader);
            assertEquals(testRunId, reader.getRunIdInCurrentSchema());
        }
    }
    
    @Test
    void metadataCache_cachesResults() {
        try (Connection conn = database.getConnection()) {
            long t1 = measureTime(() -> database.getMetadataInternal(conn, testRunId));
            long t2 = measureTime(() -> database.getMetadataInternal(conn, testRunId));
            assertTrue(t2 < t1 / 10);  // Cache >10× faster
        }
    }
    
    @Test
    void findLatestRunId_returnsNewestRun() {
        setupTestMetadata("20251020_120000");
        setupTestMetadata("20251021_140000");  // Newer
        assertEquals("20251021_140000", database.findLatestRunId());
    }
    
    @Test
    void connectionReturnedToPoolOnClose() {
        IDatabaseReader r1 = database.createReader(testRunId);
        r1.close();
        IDatabaseReader r2 = database.createReader(testRunId);  // Should reuse
        r2.close();
        // Verify no connection leak via HikariCP metrics
    }
}
```

**Success Criteria:** All Phase 1 tests green, no connection leaks.

---

### Phase 2: Strategy Read & Filtering (3-4 hours)

**Implementation:**
1. Extend `IH2EnvStorageStrategy.java`:
   - Add `readTick(Connection, long, SpatialRegion, EnvironmentProperties)` method

2. Implement `SingleBlobStrategy.readTick()`:
   - Read BLOB from database
   - Auto-detect compression (CompressionCodecFactory.detectFromMagicBytes) ✅
   - Decompress BLOB
   - Deserialize Protobuf CellStateList
   - Filter by region: `filterByRegion()` dispatcher
   - `filterByRegion2D()`: 2D-optimized with bit-ops auto-tuning
   - `filterByRegionND()`: N-D with array reuse

3. Implement `CellWithCoordinates` (Record with @JsonAutoDetect)

4. Complete `H2DatabaseReader.readEnvironmentRegion()`:
   - Call `envStrategy.readTick()`
   - Convert flatIndex → coordinates
   - Return `List<CellWithCoordinates>`

5. Extend `EnvironmentProperties.java` (if not done):
   - `flatIndexToCoordinates(int, int[])` ✅ already implemented
   - `getDimensions()` ✅ already implemented

**Verification Tests (isolated from HTTP):**
```java
/**
 * Phase 2 tests: Strategy read and filtering
 * Uses in-memory H2 database with real EnvironmentIndexer for test data
 */
@Tag("integration")  // Uses database + EnvironmentIndexer
class StrategyReadAndFilteringTest {
    
    private H2Database database;
    private String testRunId;
    
    @BeforeEach
    void setUp() {
        // In-memory H2 database (no filesystem I/O)
        Config config = ConfigFactory.parseString(
            "jdbcUrl = \"jdbc:h2:mem:test-phase2-" + System.nanoTime() + ";MODE=PostgreSQL\"\n" +
            "maxPoolSize = 5\n" +
            "h2EnvironmentStrategy {\n" +
            "  className = \"org.evochora.datapipeline.resources.database.h2.SingleBlobStrategy\"\n" +
            "  options {\n" +
            "    compression { enabled = true, codec = \"zstd\", level = 3 }\n" +
            "  }\n" +
            "}\n"
        );
        database = new H2Database("test-db", config);
        testRunId = "20251021_140000_TEST";
        
        // Setup test data via EnvironmentIndexer (realistic data)
        indexTestEnvironmentData(testRunId);
    }
    
    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }
    
    @Test
    void readTick_returnsAllCells() {
        try (IDatabaseReader reader = database.createReader(testRunId)) {
            List<CellWithCoordinates> cells = reader.readEnvironmentRegion(100, null);
            assertEquals(500_000, cells.size());
            // Verify coordinates converted (not flatIndex)
            assertTrue(cells.get(0).coordinates().length == 2);
        }
    }
    
    @Test
    void readTick_filtersBy2DRegion() {
        SpatialRegion viewport = new SpatialRegion(new int[]{100, 350, 100, 350});
        
        try (IDatabaseReader reader = database.createReader(testRunId)) {
            List<CellWithCoordinates> cells = reader.readEnvironmentRegion(100, viewport);
            
            // Verify all within bounds
            for (CellWithCoordinates cell : cells) {
                assertTrue(cell.coordinates()[0] >= 100 && cell.coordinates()[0] <= 350);
                assertTrue(cell.coordinates()[1] >= 100 && cell.coordinates()[1] <= 350);
            }
        }
    }
    
    @Test
    void readTick_bitOpsOptimization_powerOf2Width() {
        // Test with 1024×1000 environment (power-of-2 width enables bit-ops)
        String runId1024 = "20251021_150000_1024x1000";
        indexTestEnvironment1024x1000(runId1024);
        
        SpatialRegion region = new SpatialRegion(new int[]{0, 256, 0, 256});
        
        try (IDatabaseReader reader = database.createReader(runId1024)) {
            List<CellWithCoordinates> cells = reader.readEnvironmentRegion(100, region);
            assertFalse(cells.isEmpty());
            // Performance should be ~57% faster than non-power-of-2
        }
    }
    
    @Test
    void readTick_compressionAutoDetection() {
        // Verify ZSTD magic bytes are detected and decompression works
        try (IDatabaseReader reader = database.createReader(testRunId)) {
            List<CellWithCoordinates> result = reader.readEnvironmentRegion(100, null);
            assertFalse(result.isEmpty());  // Auto-detection + decompression worked
        }
    }
    
    @Test
    void cellWithCoordinates_jsonSerialization() {
        CellWithCoordinates cell = new CellWithCoordinates(
            new int[]{42, 73}, 1, 255, 7
        );
        
        // Test Jackson serialization with @JsonAutoDetect
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(cell);
        assertTrue(json.contains("\"coordinates\":[42,73]"));
        assertTrue(json.contains("\"moleculeType\":1"));
    }
}
```

**Success Criteria:** All filtering tests green, compression auto-detection works, coordinates correct.

---

### Phase 3: HTTP Controller & Integration (2-3 hours)

**Implementation:**
1. Create `EnvironmentController.java`:
   - `getEnvironmentRegion()` with early connection release
   - `getMetadata()` 
   - HTTP Cache-Headers (immutable)
   - Error handling (400, 404, 500)
   - Run-ID resolution (query → config → latest)

2. Update `HttpServerProcess.java`:
   - Get `IDatabaseReaderProvider` from ServiceManager
   - Register in controller ServiceRegistry
   - Parse routes from config

3. Update `evochora.conf`:
   - Add http.options.databaseProviderResourceName
   - Add routes for /visualizer/api
   - Add EnvironmentController config

**Verification Tests (End-to-End):**
```java
/**
 * Phase 3 tests: HTTP Controller integration
 * Uses in-memory H2 + embedded Javalin server (no external dependencies)
 * Target: <1s per test (AGENTS.md integration test guideline)
 */
@Tag("integration")  // Uses database + HTTP server
class EnvironmentControllerIntegrationTest {
    
    private H2Database database;
    private Javalin app;
    private String baseUrl;
    private String testRunId;
    
    @BeforeEach
    void setUp() {
        // In-memory H2 database
        Config dbConfig = ConfigFactory.parseString(
            "jdbcUrl = \"jdbc:h2:mem:test-phase3-" + System.nanoTime() + ";MODE=PostgreSQL\"\n" +
            "maxPoolSize = 20\n" +  // Support concurrent test requests
            "h2EnvironmentStrategy {\n" +
            "  className = \"org.evochora.datapipeline.resources.database.h2.SingleBlobStrategy\"\n" +
            "  options { compression { enabled = true, codec = \"zstd\", level = 3 } }\n" +
            "}\n"
        );
        database = new H2Database("test-db", dbConfig);
        testRunId = "20251021_140000_TEST";
        
        // Index test environment data
        indexTestEnvironment(testRunId, ticks0to100);
        
        // Start embedded Javalin server on random port
        app = Javalin.create().start(0);
        int port = app.port();
        baseUrl = "http://localhost:" + port;
        
        // Register EnvironmentController
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(IDatabaseReaderProvider.class, database);
        EnvironmentController controller = new EnvironmentController(registry, ConfigFactory.empty());
        controller.registerRoutes(app, "/visualizer/api");
    }
    
    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
        if (database != null) {
            database.close();
        }
    }
    
    @Test
    void httpEndpoint_returnsEnvironmentData() {
        Response resp = given()
            .queryParam("region", "0,250,0,250")
            .get(baseUrl + "/visualizer/api/100/environment");
        
        resp.then()
            .statusCode(200)
            .body("tick", equalTo(100))
            .body("runId", notNullValue())
            .body("cells.size()", greaterThan(0))
            .header("Cache-Control", containsString("immutable"));
    }
    
    @Test
    void httpEndpoint_cacheHeadersSet() {
        Response resp = given()
            .queryParam("region", "0,100,0,100")
            .get(baseUrl + "/visualizer/api/100/environment");
        
        assertEquals("public, max-age=31536000, immutable", 
                     resp.header("Cache-Control"));
        assertNotNull(resp.header("ETag"));
        assertTrue(resp.header("ETag").contains(testRunId));
    }
    
    @Test
    void httpEndpoint_errorHandling_invalidRegion() {
        Response resp = given()
            .queryParam("region", "0,100,0")  // Odd number (invalid)
            .get(baseUrl + "/visualizer/api/100/environment");
        
        resp.then()
            .statusCode(400)
            .body("error", containsString("Invalid region"));
    }
    
    @Test
    void httpEndpoint_runIdFallback_usesLatest() {
        // No runId in query → should use latest from database
        Response resp = given()
            .queryParam("region", "0,100,0,100")
            .get(baseUrl + "/visualizer/api/100/environment");
        
        resp.then().statusCode(200);
    }
    
    @Test
    void concurrentRequests_noConnectionLeaks() {
        // 20 parallel requests (pool size = 20)
        List<CompletableFuture<Response>> futures = IntStream.range(0, 20)
            .mapToObj(i -> CompletableFuture.supplyAsync(() -> 
                given().queryParam("region", "0,100,0,100")
                       .get(baseUrl + "/visualizer/api/" + i + "/environment")))
            .toList();
        
        List<Response> responses = futures.stream()
            .map(CompletableFuture::join)
            .toList();
        
        // All succeeded (200 or 404 if tick doesn't exist)
        assertTrue(responses.stream().allMatch(r -> 
            r.statusCode() == 200 || r.statusCode() == 404));
        
        // Verify no connection leaks (all connections returned to pool)
        // HikariCP metrics: activeConnections should be 0 after requests complete
    }
}
```

**Success Criteria:** All HTTP tests green, cache headers correct, no connection leaks under load.

---

## Summary: Testable 3-Phase Approach

| Phase | Duration | Tests | Can Verify |
|-------|----------|-------|------------|
| **1: Infrastructure** | 2-3h | 4-5 integration tests | Connection handling, caching, schema switching |
| **2: Strategy Read** | 3-4h | 5-6 integration tests | BLOB read, filtering, compression, coordinates |
| **3: HTTP API** | 2-3h | 5-7 integration tests | End-to-end, errors, concurrency, cache headers |
| **Total** | 7-10h | 14-18 tests | Full system verified step-by-step |

**Each phase builds on the previous, with independent verification at every step.**

---

## AGENTS.md Compliance

This specification follows all architectural principles and guidelines from AGENTS.md:

**Testing Guidelines:**
- ✅ All tests tagged with `@Tag("integration")` (use database)
- ✅ In-memory H2 database (no filesystem I/O, fast execution)
- ✅ `@BeforeEach` / `@AfterEach` cleanup (no artifacts left)
- ✅ Target: <1s per integration test
- ✅ No `Thread.sleep()` in tests
- ✅ Test data constructed inline (no separate files)

**Logging Guidelines:**
- ✅ Single-line logs only (no multi-line output)
- ✅ Resources use DEBUG-level for operations (connection pool, schema setup)
- ✅ WARN for transient errors (with `recordError()`)
- ✅ ERROR for fatal errors (connection pool init failed)
- ✅ No stack traces in error logs (framework logs at DEBUG)

**JavaDoc Requirements:**
- ✅ All public interfaces/classes have complete JavaDoc in English
- ✅ All methods document `@param`, `@return`, `@throws`
- ✅ Thread-safety guarantees documented
- ✅ Performance characteristics documented

**Data Pipeline Principles:**
- ✅ Resources implement `IResource` with lifecycle management
- ✅ Factory Pattern for per-request database readers
- ✅ ServiceRegistry for dependency injection
- ✅ Constructor DI: `(String name, Config options)`
- ✅ O(1) metrics recording (no lists, no iteration)

**Code Quality:**
- ✅ Minimal diffs (extends existing H2Database, doesn't replace)
- ✅ Comprehensive tests (14-18 tests across 3 phases)
- ✅ Maintains existing code style

## Future Extensions

### IOrganismDataReader (Phase 16)

When organism indexing is implemented, extend `IDatabaseReader` with organism reading capability:

```java
public interface IOrganismDataReader {
    /**
     * Reads organism state at a specific tick.
     * 
     * @param tickNumber Tick to read
     * @param organismId Organism ID
     * @return Organism state (registers, stacks, energy, etc.)
     * @throws SQLException if database read fails
     */
    OrganismState readOrganism(long tickNumber, int organismId) throws SQLException;
    
    /**
     * Lists all organisms alive at a specific tick.
     * 
     * @param tickNumber Tick to query
     * @return List of organism IDs
     * @throws SQLException if database read fails
     */
    List<Integer> listOrganisms(long tickNumber) throws SQLException;
}
```

Implementation follows same pattern:
- Add methods to `IDatabaseReader` interface (extends IOrganismDataReader)
- Implement in `H2DatabaseReader` with own SQL
- Use same connection/schema as environment queries
- No delegation needed (organisms use direct SQL, not strategy pattern)

---

**End of Specification**

