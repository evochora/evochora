package org.evochora.node.processes.http.api.visualizer;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.api.resources.database.OrganismNotFoundException;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
import org.evochora.node.processes.http.AbstractController;
import org.evochora.node.spi.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

/**
 * Base controller for visualizer API endpoints with shared functionality.
 * <p>
 * Provides common methods for run ID resolution and exception handling
 * that are shared across visualizer controllers (Environment, Simulation, etc.).
 * <p>
 * Key features:
 * <ul>
 *   <li>Run ID resolution (query parameter → latest run)</li>
 *   <li>Standardized exception handling</li>
 *   <li>Error response formatting</li>
 * </ul>
 * <p>
 * Thread Safety: This controller is thread-safe and can handle concurrent requests.
 */
public abstract class VisualizerBaseController extends AbstractController {

    private static final Logger LOGGER = LoggerFactory.getLogger(VisualizerBaseController.class);
    
    protected final IDatabaseReaderProvider databaseProvider;

    /**
     * Constructs a new VisualizerBaseController.
     *
     * @param registry The central service registry for accessing shared services.
     * @param options  The HOCON configuration specific to this controller instance.
     */
    protected VisualizerBaseController(final ServiceRegistry registry, final Config options) {
        super(registry, options);
        this.databaseProvider = registry.get(IDatabaseReaderProvider.class);
    }

    /**
     * Sets up common exception handlers for visualizer endpoints.
     * <p>
     * Registers handlers for:
     * <ul>
     *   <li>IllegalArgumentException → 400 Bad Request</li>
     *   <li>NoRunIdException → 404 Not Found</li>
     *   <li>PoolExhaustionException → 429 Too Many Requests</li>
     *   <li>SQLException → 500 Internal Server Error</li>
     *   <li>Exception → 500 Internal Server Error</li>
     * </ul>
     *
     * @param app The Javalin application instance
     */
    protected void setupExceptionHandlers(final Javalin app) {
        app.exception(IllegalArgumentException.class, (e, ctx) -> {
            LOGGER.warn("Invalid request parameters for {}: {}", ctx.path(), e.getMessage());
            ctx.status(HttpStatus.BAD_REQUEST).json(createErrorBody(HttpStatus.BAD_REQUEST, e.getMessage()));
        });
        app.exception(NoRunIdException.class, (e, ctx) -> {
            LOGGER.debug("No run ID available for request {}: {}", ctx.path(), e.getMessage());
            ctx.status(HttpStatus.NOT_FOUND).json(createErrorBody(HttpStatus.NOT_FOUND, e.getMessage()));
        });
        app.exception(TickNotFoundException.class, (e, ctx) -> {
            LOGGER.debug("Client requested a tick that was not found for {}: {}", ctx.path(), e.getMessage());
            ctx.status(HttpStatus.NOT_FOUND).json(createErrorBody(HttpStatus.NOT_FOUND, e.getMessage()));
        });
        app.exception(OrganismNotFoundException.class, (e, ctx) -> {
            LOGGER.debug("Client requested an organism that was not found for {}: {}", ctx.path(), e.getMessage());
            ctx.status(HttpStatus.NOT_FOUND).json(createErrorBody(HttpStatus.NOT_FOUND, e.getMessage()));
        });
        app.exception(PoolExhaustionException.class, (e, ctx) -> {
            LOGGER.warn("Connection pool exhausted for request {}: {}", ctx.path(), e.getMessage());
            ctx.status(HttpStatus.TOO_MANY_REQUESTS).json(createErrorBody(HttpStatus.TOO_MANY_REQUESTS, "Server is under heavy load, please try again later"));
        });
        app.exception(SQLException.class, (e, ctx) -> {
            // H2 error codes: 42102 = Table not found, 42104 = Schema not found
            // These indicate client requested data that doesn't exist (404, not 500)
            if (e.getErrorCode() == 42102 || e.getErrorCode() == 42104) {
                LOGGER.debug("Table/schema not found for request {}: {}", ctx.path(), e.getMessage());
                ctx.status(HttpStatus.NOT_FOUND).json(createErrorBody(HttpStatus.NOT_FOUND, 
                    "Requested data not available. The simulation may have been created with a different schema version."));
            } else {
                LOGGER.error("Database error for request {}: {}", ctx.path(), e.getMessage());
                LOGGER.debug("Database error stack trace", e);
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(createErrorBody(HttpStatus.INTERNAL_SERVER_ERROR, "Database error occurred"));
            }
        });
        app.exception(Exception.class, (e, ctx) -> {
            LOGGER.error("Unhandled exception for request {}: {}", ctx.path(), e.getMessage());
            LOGGER.debug("Unhandled exception stack trace", e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(createErrorBody(HttpStatus.INTERNAL_SERVER_ERROR, "An internal server error occurred"));
        });
    }

    /**
     * Resolves the run ID from query parameters or falls back to the latest run.
     * <p>
     * Resolution order:
     * <ol>
     *   <li>Query parameter "runId" (if provided)</li>
     *   <li>Latest run from database (if available)</li>
     *   <li>Throw NoRunIdException (if no runs exist)</li>
     * </ol>
     *
     * @param ctx The Javalin context for accessing query parameters
     * @return The resolved run ID
     * @throws NoRunIdException if no run ID is available
     */
    protected String resolveRunId(final Context ctx) {
        // Check query parameter first
        final String queryRunId = ctx.queryParam("runId");
        if (queryRunId != null && !queryRunId.trim().isEmpty()) {
            return queryRunId.trim();
        }
        
        // Fall back to latest run
        try {
            final String latestRunId = databaseProvider.findLatestRunId();
            if (latestRunId == null) {
                throw new NoRunIdException("No simulation runs available");
            }
            return latestRunId;
        } catch (java.sql.SQLException e) {
            // Wrap SQLException in RuntimeException - will be caught by exception handler
            throw new RuntimeException("Failed to find latest run ID", e);
        }
    }

    /**
     * Creates a standardized error response body.
     *
     * @param status  The HTTP status code
     * @param message The error message
     * @return Map containing error details
     */
    protected Map<String, Object> createErrorBody(final HttpStatus status, final String message) {
        final Map<String, Object> errorBody = new HashMap<>();
        errorBody.put("timestamp", java.time.Instant.now().toString());
        errorBody.put("status", status.getCode());
        errorBody.put("error", status.getMessage());
        errorBody.put("message", message);
        return errorBody;
    }

    /**
     * Applies HTTP cache headers based on the provided cache configuration.
     * <p>
     * This method handles:
     * <ul>
     *   <li>Cache-Control header (no-cache if disabled, or max-age if enabled)</li>
     *   <li>ETag header (if useETag is enabled)</li>
     *   <li>If-None-Match validation (returns 304 Not Modified if ETag matches)</li>
     * </ul>
     * <p>
     * If cache is disabled, sets {@code Cache-Control: no-cache, no-store, must-revalidate}
     * and returns early (no ETag processing).
     * <p>
     * If ETag validation is enabled and the client's If-None-Match header matches the provided
     * ETag, sets status to 304 Not Modified and returns early (skips database query).
     * <p>
     * <strong>Thread Safety:</strong> This method is thread-safe and can be called concurrently.
     *
     * @param ctx    The Javalin context for setting headers and status
     * @param config The cache configuration (enabled, maxAge, useETag)
     * @param etag   The ETag value to use (only used if config.useETag is true)
     * @return true if 304 Not Modified was sent (caller should return early), false otherwise
     */
    protected boolean applyCacheHeaders(final Context ctx, final CacheConfig config, final String etag) {
        if (!config.enabled) {
            // Cache disabled - set no-cache headers and return
            ctx.header("Cache-Control", "no-cache, no-store, must-revalidate");
            return false;
        }

        // Cache enabled - check ETag validation if configured
        if (config.useETag) {
            final String ifNoneMatch = ctx.header("If-None-Match");
            if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
                // Client has current version - return 304 Not Modified
                ctx.status(HttpStatus.NOT_MODIFIED);
                return true; // Indicate that 304 was sent
            }
            // Set ETag header for client to cache
            ctx.header("ETag", etag);
        }

        // Set Cache-Control header with max-age
        ctx.header("Cache-Control", String.format("public, max-age=%d, must-revalidate", config.maxAge));
        return false; // Normal response should continue
    }

    /**
     * Cache configuration for HTTP response headers.
     * <p>
     * Controls HTTP caching behavior for visualizer API endpoints.
     * <p>
     * <strong>Thread Safety:</strong> This class is immutable and thread-safe.
     */
    protected static class CacheConfig {
        /**
         * Whether HTTP caching is enabled for this endpoint.
         */
        final boolean enabled;

        /**
         * Maximum cache age in seconds (only used if enabled is true).
         */
        final int maxAge;

        /**
         * Whether ETag validation should be used (only used if enabled is true).
         */
        final boolean useETag;

        /**
         * Constructs a new CacheConfig.
         *
         * @param enabled Whether caching is enabled
         * @param maxAge  Maximum cache age in seconds
         * @param useETag Whether ETag validation should be used
         */
        CacheConfig(final boolean enabled, final int maxAge, final boolean useETag) {
            this.enabled = enabled;
            this.maxAge = maxAge;
            this.useETag = useETag;
        }

        /**
         * Parses cache configuration from HOCON Config.
         * <p>
         * Configuration structure:
         * <ul>
         *   <li>For single endpoint: {@code cache { enabled = false, maxAge = 0, useETag = false }}</li>
         *   <li>For multiple endpoints: {@code cache { metadata { enabled = false, ... }, ticks { enabled = false, ... } }}</li>
         * </ul>
         * <p>
         * Defaults (if config is missing or incomplete):
         * <ul>
         *   <li>{@code enabled = false}</li>
         *   <li>{@code maxAge = 0}</li>
         *   <li>{@code useETag = false}</li>
         * </ul>
         *
         * @param options      The controller options Config
         * @param endpointName The endpoint name ("environment", "metadata", or "ticks")
         * @return CacheConfig with parsed values or defaults
         */
        static CacheConfig fromConfig(final com.typesafe.config.Config options, final String endpointName) {
            // Default values: no caching
            boolean enabled = false;
            int maxAge = 0;
            boolean useETag = false;

            // Try to parse cache configuration
            if (options.hasPath("cache")) {
                final com.typesafe.config.Config cacheConfig = options.getConfig("cache");

                // Check if this is a nested endpoint config (e.g., cache.metadata or cache.ticks)
                if (cacheConfig.hasPath(endpointName)) {
                    final com.typesafe.config.Config endpointConfig = cacheConfig.getConfig(endpointName);
                    enabled = endpointConfig.hasPath("enabled") ? endpointConfig.getBoolean("enabled") : false;
                    maxAge = endpointConfig.hasPath("maxAge") ? endpointConfig.getInt("maxAge") : 0;
                    useETag = endpointConfig.hasPath("useETag") ? endpointConfig.getBoolean("useETag") : false;
                } else {
                    // Direct cache config (e.g., for environment endpoint)
                    enabled = cacheConfig.hasPath("enabled") ? cacheConfig.getBoolean("enabled") : false;
                    maxAge = cacheConfig.hasPath("maxAge") ? cacheConfig.getInt("maxAge") : 0;
                    useETag = cacheConfig.hasPath("useETag") ? cacheConfig.getBoolean("useETag") : false;
                }
            }

            return new CacheConfig(enabled, maxAge, useETag);
        }
    }

    /**
     * Exception thrown when no run ID is available for the request.
     */
    public static class NoRunIdException extends RuntimeException {
        public NoRunIdException(final String message) {
            super(message);
        }
        
        public NoRunIdException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Exception thrown when the connection pool is exhausted or a timeout occurs.
     */
    public static class PoolExhaustionException extends RuntimeException {
        public PoolExhaustionException(final String message) {
            super(message);
        }
        
        public PoolExhaustionException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}

