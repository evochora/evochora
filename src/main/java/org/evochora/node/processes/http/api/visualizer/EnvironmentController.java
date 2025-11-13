package org.evochora.node.processes.http.api.visualizer;

import com.typesafe.config.Config;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.evochora.datapipeline.api.resources.database.CellWithCoordinates;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.SpatialRegion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP controller for environment data visualization.
 * <p>
 * Provides REST API endpoints for retrieving environment cell data from the database.
 * Supports spatial filtering and run ID resolution for multi-simulation environments.
 * <p>
 * Key features:
 * <ul>
 *   <li>Spatial region filtering (2D/3D coordinates)</li>
 *   <li>Run ID resolution (query parameter → latest run)</li>
 *   <li>HTTP cache headers for immutable past ticks</li>
 *   <li>Comprehensive error handling (400/404/500)</li>
 * </ul>
 * <p>
 * Thread Safety: This controller is thread-safe and can handle concurrent requests.
 */
public class EnvironmentController extends VisualizerBaseController {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnvironmentController.class);

    /**
     * Constructs a new EnvironmentController.
     *
     * @param registry The central service registry for accessing shared services.
     * @param options  The HOCON configuration specific to this controller instance.
     */
    public EnvironmentController(final org.evochora.node.spi.ServiceRegistry registry, final Config options) {
        super(registry, options);
    }

    @Override
    public void registerRoutes(final Javalin app, final String basePath) {
        final String fullPath = (basePath + "/{tick}").replaceAll("//", "/");
        
        LOGGER.debug("Registering environment endpoint at: {}", fullPath);
        
        app.get(fullPath, this::getEnvironment);
        
        // Setup common exception handlers from base class
        setupExceptionHandlers(app);
    }

    /**
     * Handles GET requests for environment data at a specific tick.
     * <p>
     * Route: GET /{tick}?region=x1,x2,y1,y2&runId=...
     * <p>
     * Query parameters:
     * <ul>
     *   <li>region: Optional spatial region as comma-separated bounds (e.g., "0,100,0,100")</li>
     *   <li>runId: Optional simulation run ID (defaults to latest run)</li>
     * </ul>
     * <p>
     * Response format:
     * <pre>
     * {
     *   "tick": 100,
     *   "runId": "20251023_120000_ABC",
     *   "region": {"bounds": [0, 100, 0, 100]},
     *   "cells": [
     *     {"coordinates": [5, 10], "moleculeType": 1, "moleculeValue": 255, "ownerId": 7}
     *   ]
     * }
     * </pre>
     *
     * @param ctx The Javalin context containing request and response data.
     * @throws IllegalArgumentException if tick parameter is invalid
     * @throws VisualizerBaseController.NoRunIdException if no run ID is available
     * @throws SQLException if database operation fails
     */
    void getEnvironment(final Context ctx) throws SQLException {
        // Parse and validate tick parameter
        final long tickNumber = parseTickNumber(ctx.pathParam("tick"));
        
        // Parse region parameter (optional)
        final String regionParam = ctx.queryParam("region");
        final SpatialRegion region = parseRegion(regionParam);
        
        // Resolve run ID (query parameter → latest)
        final String runId = resolveRunId(ctx);
        
        LOGGER.debug("Retrieving environment data: tick={}, runId={}, region={}", tickNumber, runId, region);
        
        // Query database for environment data
        try (final IDatabaseReader reader = databaseProvider.createReader(runId)) {
            final List<CellWithCoordinates> cells = reader.readEnvironmentRegion(tickNumber, region);
            
            // Build response
            final Map<String, Object> response = new HashMap<>();
            response.put("tick", tickNumber);
            response.put("runId", runId);
            response.put("region", region);
            response.put("cells", cells);
            
            // HTTP Cache Headers: Tick data is IMMUTABLE after indexing
            // Aggressive caching enables client-side cache with 0ms latency on repeated queries
            // Browser/Client can cache indefinitely - data NEVER changes once indexed
            ctx.header("Cache-Control", "public, max-age=31536000, immutable"); // 1 year + immutable
            ctx.header("ETag", String.format("\"%s_%d\"", runId, tickNumber));
            
            ctx.status(HttpStatus.OK).json(response);
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
            throw new RuntimeException("Error retrieving environment data for runId: " + runId, e);
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
     * Parses and validates the tick number from the path parameter.
     *
     * @param tickParam The tick parameter from the URL path
     * @return The parsed tick number
     * @throws IllegalArgumentException if the tick parameter is invalid
     */
    private long parseTickNumber(final String tickParam) {
        if (tickParam == null || tickParam.trim().isEmpty()) {
            throw new IllegalArgumentException("Tick parameter is required");
        }
        
        try {
            final long tick = Long.parseLong(tickParam.trim());
            if (tick < 0) {
                throw new IllegalArgumentException("Tick number must be non-negative");
            }
            return tick;
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("Invalid tick number: " + tickParam, e);
        }
    }

    /**
     * Parses the region parameter into a SpatialRegion object.
     * <p>
     * Format: "x1,x2,y1,y2" for 2D or "x1,x2,y1,y2,z1,z2" for 3D
     * <p>
     * Examples:
     * <ul>
     *   <li>"0,100,0,100" → 2D region from (0,0) to (100,100)</li>
     *   <li>"0,100,0,100,0,50" → 3D region from (0,0,0) to (100,100,50)</li>
     * </ul>
     *
     * @param regionParam The region parameter string (can be null)
     * @return SpatialRegion object or null if no region specified
     * @throws IllegalArgumentException if region format is invalid
     */
    private SpatialRegion parseRegion(final String regionParam) {
        if (regionParam == null || regionParam.trim().isEmpty()) {
            return null;
        }
        
        final String[] parts = regionParam.trim().split(",");
        if (parts.length % 2 != 0) {
            throw new IllegalArgumentException("Region must have even number of values (min/max pairs)");
        }
        
        try {
            final int[] bounds = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                bounds[i] = Integer.parseInt(parts[i].trim());
            }
            
            return new SpatialRegion(bounds);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("Invalid region format: " + regionParam, e);
        }
    }

}
