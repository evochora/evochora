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
     * Sets schema if runId was previously set via setSimulationRun().
     * <p>
     * Thread-safe for single-threaded indexer use.
     *
     * @return Database connection (with schema set if cachedRunId is not null)
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

