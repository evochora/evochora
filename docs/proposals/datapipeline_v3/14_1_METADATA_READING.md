# Phase 2.5.1: Metadata Reading Capability

**Part of:** [14_BATCH_COORDINATION_AND_DUMMYINDEXER.md](../delined_or_outdated/datapipeline_v3/14_BATCH_COORDINATION_AND_DUMMYINDEXER.md)

**Status:** Ready for implementation

---

## Goal

Implement metadata reading capability for indexers to access simulation metadata from the database.

Indexers need access to metadata fields (especially `samplingInterval`) for gap detection and other processing logic. This phase establishes the infrastructure for indexers to poll and read metadata from the database (written by MetadataIndexer).

## Scope

**In Scope:**
1. `IMetadataReader` database capability interface
2. `MetadataNotFoundException` checked exception
3. `MetadataReaderWrapper` implementation
4. `AbstractDatabaseResource` extensions for metadata reading
5. `H2Database` implementation of metadata reading methods
6. `MetadataReadingComponent` for metadata polling and caching
7. `DummyIndexer` v1 - reads and logs metadata
8. Unit tests for all components
9. Integration test for DummyIndexer v1

**Out of Scope:**
- Batch coordination (Phase 2.5.2)
- Gap detection (Phase 2.5.4)
- Tick buffering (Phase 2.5.3)
- Batch processing loop (Phase 2.5.5)

## Success Criteria

1. IMetadataReader interface defined with getMetadata() and hasMetadata()
2. MetadataReaderWrapper delegates to AbstractDatabaseResource.doGetMetadata()
3. H2Database reads metadata from `metadata` table (key-value JSON)
4. MetadataReadingComponent polls until metadata available with configurable timeout
5. DummyIndexer v1 successfully reads metadata and logs samplingInterval
6. All tests pass with proper log validation
7. No connection leaks (verified in tests)
8. Metrics tracked with O(1) operations

## Prerequisites

- Phase 2.4: Database Resource and Metadata Indexer (completed)
  - `metadata` table exists in run-specific schemas
  - IMetadataWriter capability implemented
  - MetadataIndexer writes metadata to database
- ISchemaAwareDatabase interface (completed)
- AbstractIndexer schema management (completed)

## Package Structure

```
org.evochora.datapipeline.api.resources.database/
  - IMetadataReader.java               # NEW - Read metadata capability
  - MetadataNotFoundException.java     # NEW - Exception when metadata not found

org.evochora.datapipeline.resources.database/
  - AbstractDatabaseResource.java      # EXTEND - Add doGetMetadata(), doHasMetadata()
  - AbstractDatabaseWrapper.java       # NEW - Base class for all DB wrappers (DRY)
  - H2Database.java                    # EXTEND - Implement metadata reading
  - MetadataReaderWrapper.java         # NEW - IMetadataReader wrapper

org.evochora.datapipeline.services.indexers/
  - DummyIndexer.java                  # NEW - v1: Metadata reading only
  └── components/
      - MetadataReadingComponent.java  # NEW - Metadata polling component
```

## IMetadataReader Interface

```java
package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.IMonitorable;

/**
 * Database capability for reading simulation metadata.
 * <p>
 * Provides read-only access to metadata written by MetadataIndexer.
 * Used by other indexers to access simulation configuration (e.g., samplingInterval for gap detection).
 * <p>
 * Extends {@link ISchemaAwareDatabase} - AbstractIndexer automatically calls
 * {@code setSimulationRun()} after run discovery to set the schema.
 * <p>
 * Implements {@link AutoCloseable} to enable try-with-resources pattern for
 * automatic connection cleanup.
 */
public interface IMetadataReader extends ISchemaAwareDatabase, IMonitorable, AutoCloseable {
    
    /**
     * Retrieves simulation metadata for the current schema.
     * <p>
     * Reads from the metadata table in the active schema (set via setSimulationRun).
     * Parses JSON values and reconstructs SimulationMetadata protobuf.
     *
     * @param simulationRunId The simulation run ID (for validation and error messages)
     * @return The complete simulation metadata
     * @throws MetadataNotFoundException if metadata doesn't exist for this run
     */
    SimulationMetadata getMetadata(String simulationRunId) throws MetadataNotFoundException;
    
    /**
     * Checks if metadata exists for the current schema.
     * <p>
     * Non-blocking check used for polling scenarios.
     *
     * @param simulationRunId The simulation run ID
     * @return true if metadata exists, false otherwise
     */
    boolean hasMetadata(String simulationRunId);
    
    @Override
    void close();
}
```

## MetadataNotFoundException

```java
package org.evochora.datapipeline.api.resources.database;

/**
 * Exception thrown when attempting to read metadata that doesn't exist yet.
 * <p>
 * This is a checked exception representing a normal condition in parallel mode
 * where indexers start before MetadataIndexer has finished. Callers should poll
 * until metadata becomes available.
 */
public class MetadataNotFoundException extends Exception {
    public MetadataNotFoundException(String message) {
        super(message);
    }
}
```

## AbstractDatabaseResource Extensions

Add new abstract methods grouped by capability interface:

```java
// In AbstractDatabaseResource.java

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
```

## AbstractDatabaseWrapper (NEW - Base Class for All Wrappers)

To eliminate code duplication across all database wrappers, we introduce a base class that provides common functionality.

**Motivation:**
- All wrappers need: connection management, schema setting, error tracking, metrics, close()
- Without base class: ~200 lines of duplicated code per wrapper
- With base class: ~60% reduction in wrapper code

```java
package org.evochora.datapipeline.resources.database;

import org.evochora.datapipeline.api.resources.*;
import org.evochora.datapipeline.api.resources.database.ISchemaAwareDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Abstract base class for all database capability wrappers.
 * <p>
 * Provides common functionality to eliminate code duplication:
 * <ul>
 *   <li>Dedicated connection management (acquire, close)</li>
 *   <li>Schema setting (setSimulationRun implementation)</li>
 *   <li>Error tracking and recording</li>
 *   <li>Base metrics infrastructure (Template Method Pattern)</li>
 *   <li>Resource lifecycle (IMonitorable, IWrappedResource, AutoCloseable)</li>
 * </ul>
 * <p>
 * <strong>Subclass Responsibilities:</strong>
 * <ul>
 *   <li>Implement capability-specific methods (e.g., getMetadata, tryClaim)</li>
 *   <li>Override {@link #addCustomMetrics(Map)} to provide capability-specific metrics</li>
 *   <li>Track capability-specific counters and latencies</li>
 * </ul>
 * <p>
 * <strong>Design Pattern:</strong> Template Method Pattern (same as AbstractDatabaseResource)
 */
public abstract class AbstractDatabaseWrapper 
        implements ISchemaAwareDatabase, IWrappedResource, IMonitorable, AutoCloseable {
    
    private static final Logger log = LoggerFactory.getLogger(AbstractDatabaseWrapper.class);
    
    // Core dependencies
    protected final AbstractDatabaseResource database;
    protected final ResourceContext context;
    
    // Connection caching (lazy acquisition, smart release)
    private Object cachedConnection = null;
    private String cachedRunId = null;
    
    // Error tracking (O(1) operations)
    protected final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();
    protected static final int MAX_ERRORS = 100;
    
    // Metrics window config (inherited from database resource)
    protected final int metricsWindowSeconds;
    
    /**
     * Creates database wrapper.
     * <p>
     * Connection is acquired lazily on first use (not in constructor).
     * This reduces connection pool pressure during indexer initialization.
     *
     * @param db Underlying database resource
     * @param context Resource context (service name, usage type)
     */
    protected AbstractDatabaseWrapper(AbstractDatabaseResource db, ResourceContext context) {
        this.database = db;
        this.context = context;
        
        // Get metrics window from database resource config (default: 5 seconds)
        this.metricsWindowSeconds = db.getOptions().hasPath("metricsWindowSeconds")
            ? db.getOptions().getInt("metricsWindowSeconds")
            : 5;
    }
    
    /**
     * Ensures a valid connection is available.
     * <p>
     * Acquires connection lazily on first call and caches it.
     * Sets schema if not already set.
     * <p>
     * Thread-safe for single-threaded indexer use.
     *
     * @return Database connection with schema set
     * @throws RuntimeException if connection acquisition fails
     */
    protected Object ensureConnection() {
        if (cachedConnection == null || isConnectionClosed(cachedConnection)) {
            try {
                cachedConnection = database.acquireDedicatedConnection();
                log.debug("Acquired database connection for service: {}", context.serviceName());
                
                // If schema was previously set, restore it on new connection
                if (cachedRunId != null) {
                    database.doCreateSchema(cachedConnection, cachedRunId);
                    database.doSetSchema(cachedConnection, cachedRunId);
                }
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to acquire database connection for service: " + 
                                         context.serviceName(), e);
            }
        }
        return cachedConnection;
    }
    
    /**
     * Releases the cached connection back to pool.
     * <p>
     * Call this before long idle periods (e.g., before Thread.sleep during polling)
     * to reduce connection pool pressure.
     * <p>
     * Connection will be re-acquired automatically on next operation.
     */
    public void releaseConnection() {
        if (cachedConnection != null && cachedConnection instanceof Connection) {
            try {
                ((Connection) cachedConnection).close();
                log.debug("Released database connection for service: {}", context.serviceName());
            } catch (SQLException e) {
                log.debug("Failed to release connection (may already be closed): {}", e.getMessage());
            } finally {
                cachedConnection = null;
            }
        }
    }
    
    /**
     * Checks if connection is closed.
     */
    private boolean isConnectionClosed(Object conn) {
        if (conn instanceof Connection) {
            try {
                return ((Connection) conn).isClosed();
            } catch (SQLException e) {
                return true; // Assume closed on error
            }
        }
        return true;
    }
    
    // ========== ISchemaAwareDatabase Implementation (DRY!) ==========
    
    @Override
    public void setSimulationRun(String simulationRunId) {
        try {
            // Step 1: Ensure connection exists (without schema - cachedRunId still null)
            Object conn = ensureConnection();
            
            // Step 2: Create schema if not exists (idempotent, prevents race conditions)
            database.doCreateSchema(conn, simulationRunId);
            
            // Step 3: Set schema on connection
            database.doSetSchema(conn, simulationRunId);
            
            // Step 4: Cache runId for future connection re-acquisition
            this.cachedRunId = simulationRunId;
            
        } catch (Exception e) {
            recordError("SET_SCHEMA_FAILED", "Failed to set simulation run", 
                       "RunId: " + simulationRunId + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to set schema for run-id: " + simulationRunId, e);
        }
    }
    
    // ========== Error Handling (DRY!) ==========
    
    /**
     * Records an operational error.
     * <p>
     * Maintains a bounded queue of recent errors (max 100).
     * O(1) operation.
     *
     * @param code Error code (e.g., "GET_METADATA_FAILED")
     * @param message Human-readable error message
     * @param details Additional context (runId, parameters, etc.)
     */
    protected void recordError(String code, String message, String details) {
        errors.add(new OperationalError(Instant.now(), code, message, details));
        
        // Bounded queue - remove oldest if exceeds limit
        while (errors.size() > MAX_ERRORS) {
            errors.pollFirst();
        }
    }
    
    // ========== IMonitorable Implementation (Template Method Pattern) ==========
    
    /**
     * Returns all metrics for this wrapper.
     * <p>
     * Uses Template Method Pattern (same as AbstractDatabaseResource):
     * <ol>
     *   <li>Collects base metrics (error_count)</li>
     *   <li>Calls {@link #addCustomMetrics(Map)} hook for subclass-specific metrics</li>
     * </ol>
     * <p>
     * Subclasses should NOT override this method. Use {@link #addCustomMetrics(Map)} instead.
     */
    @Override
    public final Map<String, Number> getMetrics() {
        Map<String, Number> metrics = getBaseMetrics();
        addCustomMetrics(metrics);
        return metrics;
    }
    
    /**
     * Returns base metrics tracked by all database wrappers.
     * <p>
     * Private helper method called only by getMetrics().
     * Subclasses should not access this directly - use addCustomMetrics() hook instead.
     *
     * @return Map containing base metrics (O(1) operations)
     */
    private Map<String, Number> getBaseMetrics() {
        Map<String, Number> metrics = new LinkedHashMap<>();
        metrics.put("error_count", errors.size());  // O(1)
        metrics.put("connection_cached", cachedConnection != null ? 1 : 0);  // O(1)
        return metrics;
    }
    
    /**
     * Hook for subclasses to add implementation-specific metrics.
     * <p>
     * Example:
     * <pre>
     * &#64;Override
     * protected void addCustomMetrics(Map&lt;String, Number&gt; metrics) {
     *     metrics.put("metadata_reads", metadataReads.get());
     *     metrics.put("metadata_not_found", metadataNotFound.get());
     *     metrics.put("get_metadata_latency_p95_ms", getMetadataLatency.getPercentile(95) / 1_000_000.0);
     * }
     * </pre>
     * <p>
     * All metric recording operations MUST be O(1). Use:
     * <ul>
     *   <li>{@link java.util.concurrent.atomic.AtomicLong} for counters</li>
     *   <li>{@link org.evochora.datapipeline.utils.monitoring.SlidingWindowPercentiles} for latencies</li>
     * </ul>
     *
     * @param metrics Mutable map to add custom metrics to (already contains base metrics)
     */
    protected void addCustomMetrics(Map<String, Number> metrics) {
        // Default: no custom metrics
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
    
    // ========== IWrappedResource Implementation (DRY!) ==========
    
    @Override
    public String getResourceName() {
        return database.getResourceName();
    }
    
    @Override
    public IResource.UsageState getUsageState(String usageType) {
        return database.getUsageState(usageType);
    }
    
    // ========== AutoCloseable Implementation (DRY!) ==========
    
    @Override
    public void close() {
        releaseConnection();
    }
}
```

**Benefits:**
- ✅ **Code Reduction:** Wrappers go from ~200 to ~80 lines (-60%)
- ✅ **Consistency:** All wrappers behave identically for common operations
- ✅ **DRY:** Schema setting, error handling, metrics pattern implemented once
- ✅ **Template Method Pattern:** Same as AbstractDatabaseResource (familiar)
- ✅ **Type Safety:** Final methods prevent accidental overrides
- ✅ **Connection Pooling Optimization:** Lazy acquisition + smart release reduces pool pressure
  - Connections acquired only when needed
  - Released during idle periods (polling)
  - 10× fewer connections needed (10-20 pool size for 100+ indexers)

## H2Database Implementation

H2Database must implement all new abstract methods for the IMetadataReader capability:

```java
// In H2Database.java

// ========================================================================
// IMetadataReader Capability
// ========================================================================

/**
 * Implements {@link IMetadataReader#getMetadata(String)}.
 * Queries metadata table in current schema and deserializes from JSON.
 */
@Override
protected SimulationMetadata doGetMetadata(Object connection, String simulationRunId) throws Exception {
    Connection conn = (Connection) connection;
    
    // Query metadata table (already in correct schema via setSimulationRun)
    PreparedStatement stmt = conn.prepareStatement(
        "SELECT value FROM metadata WHERE key = ?"
    );
    stmt.setString(1, "full_metadata");
    ResultSet rs = stmt.executeQuery();
    
    queriesExecuted.incrementAndGet();
    
    if (!rs.next()) {
        throw new MetadataNotFoundException("Metadata not found for run: " + simulationRunId);
    }
    
    String json = rs.getString("value");
    SimulationMetadata metadata = ProtobufConverter.fromJson(json, SimulationMetadata.class);
    
    return metadata;
}

/**
 * Implements {@link IMetadataReader#hasMetadata(String)}.
 * Checks if metadata exists via COUNT query.
 */
@Override
protected boolean doHasMetadata(Object connection, String simulationRunId) throws Exception {
    Connection conn = (Connection) connection;
    
    PreparedStatement stmt = conn.prepareStatement(
        "SELECT COUNT(*) as cnt FROM metadata WHERE key = ?"
    );
    stmt.setString(1, "full_metadata");
    ResultSet rs = stmt.executeQuery();
    
    queriesExecuted.incrementAndGet();
    
    return rs.next() && rs.getInt("cnt") > 0;
}

// NEW: ulimit and resource tracking (Option C)
@Override
protected void addCustomMetrics(Map<String, Number> metrics) {
    // Existing HikariCP metrics
    if (dataSource != null && !dataSource.isClosed()) {
        metrics.put("h2_pool_active_connections", dataSource.getHikariPoolMXBean().getActiveConnections());
        metrics.put("h2_pool_idle_connections", dataSource.getHikariPoolMXBean().getIdleConnections());
        metrics.put("h2_pool_total_connections", dataSource.getHikariPoolMXBean().getTotalConnections());
        metrics.put("h2_pool_threads_awaiting", dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection());
    }
    
    // Operating system resource limits (O(1) via MXBean)
    OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
    
    if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
        long openFDs = unix.getOpenFileDescriptorCount();
        long maxFDs = unix.getMaxFileDescriptorCount();
        
        metrics.put("os_open_file_descriptors", openFDs);
        metrics.put("os_max_file_descriptors", maxFDs);
        metrics.put("os_fd_usage_percent", (openFDs * 100.0) / maxFDs);
    }
    
    // JVM thread metrics (O(1) via MXBean)
    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    metrics.put("jvm_thread_count", threads.getThreadCount());
    metrics.put("jvm_daemon_thread_count", threads.getDaemonThreadCount());
    metrics.put("jvm_peak_thread_count", threads.getPeakThreadCount());
}
```

## MetadataReaderWrapper

```java
package org.evochora.datapipeline.resources.database;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.*;
import org.evochora.datapipeline.api.resources.database.IMetadataReader;
import org.evochora.datapipeline.api.resources.database.MetadataNotFoundException;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowPercentiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Database-agnostic wrapper for metadata reading operations.
 * <p>
 * Extends {@link AbstractDatabaseWrapper} to inherit common functionality:
 * connection management, schema setting, error tracking, metrics infrastructure.
 * <p>
 * <strong>Performance Contract:</strong> All metrics use O(1) recording.
 */
class MetadataReaderWrapper extends AbstractDatabaseWrapper implements IMetadataReader {
    private static final Logger log = LoggerFactory.getLogger(MetadataReaderWrapper.class);
    
    // Metrics (O(1) atomic operations)
    private final AtomicLong metadataReads = new AtomicLong(0);
    private final AtomicLong metadataNotFound = new AtomicLong(0);
    private final AtomicLong readErrors = new AtomicLong(0);
    
    // Latency tracking (O(1) recording)
    private final SlidingWindowPercentiles getMetadataLatency;
    private final SlidingWindowPercentiles hasMetadataLatency;
    
    MetadataReaderWrapper(AbstractDatabaseResource db, ResourceContext context) {
        super(db, context);  // Parent handles connection, error tracking, metrics window
        
        this.getMetadataLatency = new SlidingWindowPercentiles(metricsWindowSeconds);
        this.hasMetadataLatency = new SlidingWindowPercentiles(metricsWindowSeconds);
    }
    
    @Override
    public SimulationMetadata getMetadata(String simulationRunId) throws MetadataNotFoundException {
        long startNanos = System.nanoTime();
        
        try {
            SimulationMetadata metadata = database.doGetMetadata(ensureConnection(), simulationRunId);
            metadataReads.incrementAndGet();
            getMetadataLatency.record(System.nanoTime() - startNanos);
            return metadata;
            
        } catch (MetadataNotFoundException e) {
            metadataNotFound.incrementAndGet();
            getMetadataLatency.record(System.nanoTime() - startNanos);
            throw e;
            
        } catch (Exception e) {
            readErrors.incrementAndGet();
            recordError("GET_METADATA_FAILED", "Failed to read metadata",
                       "RunId: " + simulationRunId + ", Error: " + e.getMessage());
            throw new RuntimeException("Failed to read metadata: " + simulationRunId, e);
        }
    }
    
    @Override
    public boolean hasMetadata(String simulationRunId) {
        long startNanos = System.nanoTime();
        
        try {
            boolean exists = database.doHasMetadata(ensureConnection(), simulationRunId);
            hasMetadataLatency.record(System.nanoTime() - startNanos);
            return exists;
            
        } catch (Exception e) {
            readErrors.incrementAndGet();
            recordError("HAS_METADATA_FAILED", "Failed to check metadata existence",
                       "RunId: " + simulationRunId + ", Error: " + e.getMessage());
            return false; // Assume not present on error
        }
    }
    
    // Note: close(), isHealthy(), getErrors(), clearErrors(), getResourceName(), 
    // getUsageState(), releaseConnection() are inherited from AbstractDatabaseWrapper
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        // Counters - O(1)
        metrics.put("metadata_reads", metadataReads.get());
        metrics.put("metadata_not_found", metadataNotFound.get());
        metrics.put("read_errors", readErrors.get());
        
        // Latency percentiles in milliseconds - O(windowSeconds × buckets) = O(constant)
        metrics.put("get_metadata_latency_p50_ms", getMetadataLatency.getPercentile(50) / 1_000_000.0);
        metrics.put("get_metadata_latency_p95_ms", getMetadataLatency.getPercentile(95) / 1_000_000.0);
        metrics.put("get_metadata_latency_p99_ms", getMetadataLatency.getPercentile(99) / 1_000_000.0);
        metrics.put("get_metadata_latency_avg_ms", getMetadataLatency.getAverage() / 1_000_000.0);
        
        metrics.put("has_metadata_latency_p95_ms", hasMetadataLatency.getPercentile(95) / 1_000_000.0);
    }
}
```

## MetadataReadingComponent

```java
package org.evochora.datapipeline.services.indexers.components;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.database.IMetadataReader;
import org.evochora.datapipeline.api.resources.database.MetadataNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeoutException;

/**
 * Component for reading and caching simulation metadata from the database.
 * <p>
 * Provides metadata polling (blocks until available) and caching for efficient access
 * to metadata fields like samplingInterval.
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Should be used by single indexer instance only.
 */
public class MetadataReadingComponent {
    private static final Logger log = LoggerFactory.getLogger(MetadataReadingComponent.class);
    
    private final IMetadataReader metadataReader;
    private final int pollIntervalMs;
    private final int maxPollDurationMs;
    
    private SimulationMetadata metadata;
    
    /**
     * Creates metadata reading component.
     *
     * @param metadataReader Database capability for reading metadata
     * @param pollIntervalMs Interval between polling attempts (milliseconds)
     * @param maxPollDurationMs Maximum time to wait for metadata (milliseconds)
     */
    public MetadataReadingComponent(IMetadataReader metadataReader, 
                                   int pollIntervalMs, 
                                   int maxPollDurationMs) {
        this.metadataReader = metadataReader;
        this.pollIntervalMs = pollIntervalMs;
        this.maxPollDurationMs = maxPollDurationMs;
    }
    
    /**
     * Polls for metadata until available or timeout.
     * <p>
     * Blocks until metadata is available in the database. Used by indexers to wait
     * for MetadataIndexer to complete before starting batch processing.
     *
     * @param runId Simulation run ID
     * @throws InterruptedException if interrupted while polling
     * @throws TimeoutException if metadata not found within maxPollDurationMs
     */
    public void loadMetadata(String runId) throws InterruptedException, TimeoutException {
        log.debug("Polling for metadata: runId={}", runId);
        
        long startTime = System.currentTimeMillis();
        
        while (!Thread.currentThread().isInterrupted()) {
            try {
                this.metadata = metadataReader.getMetadata(runId);
                return;
                
            } catch (MetadataNotFoundException e) {
                // Expected - metadata not yet indexed
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > maxPollDurationMs) {
                    throw new TimeoutException(
                        "Metadata not indexed within " + maxPollDurationMs + "ms for run: " + runId
                    );
                }
                
                // Release connection before idle period (reduces pool pressure)
                metadataReader.releaseConnection();
                Thread.sleep(pollIntervalMs);
            }
        }
        
        throw new InterruptedException("Metadata polling interrupted");
    }
    
    /**
     * Gets the sampling interval from cached metadata.
     *
     * @return Sampling interval
     * @throws IllegalStateException if metadata not loaded yet
     */
    public int getSamplingInterval() {
        if (metadata == null) {
            throw new IllegalStateException("Metadata not loaded - call loadMetadata() first");
        }
        return metadata.getSamplingInterval();
    }
    
    /**
     * Gets the complete cached metadata.
     *
     * @return Simulation metadata
     * @throws IllegalStateException if metadata not loaded yet
     */
    public SimulationMetadata getMetadata() {
        if (metadata == null) {
            throw new IllegalStateException("Metadata not loaded - call loadMetadata() first");
        }
        return metadata;
    }
}
```

**Note:** Connection release during polling (before `Thread.sleep`) happens internally in `loadMetadata()`. Final cleanup is handled automatically via try-with-resources in DummyIndexer's `indexRun()` method.

## DummyIndexer v1 Implementation

```java
package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.database.IMetadataReader;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.services.indexers.components.MetadataReadingComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test indexer for validating metadata reading infrastructure.
 * <p>
 * <strong>Phase 2.5.1 Scope:</strong>
 * <ul>
 *   <li>Discovers simulation run</li>
 *   <li>Waits for metadata to be indexed (polls database)</li>
 *   <li>Reads and logs metadata (especially samplingInterval)</li>
 *   <li>Does NOT process batches (added in Phase 2.5.2)</li>
 * </ul>
 * <p>
 * <strong>Purpose:</strong> Validate metadata reading capability before adding
 * batch coordination and processing.
 */
public class DummyIndexer extends AbstractIndexer {
    private static final Logger log = LoggerFactory.getLogger(DummyIndexer.class);
    
    private final IMetadataReader metadataReader;
    private final MetadataReadingComponent metadataComponent;
    private final AtomicLong runsProcessed = new AtomicLong(0);
    
    public DummyIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        
        // Setup metadata reader and component
        this.metadataReader = getRequiredResource("metadata", IMetadataReader.class);
        int pollIntervalMs = options.hasPath("pollIntervalMs") ? options.getInt("pollIntervalMs") : 1000;
        int maxPollDurationMs = options.hasPath("maxPollDurationMs") ? options.getInt("maxPollDurationMs") : 300000;
        
        this.metadataComponent = new MetadataReadingComponent(metadataReader, pollIntervalMs, maxPollDurationMs);
    }
    
    @Override
    protected void indexRun(String runId) throws Exception {
        // Use try-with-resources for automatic connection cleanup
        try (IMetadataReader reader = metadataReader) {
            log.info("Starting metadata reading for run: {}", runId);
            
            // Load metadata (polls until available)
            metadataComponent.loadMetadata(runId);
            
            runsProcessed.incrementAndGet();
            
            log.info("Successfully read metadata for run: {}", runId);
            
            // Phase 2.5.1: Stop after metadata (no batch processing yet)
        }  // AutoCloseable.close() releases connection
    }
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        metrics.put("runs_processed", runsProcessed.get());
    }
}
```

## Configuration

**Note:** Database resource `index-database` is already configured in `evochora.conf` - no changes needed.

### DummyIndexer v1

```hocon
dummy-indexer {
  className = "org.evochora.datapipeline.services.indexers.DummyIndexer"
  
  resources {
    storage = "storage-read:tick-storage"
    metadata = "db-meta-read:index-database"  # NEW in Phase 2.5.1
  }
  
  options {
    # Inherits from central services.runId (if set)
    # If services.runId not set → automatic discovery from storage
    # Can be overridden here for indexer-specific post-mortem mode
    runId = ${?pipeline.services.runId}
    
    # Run discovery and metadata polling
    pollIntervalMs = 1000
    maxPollDurationMs = 300000  # 5 minutes
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
  - Example: `@ExpectLog(logger = "...", level = ERROR, message = "Failed to read metadata for run: *")`
  - **NEVER** use broad patterns like `message = "*"`
- **Never use `Thread.sleep`** - use Awaitility `await().atMost(...).until(...)` instead
- Leave no artifacts (in-memory H2, temp directories cleaned in `@AfterEach`)
- Use UUID-based database names for parallel test execution
- Verify no connection leaks in `@AfterEach`

### Unit Tests

**MetadataReaderWrapperTest:**
```java
@ExtendWith(LogWatchExtension.class)
@Tag("unit")
class MetadataReaderWrapperTest {
    
    @Test
    void getMetadata_success() {
        // Mock doGetMetadata to return test metadata
        // Verify wrapper delegates correctly
        // Verify metrics updated
    }
    
    @Test
    void getMetadata_notFound_throwsException() {
        // Mock doGetMetadata to throw exception
        // Verify MetadataNotFoundException propagated
        // Verify metadataNotFound counter incremented
    }
    
    @Test
    void hasMetadata_returnsTrue() {
        // Mock doHasMetadata to return true
        // Verify correct delegation
    }
    
    @Test
    void hasMetadata_returnsFalse() {
        // Mock doHasMetadata to return false
    }
    
    @Test
    void metrics_allO1Operations() {
        // Verify all metric operations are O(1)
        // Verify sliding window percentiles available
    }
}
```

**MetadataReadingComponentTest:**
```java
@ExtendWith(LogWatchExtension.class)
@Tag("unit")
class MetadataReadingComponentTest {
    
    @Test
    void loadMetadata_success() {
        // Mock metadataReader.getMetadata() to return metadata
        // Call loadMetadata()
        // Verify metadata cached
        // Verify getSamplingInterval() works
    }
    
    @Test
    void loadMetadata_pollsUntilAvailable() {
        // Mock first 3 calls throw MetadataNotFoundException
        // 4th call succeeds
        // Verify polling behavior
        // Verify correct number of attempts
    }
    
    @Test
    void loadMetadata_timeout() {
        // Mock always throws MetadataNotFoundException
        // Verify TimeoutException after maxPollDurationMs
    }
    
    @Test
    void getSamplingInterval_beforeLoad_throwsException() {
        // Call getSamplingInterval() before loadMetadata()
        // Verify IllegalStateException
    }
}
```

### Integration Tests

**DummyIndexerV1IntegrationTest:**
```java
@ExtendWith(LogWatchExtension.class)
@Tag("integration")
class DummyIndexerV1IntegrationTest {
    
    private H2Database testDatabase;
    private FileSystemStorageResource testStorage;
    private Path tempDir;
    
    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("dummy-indexer-v1-test");
        
        // Setup H2 database
        Config dbConfig = ConfigFactory.parseString("""
            dataDirectory = "%s/db"
            """.formatted(tempDir));
        testDatabase = new H2Database("test-db", dbConfig);
        testDatabase.start();
        
        // Setup storage
        Config storageConfig = ConfigFactory.parseString("""
            rootDirectory = "%s/storage"
            """.formatted(tempDir));
        testStorage = new FileSystemStorageResource("test-storage", storageConfig);
        testStorage.start();
    }
    
    @AfterEach
    void cleanup() throws Exception {
        if (testDatabase != null) {
            // Verify no connection leaks
            assertEquals(0, testDatabase.getMetrics().get("h2_pool_active_connections"),
                        "Connection leak detected");
            testDatabase.stop();
        }
        
        if (testStorage != null) {
            testStorage.stop();
        }
        
        if (tempDir != null) {
            Files.walk(tempDir)
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
    void testMetadataReading_Success() throws Exception {
        // Create test run with metadata
        String runId = "test-run-" + UUID.randomUUID();
        createTestMetadata(runId, samplingInterval = 10);
        
        // Configure DummyIndexer
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            pollIntervalMs = 100
            maxPollDurationMs = 5000
            """.formatted(runId));
        
        DummyIndexer indexer = new DummyIndexer("test-indexer", config,
            Map.of(
                "storage", List.of(testStorage),
                "metadata", List.of(testDatabase)
            ));
        
        // Start indexer
        indexer.start();
        
        // Wait for completion (should stop after reading metadata)
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.STOPPED);
        
        // Verify metrics
        assertEquals(1, indexer.getMetrics().get("runs_processed").intValue());
    }
    
    @Test
    void testMetadataReading_PollingBehavior() throws Exception {
        String runId = "test-run-" + UUID.randomUUID();
        
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            pollIntervalMs = 100
            maxPollDurationMs = 5000
            """.formatted(runId));
        
        DummyIndexer indexer = new DummyIndexer("test-indexer", config,
            Map.of(
                "storage", List.of(testStorage),
                "metadata", List.of(testDatabase)
            ));
        
        // Start indexer (metadata doesn't exist yet)
        indexer.start();
        
        // Wait until polling started (RUNNING state)
        await().atMost(2, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);
        
        // Now create metadata
        createTestMetadata(runId, samplingInterval = 10);
        
        // Should complete now
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.STOPPED);
        
        assertEquals(1, indexer.getMetrics().get("runs_processed").intValue());
    }
    
    @Test
    @ExpectLog(logger = "org.evochora.datapipeline.services.indexers.DummyIndexer",
               level = ERROR, message = "Indexing failed for run: *")
    void testMetadataReading_Timeout() throws Exception {
        String runId = "test-run-" + UUID.randomUUID();
        
        // Configure short timeout
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            pollIntervalMs = 100
            maxPollDurationMs = 1000
            """.formatted(runId));
        
        DummyIndexer indexer = new DummyIndexer("test-indexer", config,
            Map.of(
                "storage", List.of(testStorage),
                "metadata", List.of(testDatabase)
            ));
        
        indexer.start();
        
        // Should timeout and enter ERROR state (metadata never created)
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.ERROR);
    }
    
    // Helper methods
    
    private void createTestMetadata(String runId, int samplingInterval) throws Exception {
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId(runId)
            .setSamplingInterval(samplingInterval)
            .setInitialSeed(12345L)
            .setStartTimeMs(System.currentTimeMillis())
            .setEnvironment(/* ... */)
            .build();
        
        // Use MetadataIndexer to write metadata (or direct DB access for test)
        try (IMetadataWriter writer = testDatabase.getWrappedResource(
                new ResourceContext("test", "db", "db-meta-write", "test-db", Map.of()))) {
            writer.setSimulationRun(runId);
            writer.createSimulationRun(runId);
            writer.insertMetadata(metadata);
        }
    }
}
```

## Logging Strategy

### Principles

**❌ NEVER:**
- Log in loops with INFO (too noisy - use DEBUG)
- Multi-line log statements
- Phase/version prefixes in production code
- Redundant information already in metrics

**✅ ALWAYS:**
- INFO very sparingly (start/stop, critical events only)
- DEBUG for repetitive operations
- Single line per event
- Include essential context (runId, key values)

### Log Levels

**INFO:**
- Service lifecycle only (start/stop)
- Critical failures

**DEBUG:**
- Metadata loaded/available
- Polling status
- Component state

**WARN:**
- Not used in Phase 2.5.1

**ERROR:**
- Timeout exceptions
- Read failures

### Examples

```java
// ✅ GOOD - DEBUG for repetitive operations
log.debug("Metadata available: runId={}, samplingInterval={}, seed={}", 
         runId, samplingInterval, seed);

// ✅ GOOD - Single line, essential context
log.debug("Waiting for metadata: runId={}", runId);

// ❌ BAD - Multi-line
log.debug("Metadata loaded successfully:");
log.debug("  - samplingInterval: {}", interval);

// ❌ BAD - Phase prefix
log.info("Starting metadata reading");

// ❌ BAD - INFO in potentially repeated operation
log.info("Metadata loaded for runId={}", runId);
```

## Monitoring Requirements

### Performance Contract: O(1) Metric Recording

All monitoring operations MUST use O(1) data structures from `org.evochora.datapipeline.utils.monitoring`:
- `AtomicLong` for counters
- `SlidingWindowPercentiles` for latency tracking

### MetadataReaderWrapper Metrics

**Required Metrics (all O(1) or O(constant)):**
```java
// Counters - O(1)
metrics.put("metadata_reads", metadataReads.get());
metrics.put("metadata_not_found", metadataNotFound.get());
metrics.put("read_errors", readErrors.get());
metrics.put("error_count", errors.size());
metrics.put("connection_cached", cachedConnection != null ? 1 : 0);  // NEW - Connection state

// Latency percentiles - O(windowSeconds × buckets) = O(5 × 11) = O(55) = O(constant)
metrics.put("get_metadata_latency_p50_ms", getMetadataLatency.getPercentile(50) / 1_000_000.0);
metrics.put("get_metadata_latency_p95_ms", getMetadataLatency.getPercentile(95) / 1_000_000.0);
metrics.put("get_metadata_latency_p99_ms", getMetadataLatency.getPercentile(99) / 1_000_000.0);
metrics.put("get_metadata_latency_avg_ms", getMetadataLatency.getAverage() / 1_000_000.0);
```

**H2Database Metrics (NEW ulimit tracking):**
```java
// HikariCP pool metrics - O(1)
metrics.put("h2_pool_active_connections", ...);
metrics.put("h2_pool_idle_connections", ...);
metrics.put("h2_pool_total_connections", ...);

// Operating system resource limits - O(1) via MXBean
metrics.put("os_open_file_descriptors", ...);
metrics.put("os_max_file_descriptors", ...);
metrics.put("os_fd_usage_percent", ...);

// JVM thread metrics - O(1) via MXBean
metrics.put("jvm_thread_count", ...);
metrics.put("jvm_daemon_thread_count", ...);
metrics.put("jvm_peak_thread_count", ...);
```

### DummyIndexer v1 Metrics

```java
metrics.put("runs_processed", runsProcessed.get());  // O(1)
```

### Monitoring Anti-Patterns (FORBIDDEN)

DO NOT:
- Iterate collections to compute metrics
- Use synchronized blocks for metrics
- Perform database queries in getMetrics()
- Create new objects in getMetrics() (except result Map)

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
  - Example: `/** Implements {@link IMetadataReader#getMetadata(String)}. */`

**Example:**
```java
/**
 * Component for reading and caching simulation metadata from the database.
 * <p>
 * Provides metadata polling (blocks until available) and caching for efficient access
 * to metadata fields like samplingInterval.
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Should be used by single indexer instance only.
 */
public class MetadataReadingComponent {
    /**
     * Polls for metadata until available or timeout.
     * <p>
     * Blocks until metadata is available in the database. Used by indexers to wait
     * for MetadataIndexer to complete before starting batch processing.
     *
     * @param runId Simulation run ID
     * @throws InterruptedException if interrupted while polling
     * @throws TimeoutException if metadata not found within maxPollDurationMs
     */
    public void loadMetadata(String runId) throws InterruptedException, TimeoutException {
        // ...
    }
}
```

## Implementation Checklist

- [ ] Create `IMetadataReader` interface
- [ ] Create `MetadataNotFoundException` exception
- [ ] Extend `AbstractDatabaseResource` with doGetMetadata(), doHasMetadata()
- [ ] Implement methods in `H2Database`
- [ ] **Create `AbstractDatabaseWrapper` base class**
- [ ] Update `AbstractDatabaseResource.getWrappedResource()` switch case for "db-meta-read"
- [ ] Create `MetadataReaderWrapper` (extends AbstractDatabaseWrapper)
- [ ] Create `MetadataReadingComponent`
- [ ] Create `DummyIndexer` v1
- [ ] Write unit tests for AbstractDatabaseWrapper (if needed)
- [ ] Write unit tests for MetadataReaderWrapper
- [ ] Write unit tests for MetadataReadingComponent
- [ ] Write integration test for DummyIndexer v1
- [ ] Verify all tests pass
- [ ] Verify no connection leaks
- [ ] Verify JavaDoc complete
- [ ] Verify logging follows strategy

---

**Next Phase:** [14_2_BATCH_COORDINATION.md](./14_2_BATCH_COORDINATION.md)


