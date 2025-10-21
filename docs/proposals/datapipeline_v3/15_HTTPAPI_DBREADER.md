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
 */
class CellWithCoordinates {
    public final int[] coordinates;  // e.g., [x, y] or [x, y, z]
    public final int moleculeType;
    public final int moleculeValue;
    public final int ownerId;
}

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

### 3.2 Implementation in H2Database

```java
public class H2Database extends AbstractDatabaseResource 
                         implements IDatabaseReaderProvider,
                                   IMetadataReader,  // For indexers
                                   AutoCloseable {
    
    private final HikariDataSource dataSource;
    private final IH2EnvStorageStrategy envStorageStrategy;
    
    // ... existing code ...
    
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
    
    // Package-private: Used by H2DatabaseReader for metadata delegation
    SimulationMetadata getMetadataInternal(Connection conn, String runId) 
            throws SQLException {
        // Existing implementation from AbstractDatabaseResource
        return (SimulationMetadata) doGetMetadata(conn, runId);
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
     * Specialized 2D filtering without coordinate conversion overhead.
     * <p>
     * <strong>Performance:</strong> 2.2x faster than generic N-D filtering.
     * Uses direct 2D math: x = flatIndex % width, y = flatIndex / width.
     * <p>
     * <strong>Benchmark:</strong> 1000×1000 @ 500k cells → 15.6ms (vs 34.5ms stream-based)
     *
     * @param cells All cells from tick
     * @param region 2D spatial bounds [minX, maxX, minY, maxY]
     * @param width Environment width (for flatIndex → coordinates)
     * @return Filtered cells within region bounds
     */
    private List<CellState> filterByRegion2D(List<CellState> cells, 
                                             SpatialRegion region, 
                                             int width) {
        List<CellState> result = new ArrayList<>(cells.size() / 4); // Pre-size estimate
        
        int minX = region.bounds[0];
        int maxX = region.bounds[1];
        int minY = region.bounds[2];
        int maxY = region.bounds[3];
        
        for (CellState cell : cells) {
            int flatIndex = cell.getFlatIndex();
            
            // Direct 2D coordinate calculation (no array allocation!)
            int x = flatIndex % width;
            int y = flatIndex / width;
            
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
     * This is the ONLY transformation done in reader - all other logic in Strategy.
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
            // Parse parameters
            long tick = Long.parseLong(ctx.pathParam("tick"));
            String regionParam = ctx.queryParam("region"); // "min_x,max_x,min_y,max_y,..." (dimension-agnostic)
            
            // Resolve run-id (query → config → latest)
            String runId = resolveRunId(ctx);
            
            // Thread-safe per-request reader
            try (IDatabaseReader reader = dbProvider.createReader(runId)) {
                SpatialRegion region = parseRegion(regionParam);
                
                List<CellWithCoordinates> cells = reader.readEnvironmentRegion(tick, region);
                
                ctx.json(Map.of(
                    "tick", tick,
                    "runId", runId,
                    "region", region,
                    "cells", cells
                ));
                
            } // Auto-close returns connection to pool
            
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

**Scenario B: High Occupancy (~50% - mature simulation):**
```
Environment: 1000×1000, ~500k occupied cells
BLOB size: ~10 MB raw → ~1 MB compressed (ZSTD level 3)

1. SQL Query:          SELECT cells_blob WHERE tick_number = ?     ~1-2 ms
2. BLOB Transfer:      Network/Disk → JVM heap (~1 MB)             ~5-10 ms
3. Decompression:      ZSTD level 3 (1 MB → 10 MB)                ~20-30 ms
4. Deserialization:    Protobuf → List<CellState> (~500k cells)    ~50-70 ms
5. Region Filtering:   Java stream filter (viewport 250×250)       ~10-20 ms
6. Coord Conversion:   flatIndex → coordinates (viewport cells)    ~1-2 ms
───────────────────────────────────────────────────────────────────────────
Total:                                                              ~90-135 ms
```

**Target Response Time:** 100-250 ms
- Database read (high occupancy): ~90-135 ms (~40-60% of budget)
- JSON serialization: ~10-20 ms (viewport cells)
- HTTP overhead: ~5-10 ms
- Network latency: varies
- **Result:** Within target even at 50% occupancy ✅

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

1. **Define Interfaces** (`IDatabaseReader`, `IDatabaseReaderProvider`, `IEnvironmentDataReader`)
2. **Extend H2Database** to implement `IDatabaseReaderProvider`
3. **Create H2DatabaseReader** implementing `IDatabaseReader` (in `h2/` subpackage)
4. **Extend IH2EnvStorageStrategy** with read capability (readTick method)
5. **Implement SingleBlobStrategy.readTick()** with BLOB deserialization + region filtering
6. **Update H2Database.createReader()** to pass strategy to reader
7. **Update HttpServerProcess** to register provider in ServiceRegistry
8. **Create EnvironmentController** using the factory
9. **Add routes configuration** in evochora.conf
10. **Write unit tests** (provider, reader, strategy, conversion logic)
11. **Write integration tests** (concurrent access, connection leaks, performance)
12. **Test with real simulation data** (EnvironmentIndexer → API query)

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

