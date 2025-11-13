package org.evochora.node.processes.http.api.visualizer;

import com.typesafe.config.Config;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.node.processes.http.AbstractController;
import org.evochora.node.spi.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

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
            LOGGER.warn("No run ID available for request {}: {}", ctx.path(), e.getMessage());
            ctx.status(HttpStatus.NOT_FOUND).json(createErrorBody(HttpStatus.NOT_FOUND, e.getMessage()));
        });
        app.exception(PoolExhaustionException.class, (e, ctx) -> {
            LOGGER.warn("Connection pool exhausted for request {}: {}", ctx.path(), e.getMessage());
            ctx.status(HttpStatus.TOO_MANY_REQUESTS).json(createErrorBody(HttpStatus.TOO_MANY_REQUESTS, "Server is under heavy load, please try again later"));
        });
        app.exception(SQLException.class, (e, ctx) -> {
            LOGGER.error("Database error for request {}", ctx.path(), e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(createErrorBody(HttpStatus.INTERNAL_SERVER_ERROR, "Database error occurred"));
        });
        app.exception(Exception.class, (e, ctx) -> {
            LOGGER.error("Unhandled exception for request {}", ctx.path(), e);
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

