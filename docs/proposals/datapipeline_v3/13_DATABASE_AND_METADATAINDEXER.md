# Data Pipeline V3 - Database Resource and Metadata Indexer (Phase 2.4)

## Goal

Implement database resource abstraction and metadata indexer service that reads simulation metadata from storage and writes it to a database for query access by the web client HTTP API. This phase establishes the foundation for all indexer services that will process simulation data for analysis and visualization.

## Scope

**This phase implements:**
1. Database resource abstraction with capability-based interfaces
2. H2 database implementation with HikariCP connection pooling
3. AbstractIndexer base class with run discovery logic
4. MetadataIndexer service that indexes SimulationMetadata
5. Schema-per-run database design
6. Integration tests verifying storage → indexer → database flow

**This phase does NOT implement:**
- Organism indexer (future phase)
- Environment indexer (future phase)
- HTTP API for querying indexed data (future phase)
- PostgreSQL implementation (designed for, implemented later)
- Competing consumer coordination for batch indexers (future phase)

## Success Criteria

Upon completion:
1. Database capability interfaces (IMetadataDatabase, IOrganismDatabase, IEnvironmentDatabase) defined in API
2. AbstractDatabaseResource implements connection pooling, wrapper creation, and monitoring
3. H2Database concrete implementation working with in-memory and file-based modes
4. AbstractIndexer implements run discovery (config runId vs. timestamp-based)
5. MetadataIndexer polls for metadata.pb, creates schema, writes to database
6. Schema-per-run design with sanitized schema names (sim_timestamp_uuid)
7. Dedicated connection per indexer instance with setSchema() support
8. Transaction management per batch operation
9. IBatchStorageRead.listRunIds(Instant afterTimestamp) for run discovery
10. Integration tests verify SimulationEngine → Storage → MetadataIndexer → Database flow
11. All tests pass with proper cleanup

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
  - IOrganismDatabase.java        # Organism indexer operations (future)
  - IEnvironmentDatabase.java     # Environment indexer operations (future)

org.evochora.datapipeline.resources.database/
  - AbstractDatabaseResource.java # Base class with pooling, wrappers, monitoring
  - H2Database.java               # H2 implementation
  - DatabaseWrapper.java          # Per-usage-type wrapper with dedicated connection

org.evochora.datapipeline.services.indexers/
  - AbstractIndexer.java          # Base indexer with run discovery
  - MetadataIndexer.java          # Metadata indexing implementation
```

## Database Resource Architecture

### Interface Design (Capability-Based)

```java
public interface IMetadataDatabase extends IResource {
    /**
     * Sets the database schema for all subsequent operations.
     * Must be called once after indexer discovers its runId.
     * Thread-safe: each wrapper has isolated connection.
     */
    void setSchema(String schemaName);

    /**
     * Creates a new schema for a simulation run.
     * Uses CREATE SCHEMA IF NOT EXISTS internally.
     *
     * @param schemaName Sanitized schema name (e.g., sim_20251006143025_uuid)
     */
    void createSchema(String schemaName);

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
}
```

```java
public interface IOrganismDatabase extends IResource {
    void setSchema(String schemaName);

    /**
     * Inserts a batch of organism states.
     * Uses JDBC batch INSERT for performance.
     * Atomic operation per batch.
     */
    void insertOrganismBatch(List<OrganismState> organisms);

    // CREATE TABLE organisms/organism_states when first called
}
```

```java
public interface IEnvironmentDatabase extends IResource {
    void setSchema(String schemaName);

    /**
     * Inserts a batch of cell states.
     * Uses JDBC batch INSERT for performance.
     */
    void insertCellBatch(List<CellState> cells);

    // CREATE TABLE cells when first called
}
```

### AbstractDatabaseResource Implementation

```java
public abstract class AbstractDatabaseResource extends AbstractResource
    implements IMetadataDatabase, IOrganismDatabase, IEnvironmentDatabase,
               IContextualResource, IMonitorable {

    // HikariCP connection pool
    protected final HikariDataSource dataSource;

    // Metrics
    private final AtomicLong queriesExecuted = new AtomicLong(0);
    private final AtomicLong rowsInserted = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);

    protected AbstractDatabaseResource(String name, Config options) {
        super(name, options);

        // Initialize HikariCP with config
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(getJdbcUrl(options));
        config.setMaximumPoolSize(options.getInt("maxPoolSize"));
        config.setConnectionTimeout(options.getLong("connectionTimeout"));
        // ... other HikariCP settings

        this.dataSource = new HikariDataSource(config);
    }

    // Abstract method for subclasses to provide JDBC URL
    protected abstract String getJdbcUrl(Config options);

    @Override
    public IWrappedResource getWrappedResource(ResourceContext context) {
        // Returns DatabaseWrapper with dedicated connection
        return new DatabaseWrapper(this, context);
    }

    // Connection management
    Connection acquireDedicatedConnection() throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);  // Manual commit control
        return conn;
    }

    // Monitoring
    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new HashMap<>();
        metrics.put("queries_executed", queriesExecuted.get());
        metrics.put("rows_inserted", rowsInserted.get());
        metrics.put("errors", errors.get());
        metrics.put("active_connections", dataSource.getHikariPoolMXBean().getActiveConnections());
        metrics.put("idle_connections", dataSource.getHikariPoolMXBean().getIdleConnections());
        return metrics;
    }

    // Utility: Sanitize runId to valid schema name
    protected String toSchemaName(String runId) {
        // 20251006143025-550e8400-e29b-41d4-a716-446655440000
        // → sim_20251006143025_550e8400_e29b_41d4_a716_446655440000
        return "sim_" + runId.replace("-", "_");
    }
}
```

### DatabaseWrapper (Per-Usage-Type)

```java
class DatabaseWrapper implements IMetadataDatabase, IOrganismDatabase, IEnvironmentDatabase {
    private final AbstractDatabaseResource database;
    private final Connection dedicatedConnection;
    private String currentSchema;

    DatabaseWrapper(AbstractDatabaseResource db, ResourceContext context) {
        this.database = db;
        try {
            this.dedicatedConnection = db.acquireDedicatedConnection();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire database connection", e);
        }
    }

    @Override
    public void setSchema(String schemaName) {
        try {
            dedicatedConnection.createStatement()
                .execute("SET SCHEMA " + schemaName);
            this.currentSchema = schemaName;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set schema: " + schemaName, e);
        }
    }

    @Override
    public void createSchema(String schemaName) {
        try {
            dedicatedConnection.createStatement()
                .execute("CREATE SCHEMA IF NOT EXISTS " + schemaName);
            dedicatedConnection.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create schema: " + schemaName, e);
        }
    }

    @Override
    public void insertMetadata(SimulationMetadata metadata) {
        // Delegates to database-specific implementation
        database.doInsertMetadata(dedicatedConnection, metadata);
    }

    // Similar delegation for other operations...

    public void close() {
        try {
            dedicatedConnection.close();  // Returns to pool
        } catch (SQLException e) {
            // Log error
        }
    }
}
```

### H2Database Implementation

```java
public class H2Database extends AbstractDatabaseResource {

    @Override
    protected String getJdbcUrl(Config options) {
        if (options.hasPath("jdbcUrl")) {
            return options.getString("jdbcUrl");
        }
        // Default: file-based H2
        String dataDir = options.getString("dataDirectory");
        return "jdbc:h2:" + dataDir + "/evochora";
    }

    @Override
    public void doInsertMetadata(Connection conn, SimulationMetadata metadata) {
        try {
            // Create metadata table if not exists
            conn.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS metadata (" +
                "  key VARCHAR PRIMARY KEY," +
                "  value JSON" +
                ")"
            );

            // Prepare key-value pairs
            Map<String, String> kvPairs = new HashMap<>();

            // Environment config (needed by environment indexers)
            EnvironmentConfig env = metadata.getEnvironment();
            String envJson = String.format(
                "{\"dimensions\":%d,\"shape\":%s,\"toroidal\":%s}",
                env.getDimensions(),
                Arrays.toString(env.getShapeList().toArray()),
                Arrays.toString(env.getToroidalList().toArray())
            );
            kvPairs.put("environment", envJson);

            // Simulation info (useful for web client)
            String simInfoJson = String.format(
                "{\"runId\":\"%s\",\"startTime\":%d,\"seed\":%d}",
                metadata.getSimulationRunId(),
                metadata.getStartTimeMs(),
                metadata.getInitialSeed()
            );
            kvPairs.put("simulation_info", simInfoJson);

            // Batch insert all key-value pairs
            PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO metadata (key, value) VALUES (?, ?)"
            );

            for (Map.Entry<String, String> entry : kvPairs.entrySet()) {
                stmt.setString(1, entry.getKey());
                stmt.setString(2, entry.getValue());
                stmt.addBatch();
            }

            stmt.executeBatch();
            conn.commit();

            // Track metrics
            rowsInserted.addAndGet(kvPairs.size());
            queriesExecuted.incrementAndGet();

        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException re) {
                // Log rollback failure
            }
            throw new RuntimeException("Failed to insert metadata", e);
        }
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
        log.info("Indexing metadata for run: {}", runId);

        // Wait for metadata file to exist
        String metadataKey = runId + "/metadata.pb";
        SimulationMetadata metadata = pollForMetadataFile(metadataKey);

        // Create schema
        String schemaName = toSchemaName(runId);
        database.createSchema(schemaName);
        database.setSchema(schemaName);

        // Write metadata
        database.insertMetadata(metadata);

        metadataIndexed.incrementAndGet();
        log.info("Successfully indexed metadata for run: {}", runId);

        // Service stops naturally after indexing (one-shot pattern)
    }

    private SimulationMetadata pollForMetadataFile(String key) throws Exception {
        log.debug("Polling for metadata file: {}", key);

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

    private String toSchemaName(String runId) {
        return "sim_" + runId.replace("-", "_");
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
    # JDBC URL - if not specified, uses dataDirectory
    jdbcUrl = "jdbc:h2:./data/evochora;MODE=PostgreSQL"
    # Or: jdbcUrl = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"  # In-memory for tests

    # HikariCP settings
    maxPoolSize = 10
    minIdle = 2
    connectionTimeout = 30000
    idleTimeout = 600000
    maxLifetime = 1800000
  }
}
```

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

## Open Questions

### 1. Database Schema Details
- Should we store full SimulationMetadata or just essential fields initially?
- Programs table structure: Flatten all fields or store as JSONB?
- How to handle dimension-dependent tables (separate tables per dimension count)?

### 2. Error Handling
- Should failed metadata indexing go to DLQ?
- Retry strategy if database write fails?
- What if schema creation succeeds but metadata insert fails?

### 3. Testing
- Test database cleanup strategy (drop schemas after tests)?
- How to test connection pooling exhaustion?
- Performance benchmarks for batch inserts?

### 4. Logging
- Log level for polling iterations (DEBUG vs INFO)?
- Structured logging format for indexer lifecycle events?
- Should we log full metadata or just summary?

### 5. Future PostgreSQL Implementation
- Schema compatibility between H2 and Postgres?
- Migration path from H2 to Postgres?
- Performance tuning differences?

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

**Status:** Architecture defined, ready for implementation discussion of remaining open questions.
