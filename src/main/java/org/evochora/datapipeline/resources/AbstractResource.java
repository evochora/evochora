package org.evochora.datapipeline.resources;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Abstract base class for all IResource implementations, providing common
 * functionality for name and configuration handling, and monitoring infrastructure.
 * <p>
 * This class implements {@link IMonitorable} to provide consistent error tracking
 * and metrics across all resources, following the same patterns as {@link org.evochora.datapipeline.services.AbstractService}.
 */
public abstract class AbstractResource implements IResource, IMonitorable {
    protected final String resourceName;
    protected final Config options;
    
    /**
     * Collection of operational errors that occurred during resource operations.
     * These are transient errors that don't prevent the resource from functioning
     * but may indicate problems.
     * <p>
     * Private to enforce use of {@link #recordError(String, String, String)} method.
     * Subclasses must not access this directly - use protected methods like
     * {@link #clearErrorsIf(java.util.function.Predicate)} when needed.
     */
    private final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();
    
    /**
     * Maximum number of errors to keep in memory. When exceeded, oldest errors are removed.
     * This prevents OOM in long-running resources with frequent errors.
     * Subclasses can override this value if needed.
     */
    protected int getMaxErrors() {
        return 10000;
    }

    /**
     * Constructor for AbstractResource.
     *
     * @param name    The unique name of the resource instance from the configuration.
     * @param options The configuration object for this resource instance.
     */
    protected AbstractResource(String name, Config options) {
        this.resourceName = Objects.requireNonNull(name, "Resource name cannot be null");
        this.options = Objects.requireNonNull(options, "Resource options cannot be null");
    }

    @Override
    public String getResourceName() {
        return resourceName;
    }

    /**
     * Returns the configuration object for this resource.
     *
     * @return The configuration object.
     */
    public Config getOptions() {
        return options;
    }
    
    /**
     * Records an operational error for tracking and monitoring.
     * <p>
     * <strong>IMPORTANT:</strong> Use this method ONLY for transient errors where the resource
     * continues functioning. For fatal errors that prevent operation, log and throw an exception instead.
     * <p>
     * Use this method to track transient errors that don't prevent the resource from functioning
     * but may indicate problems. These errors affect the resource's health status ({@link #isHealthy()}).
     * <p>
     * The error collection is bounded by {@link #getMaxErrors()} to prevent unbounded
     * memory growth. When the limit is exceeded, the oldest errors are automatically removed.
     * <p>
     * <strong>Error Handling Guidelines for Resources:</strong>
     * <p>
     * <strong>1. Transient Errors</strong> (resource continues functioning):
     * <ul>
     *   <li>Use: {@code log.warn("message", args)} - NO exception parameter</li>
     *   <li>Use: {@link #recordError(String, String, String)} to track</li>
     *   <li>May throw exception if caller needs to handle it (e.g., SQLException in database)</li>
     *   <li>Example: SQL constraint violation, dropped DLQ message, temporary connection issue</li>
     * </ul>
     * 
     * <strong>2. Fatal Errors</strong> (resource cannot continue):
     * <ul>
     *   <li>Use: {@code log.error("message with context", args)} - NO exception parameter</li>
     *   <li>Do NOT use recordError() - resource is broken anyway</li>
     *   <li>Throw exception - caller handles it</li>
     *   <li>Example: Cannot initialize connection pool, schema creation failed, storage not accessible</li>
     * </ul>
     * 
     * <strong>3. Normal Shutdown/Interruption:</strong>
     * <ul>
     *   <li>Use: {@code log.debug("message with context", args)} - provides context for debugging</li>
     *   <li>Do NOT use recordError() - this is not an error</li>
     *   <li>Re-throw InterruptedException if applicable</li>
     *   <li>Example: Connection closed during shutdown, resource cleanup interrupted</li>
     * </ul>
     * 
     * <strong>4. Retry Logic:</strong>
     * <ul>
     *   <li>During retry attempts: {@code log.debug()} - only for developer debugging</li>
     *   <li>After all retries exhausted: Follow transient or fatal error rules above</li>
     *   <li>Example: {@code catch(IOException e) { log.debug("Retry {}/{}", attempt, max); }}</li>
     * </ul>
     * 
     * <strong>Stack Traces:</strong> Exception stack traces should be logged at DEBUG level
     * separately if needed. Resources should never log exceptions with {@code log.error(..., e)}.
     *
     * @param code    Error code for categorization (e.g., "CONNECTION_FAILED", "READ_ERROR")
     * @param message Human-readable error message
     * @param details Additional context about the error
     */
    protected void recordError(String code, String message, String details) {
        errors.add(new OperationalError(Instant.now(), code, message, details));
        
        // Prevent unbounded memory growth
        int maxErrors = getMaxErrors();
        while (errors.size() > maxErrors) {
            errors.pollFirst();
        }
    }

    @Override
    public List<OperationalError> getErrors() {
        return new ArrayList<>(errors);
    }

    @Override
    public void clearErrors() {
        errors.clear();
    }
    
    /**
     * Clears errors from the collection that match the given predicate.
     * <p>
     * This protected method allows subclasses to selectively clear errors,
     * which is useful for wrapper resources that need to clear errors from
     * specific contexts while preserving others.
     * <p>
     * Example: Queue wrappers can clear only errors from their specific service context.
     *
     * @param filter Predicate to select which errors to remove
     */
    protected void clearErrorsIf(java.util.function.Predicate<OperationalError> filter) {
        errors.removeIf(filter);
    }

    /**
     * Returns whether the resource is healthy.
     * <p>
     * <strong>Default implementation semantics:</strong>
     * <ul>
     *   <li>Any error in collection → unhealthy (resource has encountered problems)</li>
     *   <li>No errors → healthy (resource is functioning correctly)</li>
     * </ul>
     * <p>
     * Subclasses can override this for more specific health checks (e.g., wrappers
     * checking delegate health, or resources with custom health criteria).
     *
     * @return {@code true} if resource has no errors, {@code false} otherwise
     */
    @Override
    public boolean isHealthy() {
        return errors.isEmpty();
    }

    /**
     * Returns metrics for this resource.
     * <p>
     * This implementation returns base metrics tracked by all resources,
     * then calls {@link #addCustomMetrics(Map)} to allow subclasses to add
     * resource-specific metrics.
     * <p>
     * Base metrics included:
     * <ul>
     *   <li>error_count - number of errors in the error collection</li>
     * </ul>
     *
     * @return Map of metric names to their current values
     */
    @Override
    public final Map<String, Number> getMetrics() {
        Map<String, Number> metrics = getBaseMetrics();
        addCustomMetrics(metrics);
        return metrics;
    }

    /**
     * Returns the base metrics tracked by AbstractResource.
     * <p>
     * Private helper method called only by getMetrics().
     * Subclasses should not access this directly - use addCustomMetrics() hook instead.
     *
     * @return Map containing base metrics
     */
    private Map<String, Number> getBaseMetrics() {
        Map<String, Number> metrics = new java.util.LinkedHashMap<>();
        metrics.put("error_count", errors.size());
        return metrics;
    }

    /**
     * Hook method for subclasses to add resource-specific metrics.
     * <p>
     * The default implementation does nothing. Subclasses should override this method
     * to add their own metrics to the provided map.
     * <p>
     * <strong>IMPORTANT:</strong> Always call {@code super.addCustomMetrics(metrics)} first
     * to ensure parent class metrics are included. This is critical for multi-level
     * inheritance hierarchies.
     * <p>
     * Example:
     * <pre>
     * &#64;Override
     * protected void addCustomMetrics(Map&lt;String, Number&gt; metrics) {
     *     super.addCustomMetrics(metrics);  // Always call super first!
     *     
     *     metrics.put("connections_active", activeConnections.get());
     *     metrics.put("cache_hit_rate", calculateCacheHitRate());
     * }
     * </pre>
     *
     * @param metrics Mutable map to add custom metrics to (already contains base metrics)
     */
    protected void addCustomMetrics(Map<String, Number> metrics) {
        // Default: no custom metrics
    }
}