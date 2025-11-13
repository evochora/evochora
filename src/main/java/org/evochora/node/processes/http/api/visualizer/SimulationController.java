package org.evochora.node.processes.http.api.visualizer;

import com.typesafe.config.Config;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.TickRange;
import org.evochora.datapipeline.utils.protobuf.ProtobufConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP controller for simulation metadata and tick information.
 * <p>
 * Provides REST API endpoints for retrieving simulation metadata and tick range information
 * from the database. Supports run ID resolution for multi-simulation environments.
 * <p>
 * Key features:
 * <ul>
 *   <li>Simulation metadata endpoint (worldshape, samplingInterval, etc.)</li>
 *   <li>Tick range endpoint (minTick, maxTick)</li>
 *   <li>Run ID resolution (query parameter → latest run)</li>
 *   <li>HTTP cache headers for immutable metadata</li>
 *   <li>Comprehensive error handling (400/404/500)</li>
 * </ul>
 * <p>
 * Thread Safety: This controller is thread-safe and can handle concurrent requests.
 */
public class SimulationController extends VisualizerBaseController {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimulationController.class);

    /**
     * Constructs a new SimulationController.
     *
     * @param registry The central service registry for accessing shared services.
     * @param options  The HOCON configuration specific to this controller instance.
     */
    public SimulationController(final org.evochora.node.spi.ServiceRegistry registry, final Config options) {
        super(registry, options);
    }

    @Override
    public void registerRoutes(final Javalin app, final String basePath) {
        final String metadataPath = (basePath + "/metadata").replaceAll("//", "/");
        final String ticksPath = (basePath + "/ticks").replaceAll("//", "/");
        
        LOGGER.debug("Registering simulation endpoints: metadata={}, ticks={}", metadataPath, ticksPath);
        
        app.get(metadataPath, this::getMetadata);
        app.get(ticksPath, this::getTicks);
        
        // Setup common exception handlers from base class
        setupExceptionHandlers(app);
    }

    /**
     * Handles GET requests for simulation metadata.
     * <p>
     * Route: GET /metadata?runId=...
     * <p>
     * Query parameters:
     * <ul>
     *   <li>runId: Optional simulation run ID (defaults to latest run)</li>
     * </ul>
     * <p>
     * Response format: JSON representation of SimulationMetadata protobuf
     * <p>
     * HTTP Caching: Metadata is immutable, so aggressive caching is used (ETag, Cache-Control: immutable)
     *
     * @param ctx The Javalin context containing request and response data.
     * @throws VisualizerBaseController.NoRunIdException if no run ID is available
     * @throws SQLException if database operation fails
     */
    void getMetadata(final Context ctx) throws SQLException {
        // Resolve run ID (query parameter → latest)
        final String runId = resolveRunId(ctx);
        
        LOGGER.debug("Retrieving simulation metadata: runId={}", runId);
        
        // Query database for metadata
        try (final IDatabaseReader reader = databaseProvider.createReader(runId)) {
            final SimulationMetadata metadata = reader.getMetadata();
            
            // Convert Protobuf to JSON string directly (no unnecessary conversion)
            final String jsonString = ProtobufConverter.toJson(metadata);
            
            // HTTP Cache Headers: Metadata is IMMUTABLE (never changes after creation)
            // Aggressive caching enables client-side cache with 0ms latency on repeated queries
            ctx.header("Cache-Control", "public, max-age=31536000, immutable"); // 1 year + immutable
            ctx.header("ETag", String.format("\"%s_metadata\"", runId));
            ctx.contentType("application/json");
            
            ctx.status(HttpStatus.OK).result(jsonString);
        } catch (org.evochora.datapipeline.api.resources.database.MetadataNotFoundException e) {
            // Metadata not found - return 404
            throw new VisualizerBaseController.NoRunIdException("Metadata not found for run: " + runId, e);
        } catch (RuntimeException e) {
            // Check if the error is due to non-existent schema (run ID not found)
            if (e.getCause() instanceof SQLException) {
                SQLException sqlEx = (SQLException) e.getCause();
                String msg = sqlEx.getMessage();
                
                if (msg != null) {
                    String lowerMsg = msg.toLowerCase();
                    
                    // Check for schema errors FIRST (before pool exhaustion)
                    if (msg.contains("schema") || msg.contains("Schema")) {
                        // Schema doesn't exist - run ID not found
                        throw new VisualizerBaseController.NoRunIdException("Run ID not found: " + runId);
                    }
                    
                    // Check for connection pool timeout/exhaustion (specific patterns only)
                    if (lowerMsg.contains("timeout") || 
                        lowerMsg.contains("connection is not available") ||
                        lowerMsg.contains("connection pool")) {
                        // Connection pool exhausted or timeout
                        throw new VisualizerBaseController.PoolExhaustionException("Connection pool exhausted or timeout", sqlEx);
                    }
                }
            }
            // Other runtime errors - wrap to provide better context
            throw new RuntimeException("Error retrieving metadata for runId: " + runId, e);
        } catch (SQLException e) {
            // Check if the error is due to non-existent schema (run ID not found)
            if (e.getMessage() != null && 
                (e.getMessage().contains("schema") || e.getMessage().contains("Schema"))) {
                // Schema doesn't exist - run ID not found
                throw new VisualizerBaseController.NoRunIdException("Run ID not found: " + runId);
            }
            // Other database errors
            throw e;
        }
    }

    /**
     * Handles GET requests for tick range information.
     * <p>
     * Route: GET /ticks?runId=...
     * <p>
     * Query parameters:
     * <ul>
     *   <li>runId: Optional simulation run ID (defaults to latest run)</li>
     * </ul>
     * <p>
     * Response format:
     * <pre>
     * {
     *   "minTick": 0,
     *   "maxTick": 1000
     * }
     * </pre>
     * <p>
     * Returns 404 if no ticks are available.
     * <p>
     * HTTP Caching: No caching (ticks can change during simulation)
     *
     * @param ctx The Javalin context containing request and response data.
     * @throws VisualizerBaseController.NoRunIdException if no run ID is available
     * @throws SQLException if database operation fails
     */
    void getTicks(final Context ctx) throws SQLException {
        // Resolve run ID (query parameter → latest)
        final String runId = resolveRunId(ctx);
        
        LOGGER.debug("Retrieving tick range: runId={}", runId);
        
        // Query database for tick range
        try (final IDatabaseReader reader = databaseProvider.createReader(runId)) {
            final TickRange tickRange = reader.getTickRange();
            
            if (tickRange == null) {
                // No ticks available - return 404
                throw new VisualizerBaseController.NoRunIdException("No ticks available for run: " + runId);
            }
            
            // Build response
            final Map<String, Object> response = new HashMap<>();
            response.put("minTick", tickRange.minTick());
            response.put("maxTick", tickRange.maxTick());
            
            // No caching - ticks can change during simulation
            ctx.status(HttpStatus.OK).json(response);
        } catch (VisualizerBaseController.NoRunIdException e) {
            // Re-throw NoRunIdException directly (will be handled by exception handler)
            throw e;
        } catch (RuntimeException e) {
            // Check if the error is due to non-existent schema (run ID not found)
            if (e.getCause() instanceof SQLException) {
                SQLException sqlEx = (SQLException) e.getCause();
                String msg = sqlEx.getMessage();
                
                if (msg != null) {
                    String lowerMsg = msg.toLowerCase();
                    
                    // Check for schema errors FIRST (before pool exhaustion)
                    if (msg.contains("schema") || msg.contains("Schema")) {
                        // Schema doesn't exist - run ID not found
                        throw new VisualizerBaseController.NoRunIdException("Run ID not found: " + runId);
                    }
                    
                    // Check for connection pool timeout/exhaustion (specific patterns only)
                    if (lowerMsg.contains("timeout") || 
                        lowerMsg.contains("connection is not available") ||
                        lowerMsg.contains("connection pool")) {
                        // Connection pool exhausted or timeout
                        throw new VisualizerBaseController.PoolExhaustionException("Connection pool exhausted or timeout", sqlEx);
                    }
                }
            }
            // Other runtime errors - wrap to provide better context
            throw new RuntimeException("Error retrieving tick range for runId: " + runId, e);
        } catch (SQLException e) {
            // Check if the error is due to non-existent schema (run ID not found)
            if (e.getMessage() != null && 
                (e.getMessage().contains("schema") || e.getMessage().contains("Schema"))) {
                // Schema doesn't exist - run ID not found
                throw new VisualizerBaseController.NoRunIdException("Run ID not found: " + runId);
            }
            // Other database errors
            throw e;
        }
    }
}

