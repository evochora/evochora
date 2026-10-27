# Data Pipeline V3 - Database Resource and Metadata Indexer (Phase 2.4)

## Goal

Implement database resource abstraction and metadata indexer service that reads simulation metadata from storage and writes it to a database for query access by the web client HTTP API. This phase establishes the foundation for all indexer services that will process simulation data for analysis and visualization.

## Scope

**This phase implements:**
1. Database resource abstraction with single metadata capability interface
2. H2 database implementation with HikariCP connection pooling
3. AbstractIndexer base class with run discovery logic
4. MetadataIndexer service that indexes SimulationMetadata
5. Schema-per-run database design
6. Integration tests verifying storage → indexer → database flow

**This phase does NOT implement:**
- Organism indexer (deferred - not part of this implementation)
- Environment/cell indexer (deferred - not part of this implementation)
- HTTP API for querying indexed data (future phase)
- PostgreSQL implementation (designed for, implemented later)
- Competing consumer coordination for batch indexers (not needed for metadata indexer)

## Success Criteria

Upon completion:
1. IMetadataDatabase capability interface defined in API
2. AbstractDatabaseResource implements connection pooling, wrapper creation, and monitoring
3. H2Database concrete implementation working with in-memory and file-based modes
4. MetadataDatabaseWrapper implements IMetadataDatabase with dedicated connection
5. AbstractIndexer implements run discovery (config runId vs. timestamp-based)
6. MetadataIndexer polls for metadata.pb, creates schema, writes to database
7. Schema-per-run design with sanitized schema names (sim_timestamp_uuid)
8. Dedicated connection per indexer instance with setSchema() support
9. Transaction management per database operation (handled internally)
10.IBatchStorageRead.listRunIds(Instant afterTimestamp) for run discovery
11. Integration tests verify SimulationEngine → Storage → MetadataIndexer → Database flow
12. All tests pass with proper cleanup

## Prerequisites

- Phase 0: API Foundation (completed) - IResource, IMonitorable interfaces
- Phase 1.2: Core Resource Implementation (completed) - Resource abstraction patterns
- Phase 2.2: Storage Resource (completed) - IBatchStorageRead/Write interfaces
- Phase 2.3: Persistence Service (completed) - MetadataPersistenceService writes metadata.pb

## Architectural Context

### Data Flow

```
SimulationEngine → Queue → MetadataPersistenceService → Storage (metadata.pb)
                                                            ↓
                                                      MetadataIndexer
                                                            ↓
                                                        Database
                                                            ↓
                                                 HTTP API (future)
                                                            ↓
                                                      Web Client
```

### Run Discovery Modes

**Mode 1: Configured RunId (Post-mortem)**
```
Indexer starts with runId="20251006143025-uuid" in config
  ↓
Directly reads metadata.pb from storage
  ↓
Creates schema and indexes
```

**Mode 2: Timestamp-based (Parallel to simulation)**
```
Indexer starts at time T
  ↓
Polls storage.listRunIds(T) every pollIntervalMs
  ↓
Returns first runId with timestamp > T
  ↓
Polls for {runId}/metadata.pb until exists
  ↓
Creates schema and indexes
```

### Schema-per-Run Design

Each simulation run gets its own database schema:

```sql
-- Run: 20251006143025-550e8400-e29b-41d4-a716-446655440000
-- Schema: sim_20251006143025_550e8400_e29b_41d4_a716_446655440000

CREATE SCHEMA sim_20251006143025_550e8400_e29b_41d4_a716_446655440000;

-- Within schema:
CREATE TABLE metadata (
  key VARCHAR PRIMARY KEY,
  value JSON
);

-- Future: organism indexer creates these
CREATE TABLE organisms (...);
CREATE TABLE organism_states (...);

-- Future: environment indexer creates these
CREATE TABLE cells (...);
```

**Benefits:**
- Complete isolation between runs
- Easy deletion (DROP SCHEMA)
- Dimension-specific table structures (2D vs 3D)
- No runId column pollution
- Concurrent indexing of different runs

## Package Structure

```
org.evochora.datapipeline.api.resources.database/
  - IMetadataDatabase.java       # Metadata indexer operations
  - IOrganismDatabase.java        # Organism indexer operations (future, not part of this scope)
  - IEnvironmentDatabase.java     # Environment indexer operations (future, not part of this scope)

org.evochora.datapipeline.resources.database/
  - AbstractDatabaseResource.java      # Base class with pooling, monitoring, addCustomMetrics()
  - H2Database.java                    # H2 implementation
  - MetadataDatabaseWrapper.java       # IMetadataDatabase wrapper with dedicated connection
  - OrganismDatabaseWrapper.java       # IOrganismDatabase wrapper with dedicated connection (future, not part of this scope)
  - EnvironmentDatabaseWrapper.java    # IEnvironmentDatabase wrapper with dedicated connection (future, not part of this scope)

org.evochora.datapipeline.services.indexers/
  - AbstractIndexer.java          # Base indexer with run discovery
  - MetadataIndexer.java          # Metadata indexing implementation
```

**Note:** Additional capability interfaces (IOrganismDatabase, IEnvironmentDatabase) and their wrappers will be added when implementing future indexers. The architecture is designed to support this extension pattern.

## Database Resource Architecture

### Interface Design (Capability-Based)

```java
public interface IMetadataDatabase extends IResource, IMonitorable, AutoCloseable {
    /**
     * Sets the database schema for all subsequent operations.
     * Must be called once after indexer discovers its runId.
     * Thread-safe: each wrapper has isolated connection.
     *
     * @param simulationRunId Raw simulation run ID (will be sanitized internally)
     */
    void setSimulationRun(String simulationRunId);

    /**
     * Creates a new schema for a simulation run.
     * Uses CREATE SCHEMA IF NOT EXISTS internally.
     *
     * @param simulationRunId Raw simulation run ID (will be sanitized internally)
     */
    void createSimulationRun(String simulationRunId);

    /**
     * Writes complete simulation metadata to database.
     * Inserts into metadata table as key-value pairs.
     * Atomic operation - either all inserts succeed or all rollback.
     *
     * Initial implementation stores:
     * - 'environment' -> {"dimensions": 2, "shape": [100,100], "toroidal": [true,true]}
     * - 'simulation_info' -> {"runId": "...", "startTime": ..., "seed": ...}
     *
     * Future extensions can add more keys without schema changes.
     */
    void insertMetadata(SimulationMetadata metadata);

    /**
     * Closes the database wrapper and releases its dedicated connection.
     * <p>
     * Enables try-with-resources pattern:
     * <pre>
     * try (IMetadataDatabase db = resource.getWrappedResource(context)) {
     *     db.createSimulationRun(runId);
     *     db.insertMetadata(metadata);
     * }  // Connection automatically released
     * </pre>
     */
    @Override
    void close();
}

/**
 * Note: Schema name sanitization (toSchemaName) is intentionally NOT part of the public interface.
 * It is a protected abstract method in AbstractDatabaseResource, used internally by wrappers.
 * This maintains proper encapsulation - services work with raw runIds, and the database
 * handles all schema naming concerns internally without exposing implementation details.
 */
```

**Future capability interfaces (not implemented in this phase):**
- `IOrganismDatabase` - for organism indexers
- `IEnvironmentDatabase` - for environment/cell indexers

### AbstractDatabaseResource Implementation

```java
/**
 * Abstract base class for database resources.
 * <p>
 * Database-agnostic implementation that delegates connection management,
 * schema handling, and metrics collection to concrete implementations.
 * <p>
 * <strong>Performance Contract:</strong>
 * All metric recording operations MUST be O(1). Use atomic counters and bucket-based
 * data structures (LatencyBucket, RateBucket) for efficient lock-free recording.
 */
public abstract class AbstractDatabaseResource extends AbstractResource
    implements IMetadataDatabase, /* IOrganismDatabase, IEnvironmentDatabase, */
               IContextualResource, IMonitorable {

    // Base metrics (O(1) recording via atomic counters)
    protected final AtomicLong queriesExecuted = new AtomicLong(0);
    protected final AtomicLong rowsInserted = new AtomicLong(0);
    protected final AtomicLong writeErrors = new AtomicLong(0);
    protected final AtomicLong readErrors = new AtomicLong(0);

    // Error tracking (like storage/queue resources)
    protected final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();
    private static final int MAX_ERRORS = 100;

    protected AbstractDatabaseResource(String name, Config options) {
        super(name, options);
    }

    @Override
    public IWrappedResource getWrappedResource(ResourceContext context) {
        // Returns appropriate wrapper based on usage type
        String usageType = context.usageType();

        return switch (usageType) {
            case "database-metadata" -> new MetadataDatabaseWrapper(this, context);
            /*case "database-organisms" -> new OrganismDatabaseWrapper(this, context);
            case "database-environment" -> new EnvironmentDatabaseWrapper(this, context);*/
            default -> throw new IllegalArgumentException(
                "Unknown database usage type: " + usageType +
                ". Supported: database-metadata"
            );
        };
    }

    // ==================== Abstract Methods (implemented by concrete databases) ====================

    /**
     * Acquires a dedicated connection for exclusive use by a wrapper.
     * <p>
     * Connection lifecycle:
     * - Acquired on wrapper creation
     * - Held for wrapper's entire lifetime
     * - Returned to pool (if applicable) when wrapper is closed
     * <p>
     * Concrete implementations handle connection pooling strategy
     * (e.g., HikariCP for JDBC, connection reuse for embedded DBs).
     *
     * @return Database-specific connection handle
     * @throws Exception if connection acquisition fails
     */
    protected abstract Object acquireDedicatedConnection() throws Exception;

    /**
     * Sanitizes simulation run ID to valid schema/namespace identifier.
     * <p>
     * Different databases have different naming requirements:
     * - SQL: alphanumeric + underscore only
     * - NoSQL: may allow hyphens
     * - Case sensitivity varies
     * <p>
     * Example (SQL databases):
     * <pre>
     * 20251006143025-550e8400-e29b-41d4-a716-446655440000
     * → sim_20251006143025_550e8400_e29b_41d4_a716_446655440000
     * </pre>
     *
     * @param simulationRunId Raw simulation run ID
     * @return Sanitized schema/namespace name
     */
    protected abstract String toSchemaName(String simulationRunId);

    /**
     * Implementation-specific method to insert metadata.
     * Called by MetadataDatabaseWrapper after connection and schema setup.
     * <p>
     * Concrete implementation handles:
     * - Schema creation (CREATE SCHEMA IF NOT EXISTS)
     * - Table creation (CREATE TABLE IF NOT EXISTS metadata)
     * - Data insertion with transaction management
     * - Metric tracking (O(1) recording)
     *
     * @param connection Database-specific connection handle
     * @param metadata Simulation metadata to persist
     * @throws Exception if insertion fails
     */
    protected abstract void doInsertMetadata(Object connection, SimulationMetadata metadata) throws Exception;

    /**
     * Sets the active schema/namespace for subsequent operations.
     * <p>
     * SQL example: SET SCHEMA schema_name
     * NoSQL example: Switch database context
     *
     * @param connection Database-specific connection handle
     * @param schemaName Sanitized schema name from toSchemaName()
     * @throws Exception if schema switching fails
     */
    protected abstract void doSetSchema(Object connection, String schemaName) throws Exception;

    /**
     * Creates a new schema/namespace for a simulation run.
     * <p>
     * Must be idempotent (CREATE ... IF NOT EXISTS).
     * Includes transaction commit if applicable.
     *
     * @param connection Database-specific connection handle
     * @param schemaName Sanitized schema name from toSchemaName()
     * @throws Exception if schema creation fails
     */
    protected abstract void doCreateSchema(Object connection, String schemaName) throws Exception;

    // ==================== Error Tracking (like storage/queue resources) ====================

    protected void recordError(String code, String message, String details) {
        errors.add(new OperationalError(Instant.now(), code, message, details));

        // Prevent unbounded memory growth
        while (errors.size() > MAX_ERRORS) {
            errors.pollFirst();
        }
    }

    @Override
    public boolean isHealthy() {
        // Resources track operational errors, not lifecycle state (State is for services only)
        // Consistent with AbstractBatchStorageResource pattern
        return errors.isEmpty();
    }

    @Override
    public List<OperationalError> getErrors() {
        return new ArrayList<>(errors);
    }

    @Override
    public void clearErrors() {
        errors.clear();
    }

    // ==================== Monitoring (Template Method Pattern) ====================

    @Override
    public final Map<String, Number> getMetrics() {
        Map<String, Number> metrics = getBaseMetrics();
        addCustomMetrics(metrics);
        return metrics;
    }

    /**
     * Returns base metrics tracked by AbstractDatabaseResource.
     * Final - cannot be overridden.
     * <p>
     * <strong>Performance:</strong> All metrics use O(1) atomic operations.
     */
    protected final Map<String, Number> getBaseMetrics() {
        Map<String, Number> metrics = new LinkedHashMap<>();

        // Query metrics (O(1) atomic reads)
        metrics.put("queries_executed", queriesExecuted.get());
        metrics.put("rows_inserted", rowsInserted.get());
        metrics.put("write_errors", writeErrors.get());
        metrics.put("read_errors", readErrors.get());
        metrics.put("error_count", errors.size());

        return metrics;
    }

    /**
     * Hook method for subclasses to add implementation-specific metrics.
     * Default implementation does nothing.
     * <p>
     * <strong>Performance Contract:</strong> All custom metrics MUST use O(1) recording.
     * <p>
     * Example (H2Database):
     * <pre>
     * protected void addCustomMetrics(Map<String, Number> metrics) {
     *     // Connection pool metrics (O(1) via HikariCP MXBean)
     *     metrics.put("h2_active_connections", hikariPool.getActiveConnections());
     *     metrics.put("h2_idle_connections", hikariPool.getIdleConnections());
     *
     *     // Cache metrics (O(1) atomic counters)
     *     metrics.put("h2_cache_hit_ratio", cacheHits.get() / (double) totalAccesses.get());
     *
     *     // Rate metrics (O(1) via RateBucket)
     *     metrics.put("h2_disk_writes_per_sec", diskWritesBucket.getRate(now));
     * }
     * </pre>
     */
    protected void addCustomMetrics(Map<String, Number> metrics) {
        // Default: no custom metrics
    }
}
```

**Key Design Principles:**
1. **Database Agnostic**: No knowledge of JDBC, HikariCP, SQL, or any specific database technology
2. **O(1) Metrics**: Explicit contract that all recording must be constant-time
3. **Template Method**: Abstract methods delegate implementation details to concrete classes
4. **Error Tracking**: Follows storage/queue pattern with bounded error list
5. **Schema Naming**: Delegated to concrete class (different databases have different constraints)

### MetadataDatabaseWrapper

```java
/**
 * Database-agnostic wrapper for metadata database operations.
 * <p>
 * Holds dedicated connection for exclusive use by one indexer instance.
 * Delegates actual database operations to AbstractDatabaseResource methods.
 * <p>
 * <strong>Performance Contract:</strong> All metrics use O(1) recording
 * via atomic counters and LatencyBucket for percentile tracking.
 */
class MetadataDatabaseWrapper implements IMetadataDatabase, IWrappedResource, IMonitorable {
    private final AbstractDatabaseResource database;
    private final ResourceContext context;
    private final Object dedicatedConnection;  // Database-agnostic handle
    private String currentSimulationRunId;

    // Operation counters (O(1) recording via atomic operations)
    private final AtomicLong schemasCreated = new AtomicLong(0);
    private final AtomicLong metadataInserts = new AtomicLong(0);
    private final AtomicLong operationErrors = new AtomicLong(0);

    // Performance bucketing (O(1) recording, following storage/queue pattern)
    private final Map<String, LatencyBucket> operationLatencies = new ConcurrentHashMap<>();

    // Error tracking (bounded, like storage/queue)
    private final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();
    private static final int MAX_ERRORS = 100;

    MetadataDatabaseWrapper(AbstractDatabaseResource db, ResourceContext context) {
        this.database = db;
        this.context = context;

        try {
            this.dedicatedConnection = db.acquireDedicatedConnection();
        } catch (Exception e) {
            throw new RuntimeException("Failed to acquire database connection", e);
        }

        // Initialize latency buckets
        operationLatencies.put("create_simulation_run", new LatencyBucket());
        operationLatencies.put("set_simulation_run", new LatencyBucket());
        operationLatencies.put("insert_metadata", new LatencyBucket());
    }

    @Override
    public void setSimulationRun(String simulationRunId) {
        long startNanos = System.nanoTime();
        try {
            String schemaName = database.toSchemaName(simulationRunId);
            database.doSetSchema(dedicatedConnection, schemaName);
            this.currentSimulationRunId = simulationRunId;

            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            operationLatencies.get("set_simulation_run").record(durationMs);

        } catch (Exception e) {
            operationErrors.incrementAndGet();
            recordError("SET_SCHEMA_FAILED", "Failed to set simulation run",
                       "RunId: " + simulationRunId + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to set simulation run: " + simulationRunId, e);
        }
    }

    @Override
    public void createSimulationRun(String simulationRunId) {
        long startNanos = System.nanoTime();
        try {
            String schemaName = database.toSchemaName(simulationRunId);
            database.doCreateSchema(dedicatedConnection, schemaName);
            schemasCreated.incrementAndGet();

            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            operationLatencies.get("create_simulation_run").record(durationMs);

        } catch (Exception e) {
            operationErrors.incrementAndGet();
            recordError("CREATE_SCHEMA_FAILED", "Failed to create simulation run",
                       "RunId: " + simulationRunId + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to create simulation run: " + simulationRunId, e);
        }
    }

    @Override
    public void insertMetadata(SimulationMetadata metadata) {
        long startNanos = System.nanoTime();
        try {
            // Delegates to database-specific implementation
            database.doInsertMetadata(dedicatedConnection, metadata);
            metadataInserts.incrementAndGet();

            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            operationLatencies.get("insert_metadata").record(durationMs);

        } catch (Exception e) {
            operationErrors.incrementAndGet();
            recordError("INSERT_METADATA_FAILED", "Failed to insert metadata",
                       "RunId: " + metadata.getSimulationRunId() + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to insert metadata", e);
        }
    }

    private void recordError(String code, String message, String details) {
        errors.add(new OperationalError(Instant.now(), code, message, details));

        // Prevent unbounded memory growth
        while (errors.size() > MAX_ERRORS) {
            errors.pollFirst();
        }
    }

    @Override
    public String getResourceName() {
        return database.getResourceName();
    }

    @Override
    public Map<String, Number> getUsageStats() {
        return Map.of(
            "schemas_created", schemasCreated.get(),
            "metadata_inserts", metadataInserts.get(),
            "operation_errors", operationErrors.get()
        );
    }

    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new LinkedHashMap<>();

        // Operation counts (O(1) atomic reads)
        metrics.put("schemas_created", schemasCreated.get());
        metrics.put("metadata_inserts", metadataInserts.get());
        metrics.put("operation_errors", operationErrors.get());
        metrics.put("error_count", errors.size());
        metrics.put("current_simulation_run_set", currentSimulationRunId != null ? 1 : 0);

        // Latency percentiles (O(1) calculation from buckets)
        for (Map.Entry<String, LatencyBucket> entry : operationLatencies.entrySet()) {
            String op = entry.getKey();
            LatencyBucket bucket = entry.getValue();
            metrics.put(op + "_latency_p50", bucket.getPercentile(50));
            metrics.put(op + "_latency_p95", bucket.getPercentile(95));
            metrics.put(op + "_latency_p99", bucket.getPercentile(99));
        }

        return metrics;
    }

    @Override
    public boolean isHealthy() {
        // Delegate health check to database resource
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
}
```

**Implementing close() for AutoCloseable:**
```java
@Override
public void close() {
    // Release dedicated connection back to pool
    if (dedicatedConnection != null && dedicatedConnection instanceof Connection) {
        try {
            ((Connection) dedicatedConnection).close();
        } catch (SQLException e) {
            log.warn("Failed to close database connection", e);
        }
    }
}

@Override
public String toSchemaName(String simulationRunId) {
    // Delegate to database resource
    return database.toSchemaName(simulationRunId);
}
```

**Note:** Since IMetadataDatabase extends AutoCloseable, the wrapper must implement close() to release the dedicated connection back to the pool.

**Future wrapper implementations (not part of this phase):**
```java
class OrganismDatabaseWrapper implements IOrganismDatabase, IWrappedResource, IMonitorable {
    // Similar structure - dedicated connection, organism-specific metrics
}

class EnvironmentDatabaseWrapper implements IEnvironmentDatabase, IWrappedResource, IMonitorable {
    // Similar structure - dedicated connection, environment-specific metrics
}
```

### H2Database Implementation

```java
/**
 * H2 database implementation using HikariCP for connection pooling.
 * <p>
 * Supports both in-memory and file-based modes for testing and production.
 * <p>
 * <strong>Performance:</strong> All metrics use O(1) recording via atomic counters
 * and RateBucket for moving window averages.
 */
public class H2Database extends AbstractDatabaseResource {

    // HikariCP connection pool (H2-specific implementation detail)
    private final HikariDataSource dataSource;

    // H2-specific metrics (O(1) recording via atomic operations)
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong diskReads = new AtomicLong(0);
    private final AtomicLong diskWrites = new AtomicLong(0);

    // Moving window rate buckets (O(1) recording, configurable window size)
    private final Map<String, RateBucket> rateBuckets = new ConcurrentHashMap<>();
    private final int metricsWindowSizeMs;

    public H2Database(String name, Config options) {
        super(name, options);

        // Initialize HikariCP connection pool
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(getJdbcUrl(options));
        hikariConfig.setMaximumPoolSize(options.hasPath("maxPoolSize") ?
            options.getInt("maxPoolSize") : 10);
        hikariConfig.setMinimumIdle(options.hasPath("minIdle") ?
            options.getInt("minIdle") : 2);
        hikariConfig.setConnectionTimeout(options.hasPath("connectionTimeout") ?
            options.getLong("connectionTimeout") : 30000);
        hikariConfig.setIdleTimeout(options.hasPath("idleTimeout") ?
            options.getLong("idleTimeout") : 600000);
        hikariConfig.setMaxLifetime(options.hasPath("maxLifetime") ?
            options.getLong("maxLifetime") : 1800000);

        this.dataSource = new HikariDataSource(hikariConfig);

        // Configurable metrics window size (default 5 seconds per user preference)
        this.metricsWindowSizeMs = options.hasPath("metricsWindowSizeMs") ?
            options.getInt("metricsWindowSizeMs") : 5000;

        // Initialize rate buckets with configurable window
        rateBuckets.put("cache_hits", new RateBucket(metricsWindowSizeMs));
        rateBuckets.put("cache_misses", new RateBucket(metricsWindowSizeMs));
        rateBuckets.put("disk_reads", new RateBucket(metricsWindowSizeMs));
        rateBuckets.put("disk_writes", new RateBucket(metricsWindowSizeMs));
    }

    private String getJdbcUrl(Config options) {
        if (options.hasPath("jdbcUrl")) {
            return options.getString("jdbcUrl");
        }

        // Require dataDirectory if jdbcUrl not provided
        if (!options.hasPath("dataDirectory")) {
            throw new IllegalArgumentException(
                "Either 'jdbcUrl' or 'dataDirectory' must be configured for H2Database"
            );
        }

        String dataDir = options.getString("dataDirectory");

        // Expand environment variables and system properties (like storage resources)
        String expandedPath = expandPath(dataDir);
        if (!dataDir.equals(expandedPath)) {
            log.debug("Expanded dataDirectory: '{}' -> '{}'", dataDir, expandedPath);
        }

        return "jdbc:h2:" + expandedPath + "/evochora;MODE=PostgreSQL";
    }

    /**
     * Expands environment variables and Java system properties in a path string.
     * Supports syntax: ${VAR} for both environment variables and system properties.
     * System properties are checked first, then environment variables.
     * <p>
     * Examples:
     * <ul>
     *   <li>${user.home}/data → /home/user/data</li>
     *   <li>${HOME}/evochora → /home/user/evochora</li>
     *   <li>${EVOCHORA_DATA_DIR} → /var/lib/evochora</li>
     * </ul>
     *
     * @param path the path potentially containing variables like ${HOME} or ${user.home}
     * @return the path with all variables expanded
     * @throws IllegalArgumentException if a variable is referenced but not defined
     */
    private static String expandPath(String path) {
        if (path == null || !path.contains("${")) {
            return path;
        }

        StringBuilder result = new StringBuilder();
        int pos = 0;

        while (pos < path.length()) {
            int startVar = path.indexOf("${", pos);
            if (startVar == -1) {
                // No more variables, append rest of string
                result.append(path.substring(pos));
                break;
            }

            // Append text before variable
            result.append(path.substring(pos, startVar));

            int endVar = path.indexOf("}", startVar + 2);
            if (endVar == -1) {
                throw new IllegalArgumentException("Unclosed variable in path: " + path);
            }

            String varName = path.substring(startVar + 2, endVar);
            String value = resolveVariable(varName);

            if (value == null) {
                throw new IllegalArgumentException(
                    "Undefined variable '${" + varName + "}' in path: " + path +
                    ". Check that environment variable or system property exists."
                );
            }

            result.append(value);
            pos = endVar + 1;
        }

        return result.toString();
    }

    /**
     * Resolves a variable name to its value, checking system properties first, then environment variables.
     *
     * @param varName the variable name (without ${} delimiters)
     * @return the resolved value, or null if not found
     */
    private static String resolveVariable(String varName) {
        // Check system properties first (e.g., user.home, java.io.tmpdir)
        String value = System.getProperty(varName);
        if (value != null) {
            return value;
        }

        // Check environment variables (e.g., HOME, USERPROFILE, EVOCHORA_DATA_DIR)
        return System.getenv(varName);
    }

    @Override
    protected Object acquireDedicatedConnection() throws Exception {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);  // Manual transaction control
        return conn;
    }

    @Override
    protected String toSchemaName(String simulationRunId) {
        // SQL identifier: alphanumeric + underscore only
        // 20251006143025-550e8400-e29b-41d4-a716-446655440000
        // → sim_20251006143025_550e8400_e29b_41d4_a716_446655440000
        return "sim_" + simulationRunId.replace("-", "_");
    }

    @Override
    protected void doSetSchema(Object connection, String schemaName) throws Exception {
        Connection conn = (Connection) connection;
        conn.createStatement().execute("SET SCHEMA " + schemaName);
    }

    @Override
    protected void doCreateSchema(Object connection, String schemaName) throws Exception {
        Connection conn = (Connection) connection;
        conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS " + schemaName);
        conn.commit();
    }

    @Override
    protected void doInsertMetadata(Object connection, SimulationMetadata metadata) throws Exception {
        Connection conn = (Connection) connection;
        long now = System.currentTimeMillis();

        try {
            // Create metadata table if not exists
            conn.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS metadata (" +
                "  key VARCHAR PRIMARY KEY," +
                "  value JSON" +
                ")"
            );

            // Prepare key-value pairs using GSON for type-safe JSON generation
            Gson gson = new Gson();
            Map<String, String> kvPairs = new HashMap<>();

            // Environment config (needed by environment indexers)
            EnvironmentConfig env = metadata.getEnvironment();
            Map<String, Object> envMap = Map.of(
                "dimensions", env.getDimensions(),
                "shape", env.getShapeList(),
                "toroidal", env.getToroidalList()
            );
            kvPairs.put("environment", gson.toJson(envMap));

            // Simulation info (useful for web client)
            Map<String, Object> simInfoMap = Map.of(
                "runId", metadata.getSimulationRunId(),
                "startTime", metadata.getStartTimeMs(),
                "seed", metadata.getInitialSeed()
            );
            kvPairs.put("simulation_info", gson.toJson(simInfoMap));

            // Batch insert/update all key-value pairs (idempotent using MERGE)
            // MERGE allows safe restart after partial failure (schema created but insert failed)
            PreparedStatement stmt = conn.prepareStatement(
                "MERGE INTO metadata (key, value) KEY(key) VALUES (?, ?)"
            );

            for (Map.Entry<String, String> entry : kvPairs.entrySet()) {
                stmt.setString(1, entry.getKey());
                stmt.setString(2, entry.getValue());
                stmt.addBatch();
            }

            stmt.executeBatch();
            conn.commit();

            // Track metrics (O(1) recording)
            rowsInserted.addAndGet(kvPairs.size());
            queriesExecuted.incrementAndGet();
            diskWrites.incrementAndGet();  // H2-specific metric
            rateBuckets.get("disk_writes").record(now);  // O(1) moving window recording

        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException re) {
                log.warn("Rollback failed", re);
            }
            recordError("INSERT_METADATA_FAILED", "Failed to insert metadata",
                       "Error: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Adds H2-specific metrics via Template Method Pattern hook.
     * Called by AbstractDatabaseResource.getMetrics() after base metrics.
     * <p>
     * <strong>Performance:</strong> All metrics use O(1) operations.
     */
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        long now = System.currentTimeMillis();

        // H2 cache metrics (O(1) atomic reads)
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;

        metrics.put("h2_cache_hits", hits);
        metrics.put("h2_cache_misses", misses);
        metrics.put("h2_cache_hit_ratio", total > 0 ? hits / (double) total : 0.0);

        // H2 disk I/O metrics (O(1) atomic reads)
        metrics.put("h2_disk_reads", diskReads.get());
        metrics.put("h2_disk_writes", diskWrites.get());

        // H2 rates (operations per second) - moving window average with O(1) calculation
        metrics.put("h2_cache_hits_per_sec", rateBuckets.get("cache_hits").getRate(now));
        metrics.put("h2_cache_misses_per_sec", rateBuckets.get("cache_misses").getRate(now));
        metrics.put("h2_disk_reads_per_sec", rateBuckets.get("disk_reads").getRate(now));
        metrics.put("h2_disk_writes_per_sec", rateBuckets.get("disk_writes").getRate(now));

        // HikariCP connection pool metrics (O(1) via MXBean)
        metrics.put("h2_pool_active_connections", dataSource.getHikariPoolMXBean().getActiveConnections());
        metrics.put("h2_pool_idle_connections", dataSource.getHikariPoolMXBean().getIdleConnections());
        metrics.put("h2_pool_total_connections", dataSource.getHikariPoolMXBean().getTotalConnections());
        metrics.put("h2_pool_threads_awaiting", dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection());

        // H2 memory usage (can query H2 internal statistics)
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT * FROM INFORMATION_SCHEMA.SETTINGS WHERE NAME = 'info.CACHE_SIZE'")) {

            if (rs.next()) {
                metrics.put("h2_cache_size_bytes", rs.getLong("VALUE"));
            }
        } catch (SQLException e) {
            // Log but don't fail metrics collection
            log.warn("Failed to query H2 statistics: {}", e.getMessage());
        }
    }

    @Override
    public boolean isHealthy() {
        // Check connection pool health
        return dataSource != null &&
               !dataSource.isClosed() &&
               getCurrentState() != State.ERROR;
    }
}
```

## Indexer Architecture

### AbstractIndexer

```java
public abstract class AbstractIndexer extends AbstractService {

    protected final IBatchStorageRead storage;
    protected final Config indexerOptions;

    // Run discovery configuration
    private final String configuredRunId;  // Optional
    private final int pollIntervalMs;
    private final int maxPollDurationMs;
    private final Instant indexerStartTime;

    protected AbstractIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);

        this.storage = getRequiredResource("storage", IBatchStorageRead.class);
        this.indexerOptions = options;

        // Run discovery config
        this.configuredRunId = options.hasPath("runId") ?
            options.getString("runId") : null;
        this.pollIntervalMs = options.hasPath("pollIntervalMs") ?
            options.getInt("pollIntervalMs") : 1000;
        this.maxPollDurationMs = options.hasPath("maxPollDurationMs") ?
            options.getInt("maxPollDurationMs") : 300000;  // 5 minutes
        this.indexerStartTime = Instant.now();
    }

    /**
     * Discovers which simulation run this indexer should process.
     *
     * Mode 1: If runId configured → return it
     * Mode 2: If not configured → poll storage for first run after indexer start time
     *
     * @return The simulation run ID to index
     * @throws InterruptedException if interrupted during polling
     * @throws TimeoutException if no run appears within maxPollDurationMs
     */
    protected String discoverRunId() throws InterruptedException, TimeoutException {
        if (configuredRunId != null) {
            log.info("Using configured runId: {}", configuredRunId);
            return configuredRunId;
        }

        log.info("Discovering runId from storage (timestamp-based, after {})", indexerStartTime);

        long startTime = System.currentTimeMillis();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<String> runIds = storage.listRunIds(indexerStartTime);

                if (!runIds.isEmpty()) {
                    String discoveredRunId = runIds.get(0);
                    log.info("Discovered runId from storage: {}", discoveredRunId);
                    return discoveredRunId;
                }

                // Check timeout
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > maxPollDurationMs) {
                    throw new TimeoutException(
                        "No simulation run appeared within " + maxPollDurationMs + "ms"
                    );
                }

                // Wait before next poll
                Thread.sleep(pollIntervalMs);

            } catch (IOException e) {
                log.warn("Error listing run IDs from storage, retrying: {}", e.getMessage());
                Thread.sleep(pollIntervalMs);
            }
        }

        throw new InterruptedException("Run discovery interrupted");
    }

    /**
     * Template method called after run discovery.
     * Subclasses implement their specific indexing logic here.
     */
    protected abstract void indexRun(String runId) throws Exception;

    @Override
    protected void run() throws InterruptedException {
        try {
            String runId = discoverRunId();
            indexRun(runId);
        } catch (TimeoutException e) {
            log.error("Failed to discover run: {}", e.getMessage());
            throw new RuntimeException(e);
        } catch (Exception e) {
            log.error("Indexing failed", e);
            throw new RuntimeException(e);
        }
    }
}
```

### MetadataIndexer

```java
public class MetadataIndexer extends AbstractIndexer implements IMonitorable {

    private final IMetadataDatabase database;
    private final int metadataFilePollIntervalMs;
    private final int metadataFileMaxPollDurationMs;

    // Metrics
    private final AtomicLong metadataIndexed = new AtomicLong(0);
    private final AtomicLong metadataFailed = new AtomicLong(0);

    public MetadataIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);

        this.database = getRequiredResource("database", IMetadataDatabase.class);

        this.metadataFilePollIntervalMs = options.hasPath("metadataFilePollIntervalMs") ?
            options.getInt("metadataFilePollIntervalMs") : 1000;
        this.metadataFileMaxPollDurationMs = options.hasPath("metadataFileMaxPollDurationMs") ?
            options.getInt("metadataFileMaxPollDurationMs") : 60000;  // 1 minute
    }

    @Override
    protected void indexRun(String runId) throws Exception {
        log.info("Indexing metadata for run: runId={}", runId);

        // Wait for metadata file to exist
        String metadataKey = runId + "/metadata.pb";
        SimulationMetadata metadata = pollForMetadataFile(metadataKey);

        // Use try-with-resources to ensure connection cleanup
        try (IMetadataDatabase db = database) {
            db.createSimulationRun(runId);  // Uses raw runId, sanitizes internally
            db.setSimulationRun(runId);     // Uses raw runId, sanitizes internally
            db.insertMetadata(metadata);
        }

        metadataIndexed.incrementAndGet();
        log.info("Successfully indexed metadata for run: runId={}", runId);

        // Service stops naturally after indexing (one-shot pattern)
    }

    private SimulationMetadata pollForMetadataFile(String key) throws Exception {
        log.debug("Polling for metadata file: key={}", key);

        long startTime = System.currentTimeMillis();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Attempt to read metadata file
                SimulationMetadata metadata = storage.readMessage(key, SimulationMetadata.parser());
                log.info("Found metadata file: {}", key);
                return metadata;

            } catch (IOException e) {
                // File doesn't exist yet or read failed

                // Check timeout
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > metadataFileMaxPollDurationMs) {
                    metadataFailed.incrementAndGet();
                    throw new TimeoutException(
                        "Metadata file did not appear within " +
                        metadataFileMaxPollDurationMs + "ms: " + key
                    );
                }

                // Wait before retry
                Thread.sleep(metadataFilePollIntervalMs);
            }
        }

        throw new InterruptedException("Metadata polling interrupted");
    }

    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
            "metadata_indexed", metadataIndexed.get(),
            "metadata_failed", metadataFailed.get()
        );
    }

    // ... IMonitorable methods
}
```

## Storage Interface Extension

Add to `IBatchStorageRead`:

```java
/**
 * Lists simulation run IDs in storage that started after the given timestamp.
 * <p>
 * Used by indexers for run discovery in parallel mode.
 * <p>
 * Implementation notes:
 * - Returns empty list if no matching runs
 * - Never blocks - returns immediately
 * - Run timestamp can be determined from:
 *   - Parsing runId format (YYYYMMDDHHmmssSS-UUID)
 *   - Directory creation time (filesystem)
 *   - Object metadata (S3/Azure)
 *
 * @param afterTimestamp Only return runs that started after this time
 * @return List of simulation run IDs, sorted by timestamp (oldest first)
 * @throws IOException if storage access fails
 */
List<String> listRunIds(Instant afterTimestamp) throws IOException;
```

## Configuration

### Database Resource

```hocon
mydb {
  className = "org.evochora.datapipeline.resources.database.H2Database"
  options {
    # Option 1: Specify JDBC URL directly
    # jdbcUrl = "jdbc:h2:./data/evochora;MODE=PostgreSQL"
    # jdbcUrl = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"  # In-memory for tests

    # Option 2: Use dataDirectory with variable expansion (like storage resources)
    # Supports ${VAR} syntax for environment variables and system properties
    dataDirectory = "${user.home}/evochora/data"  # Uses Java system property
    # dataDirectory = "${HOME}/evochora/data"      # Uses environment variable
    # dataDirectory = "${EVOCHORA_DATA_DIR}"       # Uses custom env variable

    # HikariCP settings
    maxPoolSize = 10
    minIdle = 2
    connectionTimeout = 30000
    idleTimeout = 600000
    maxLifetime = 1800000

    # Performance metrics window size (moving average)
    # Controls RateBucket window for per-second metrics (default: 5000ms = 5 seconds)
    metricsWindowSizeMs = 5000
  }
}
```

**Variable Expansion Examples:**
- `${user.home}/data` → `/home/user/data` (Java system property)
- `${HOME}/evochora` → `/home/user/evochora` (environment variable)
- `${EVOCHORA_DATA_DIR}` → `/var/lib/evochora` (custom environment variable)
- System properties are checked first, then environment variables

### Metadata Indexer

```hocon
metadata-indexer {
  className = "org.evochora.datapipeline.services.indexers.MetadataIndexer"

  resources {
    storage = "storage-read:tick-storage"
    database = "database-metadata:mydb"
  }

  options {
    # Optional: Specific run to index (post-mortem mode)
    # If not set, uses timestamp-based discovery (parallel mode)
    # runId = "20251006143025-550e8400-e29b-41d4-a716-446655440000"

    # Run discovery polling (parallel mode)
    pollIntervalMs = 1000           # How often to check for new runs
    maxPollDurationMs = 300000      # Timeout: 5 minutes

    # Metadata file polling
    metadataFilePollIntervalMs = 1000
    metadataFileMaxPollDurationMs = 60000
  }
}
```

## Testing Strategy

### Unit Tests

**AbstractIndexerTest:**
- Test run discovery with configured runId
- Test run discovery with timestamp filtering
- Test polling timeout behavior
- Mock storage.listRunIds()

**MetadataIndexerTest:**
- Test metadata file polling
- Test schema creation
- Test metadata insertion
- Mock database and storage

### Integration Tests

**MetadataIndexerIntegrationTest:**
```java
@Test
void testMetadataIndexing_PostMortemMode() {
    // Setup: Write metadata to storage
    SimulationMetadata metadata = createTestMetadata();
    storage.writeMessage("test-run/metadata.pb", metadata);

    // Configure indexer with specific runId
    Config config = ConfigFactory.parseString("""
        runId = "test-run"
        """);

    MetadataIndexer indexer = new MetadataIndexer("test", config, resources);

    // Run indexer
    indexer.start();
    indexer.awaitTermination(5, TimeUnit.SECONDS);

    // Verify: Schema created and metadata indexed
    assertSchemaExists("sim_test_run");
    assertMetadataInDatabase("sim_test_run", metadata);
}

@Test
void testMetadataIndexing_ParallelMode() {
    // Start indexer without runId (timestamp mode)
    MetadataIndexer indexer = startIndexerAsync();

    // Wait briefly
    Thread.sleep(100);

    // Write metadata AFTER indexer started
    SimulationMetadata metadata = createTestMetadata();
    storage.writeMessage("new-run/metadata.pb", metadata);

    // Indexer should discover and index it
    indexer.awaitTermination(10, TimeUnit.SECONDS);

    // Verify
    assertSchemaExists("sim_new_run");
    assertMetadataInDatabase("sim_new_run", metadata);
}
```

## Architectural Decisions

### 1. Database Schema
**Decision:** Hybrid approach - essential fields as key-value pairs + full metadata JSON backup
- Store `environment`, `simulation_info` as structured JSON for query performance
- Store `full_metadata` as complete JSON backup for future needs
- Add more key-value pairs (e.g., `programs`) when web client query patterns emerge
- Use single polymorphic tables with dimension field (not separate tables per dimension)

### 2. Error Handling

**Metadata Indexing Failures: Fail Fast, No DLQ**
- **Rationale**: Metadata is critical dependency for all other indexers. Silent DLQ creates confusing failures.
- **Implementation**: Let exceptions propagate, service enters ERROR state, triggers operational alerts
- **Recovery**: Operator fixes root cause (DB connection, etc.) and restarts indexer with same runId

```java
// MetadataIndexer.indexRun()
try {
    database.insertMetadata(metadata);
    metadataIndexed.incrementAndGet();
} catch (Exception e) {
    metadataFailed.incrementAndGet();
    log.error("Metadata indexing failed for run {}. " +
             "Other indexers cannot proceed.", runId, e);
    throw new RuntimeException("Metadata indexing failed", e);  // ERROR state
}
```

**Database Write Retries: Not Implemented for Metadata**
- **Rationale**: YAGNI - Metadata is single small write, transient failures rare. Adding retry logic to wrapper would require database-specific error detection (violates wrapper purity).
- **Implementation**: No retry logic in wrapper or indexer - let exceptions propagate immediately
- **Future**: If needed for organism indexer (millions of writes), implement retry logic in concrete database class (H2Database.doInsertMetadata), not in wrapper

**Metadata Insert Idempotency: Using MERGE Instead of INSERT**
- **Rationale**: Enables safe restart after partial failure (schema created but insert failed) with minimal complexity
- **Implementation**: H2Database.doInsertMetadata() uses `MERGE INTO metadata (key, value) KEY(key) VALUES (?, ?)` instead of `INSERT INTO metadata (key, value) VALUES (?, ?)`
- **Benefit**: Restart continues from partial state without error - updates existing keys or inserts new ones atomically
- **Cost**: None - simple SQL statement change with same performance characteristics

### 3. Testing Strategy

**Database Cleanup: In-memory H2 with @AfterEach cleanup**
- **Approach**: Each test gets unique in-memory database (`jdbc:h2:mem:test_${UUID}`) that disappears on close
- **Cleanup**: @AfterEach deletes temporary storage directories, closes database connections
- **Isolation**: UUID in database name ensures parallel tests don't interfere
- **No artifacts**: In-memory DB vanishes on JVM exit, temp directories explicitly deleted

**Connection Pool Management in Tests**
- **Strategy**: Use small pool sizes (maxPoolSize=2) appropriate for single-threaded test execution
- **Leak detection**: @AfterEach verifies activeConnections=0 to catch connection leaks
- **No exhaustion tests**: Not testing HikariCP internals, tests use 1-2 connections maximum
- **Fast failure**: connectionTimeout=5000ms makes leaked connections fail tests quickly

**Performance Benchmarks**
- **Decision**: Not implemented (YAGNI)
- **Rationale**: Benchmarks are environment-dependent, testing correctness not performance
- **Future**: Add targeted benchmarks if production performance issues arise

**Test Requirements**
- All tests tagged with `@Tag("unit")` or `@Tag("integration")`
- LogWatchExtension mandatory: Use `@AllowLog` or `@ExpectLog` for every expected log
- No broad ERROR/WARN allowances - only explicitly expected logs
- Tests fail if unexpected errors/warnings occur
- **No Thread.sleep**: Use Awaitility for waiting on dependencies (already in project dependencies)

**Example:**
```java
@ExtendWith(LogWatchExtension.class)
@Tag("integration")
class MetadataIndexerIntegrationTest {

    @BeforeEach
    void setup() {
        testDatabase = new H2Database("test-db", ConfigFactory.parseString("""
            jdbcUrl = "jdbc:h2:mem:test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
            maxPoolSize = 2
            minIdle = 1
            connectionTimeout = 5000
        """));

        Path tempStorage = Files.createTempDirectory("evochora-test-");
        testStorage = new FileSystemStorageResource("test-storage",
            ConfigFactory.parseString("rootDirectory = \"" + tempStorage + "\""));
    }

    @AfterEach
    void cleanup() throws IOException {
        if (testDatabase != null) {
            // Verify no connection leaks
            assertEquals(0, testDatabase.getMetrics().get("h2_pool_active_connections"));
            testDatabase.stop();
        }

        if (testStorage != null) {
            Files.walk(Paths.get(testStorage.getRootDirectory()))
                .sorted(Comparator.reverseOrder())
                .forEach(path -> path.toFile().delete());
        }
    }

    @Test
    @AllowLog(logger = "org.evochora.datapipeline.services.indexers.MetadataIndexer",
              level = INFO,
              message = "Indexing metadata for run: test-run-123")
    @AllowLog(logger = "org.evochora.datapipeline.services.indexers.MetadataIndexer",
              level = INFO,
              message = "Successfully indexed metadata for run: test-run-123")
    void testMetadataIndexing() {
        // Test implementation
    }
}
```

### 4. Logging Strategy

**Log Levels: DEBUG for polling, INFO for results**
- **Polling iterations**: DEBUG (repetitive, too noisy for INFO)
- **Discovery/completion**: INFO (successful discovery, major operations)
- **Example**:
  ```java
  log.debug("Polling for simulation runs");           // DEBUG: Repetitive
  log.info("Discovered runId={}", runId);              // INFO: Success
  log.info("Successfully indexed metadata for run: runId={}", runId);  // INFO: Completion
  ```

**Structured Logging: Simple key=value format**
- Logback automatically provides: logger name, timestamp, thread, level
- Use key=value pairs for structured data (e.g., `runId={}`)
- Always include runId for correlation
- **Example**:
  ```java
  log.info("Using configured runId={}", configuredRunId);
  log.info("Discovered runId={}", runId);
  log.info("Indexing metadata for run: runId={}", runId);
  log.info("Successfully indexed metadata for run: runId={}", runId);
  ```

**Metadata Content: Never logged**
- **Single INFO log** after all metadata inserts complete
- **No logging of**: individual key-value pairs, row counts, dimensions, seed, full protobuf content
- **No performance metrics**: No duration, throughput, or timing information in logs
- **Rationale**: Metadata already persisted to database and storage - logging adds no operational value
- **Example**:
  ```java
  // Single INFO log for entire metadata indexing operation
  log.info("Successfully indexed metadata for run: runId={}", metadata.getSimulationRunId());
  ```

### 5. Code Refactoring

**Path Expansion Utility: Extract to Centralized Utils Class**
- **Problem**: Path expansion logic duplicated in FileSystemStorageResource and H2Database (identical code, ~60 lines each)
- **Solution**: Extract to `org.evochora.datapipeline.utils.PathExpansion` utility class
- **Implementation**:
  ```java
  package org.evochora.datapipeline.utils;

  public class PathExpansion {
      /**
       * Expands environment variables and Java system properties in a path string.
       * Supports syntax: ${VAR} for both environment variables and system properties.
       * System properties are checked first, then environment variables.
       *
       * @param path the path potentially containing variables like ${HOME} or ${user.home}
       * @return the path with all variables expanded
       * @throws IllegalArgumentException if a variable is referenced but not defined
       */
      public static String expandPath(String path) {
          // Implementation moves here from FileSystemStorageResource/H2Database
      }

      private static String resolveVariable(String varName) {
          // Implementation moves here from FileSystemStorageResource/H2Database
      }
  }
  ```
- **Usage**:
  ```java
  // In FileSystemStorageResource and H2Database
  String expandedPath = PathExpansion.expandPath(rootPath);
  ```
- **Benefits**:
  - DRY principle - single source of truth
  - Easier testing - test utility class once
  - Easier maintenance - bug fixes apply everywhere
  - Consistent behavior across storage and database resources
- **Priority**: Medium - implement after Phase 2.4 completion, before adding more resources that need path expansion

## Future Extensions

### Organism Indexer
- Reads batches using storage.listBatchFiles()
- Coordinates work via database (tracks processed batches)
- Multiple instances compete for batches
- Writes to organism-related tables

### Environment Indexer
- Similar competing consumer pattern
- Writes cell states to environment tables
- Depends on metadata for environment.shape

### HTTP API
- Queries indexed data
- Returns JSON for web client
- Uses database connection pooling

---

**Status:** Architecture defined, ready for implementation.
