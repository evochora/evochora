package org.evochora.node.processes.http.api.visualizer;

import com.typesafe.config.Config;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.OrganismNotFoundException;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickDetails;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;
import org.evochora.datapipeline.api.resources.database.dto.TickRange;

import java.util.LinkedHashMap;
import java.util.Map;
import org.evochora.node.processes.http.api.pipeline.dto.ErrorResponseDto;
import org.evochora.node.processes.http.api.visualizer.dto.OrganismsResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;

/**
 * HTTP controller for organism data visualization.
 * <p>
 * Provides REST API endpoints for retrieving organism summaries and detailed state
 * for the visualizer. Uses the indexed organism tables (organisms, organism_states)
 * as data source via {@link IDatabaseReader}.
 * <p>
 * Key features:
 * <ul>
 *   <li>Tick-based organism listing for grid and dropdown views</li>
 *   <li>Per-organism detailed state for sidebar view</li>
 *   <li>Run ID resolution (query parameter → latest run)</li>
 *   <li>Optional HTTP caching with ETags (disabled by default)</li>
 *   <li>Comprehensive error handling (400/404/429/500)</li>
 * </ul>
 * <p>
 * Thread Safety: This controller is thread-safe and can handle concurrent requests.
 */
public class OrganismController extends VisualizerBaseController {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrganismController.class);

    /**
     * Constructs a new OrganismController.
     *
     * @param registry The central service registry for accessing shared services.
     * @param options  The HOCON configuration specific to this controller instance.
     */
    public OrganismController(final org.evochora.node.spi.ServiceRegistry registry, final Config options) {
        super(registry, options);
    }

    @Override
    public void registerRoutes(final Javalin app, final String basePath) {
        final String listPath = (basePath + "/{tick}").replaceAll("//", "/");
        final String detailPath = (basePath + "/{tick}/{organismId}").replaceAll("//", "/");
        final String ticksPath = (basePath + "/ticks").replaceAll("//", "/");

        LOGGER.debug("Registering organism endpoints: list={}, detail={}, ticks={}", listPath, detailPath, ticksPath);

        // IMPORTANT: Register /ticks BEFORE /{tick} to avoid path parameter conflict
        // Javalin matches routes in registration order, so /ticks must come first
        app.get(ticksPath, this::getTicks);
        app.get(listPath, this::getOrganismsAtTick);
        app.get(detailPath, this::getOrganismDetails);

        // Setup common exception handlers from base class
        setupExceptionHandlers(app);
    }

    /**
     * Handles GET requests for all organisms that are alive at a specific tick.
     * <p>
     * Route: GET /visualizer/api/organisms/{tick}?runId=...
     * <p>
     * Response format:
     * <pre>
     * {
     *   "runId": "string",
     *   "tick": 1234,
     *   "organisms": [ OrganismTickSummary... ]
     * }
     * </pre>
     *
     * @param ctx The Javalin context containing request and response data.
     * @throws IllegalArgumentException if the tick parameter is invalid
     * @throws NoRunIdException if no run ID is available
     * @throws SQLException if database operations fail
     */
    @OpenApi(
        path = "{tick}",
        methods = {HttpMethod.GET},
        summary = "Get all organisms at a specific tick",
        description = "Returns a list of all organisms that are alive at the specified tick",
        tags = {"visualizer / organism"},
        pathParams = {
            @OpenApiParam(name = "tick", description = "The tick number", required = true, type = Long.class)
        },
        queryParams = {
            @OpenApiParam(name = "runId", description = "Optional simulation run ID (defaults to latest run)", required = false)
        },
        responses = {
            @OpenApiResponse(status = "200", description = "OK", content = @OpenApiContent(from = OrganismsResponseDto.class)),
            @OpenApiResponse(status = "304", description = "Not Modified (cached response, ETag matches)"),
            @OpenApiResponse(status = "400", description = "Bad request (invalid tick)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "404", description = "Not found (run ID not found)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "429", description = "Too many requests (connection pool exhausted)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "500", description = "Internal server error (database error)", content = @OpenApiContent(from = ErrorResponseDto.class))
        }
    )
    void getOrganismsAtTick(final Context ctx) throws SQLException {
        final long tickNumber = parseTickNumber(ctx.pathParam("tick"));
        final String runId = resolveRunId(ctx);

        LOGGER.debug("Retrieving organisms for tick={} runId={}", tickNumber, runId);

        // Parse cache configuration (separate namespace "organisms")
        final CacheConfig cacheConfig = CacheConfig.fromConfig(options, "organisms");

        try (final IDatabaseReader reader = databaseProvider.createReader(runId)) {
            // Generate ETag: "runId_tick"
            final String etag = "\"" + runId + "_" + tickNumber + "\"";

            // Apply cache headers (may return 304 Not Modified if ETag matches)
            if (applyCacheHeaders(ctx, cacheConfig, etag)) {
                return;
            }

            final List<OrganismTickSummary> organisms = reader.readOrganismsAtTick(tickNumber);
            final int totalOrganismCount = reader.readTotalOrganismsCreated(tickNumber);
            final Map<Long, Long> genomeTree = reader.readGenomeLineageTree(tickNumber);

            // Convert Long keys/values to String to preserve 64-bit precision in JSON
            final Map<String, String> stringTree = new LinkedHashMap<>(genomeTree.size());
            genomeTree.forEach((k, v) -> stringTree.put(String.valueOf(k), v != null ? String.valueOf(v) : null));

            ctx.status(HttpStatus.OK).json(new OrganismsResponseDto(organisms, totalOrganismCount, stringTree));
        } catch (RuntimeException e) {
            // Check for schema / connection issues analogous to Environment/SimulationController
            if (e.getCause() instanceof SQLException sqlEx) {
                if (isPoolExhaustion(sqlEx)) {
                    throw new PoolExhaustionException("Connection pool exhausted", sqlEx);
                }
                final String msg = sqlEx.getMessage();
                if (msg != null && (msg.contains("schema") || msg.contains("Schema"))) {
                    throw new NoRunIdException("Run ID not found: " + runId);
                }
            }
            throw new RuntimeException("Error retrieving organisms for runId: " + runId, e);
        } catch (SQLException e) {
            if (e.getMessage() != null
                    && (e.getMessage().contains("schema") || e.getMessage().contains("Schema"))) {
                throw new NoRunIdException("Run ID not found: " + runId);
            }
            throw e;
        }
    }

    /**
     * Handles GET requests for full organism details at a specific tick.
     * <p>
     * Route: GET /visualizer/api/organisms/{tick}/{organismId}?runId=...
     * <p>
     * Response format:
     * <pre>
     * {
     *   "runId": "string",
     *   "tick": 1234,
     *   "organismId": 1,
     *   "static": { ... },
     *   "state": { ... }
     * }
     * </pre>
     *
     * @param ctx The Javalin context containing request and response data.
     * @throws IllegalArgumentException if tick or organismId are invalid
     * @throws NoRunIdException if no run ID is available
     * @throws SQLException if database operations fail
     */
    @OpenApi(
        path = "{tick}/{organismId}",
        methods = {HttpMethod.GET},
        summary = "Get organism details at a specific tick",
        description = "Returns detailed state information for a specific organism at a specific tick",
        tags = {"visualizer / organism"},
        pathParams = {
            @OpenApiParam(name = "tick", description = "The tick number", required = true, type = Long.class),
            @OpenApiParam(name = "organismId", description = "The organism ID", required = true, type = Integer.class)
        },
        queryParams = {
            @OpenApiParam(name = "runId", description = "Optional simulation run ID (defaults to latest run)", required = false)
        },
        responses = {
            @OpenApiResponse(status = "200", description = "OK", content = @OpenApiContent(from = OrganismTickDetails.class)),
            @OpenApiResponse(status = "304", description = "Not Modified (cached response, ETag matches)"),
            @OpenApiResponse(status = "400", description = "Bad request (invalid tick or organismId)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "404", description = "Not found (organism, tick, or run ID not found)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "429", description = "Too many requests (connection pool exhausted)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "500", description = "Internal server error (database error)", content = @OpenApiContent(from = ErrorResponseDto.class))
        }
    )
    void getOrganismDetails(final Context ctx) throws SQLException, OrganismNotFoundException {
        final long tickNumber = parseTickNumber(ctx.pathParam("tick"));
        final int organismId = parseOrganismId(ctx.pathParam("organismId"));
        final String runId = resolveRunId(ctx);

        LOGGER.debug("Retrieving organism details: tick={}, organismId={}, runId={}", tickNumber, organismId, runId);

        // Parse cache configuration (separate namespace "organismDetails")
        final CacheConfig cacheConfig = CacheConfig.fromConfig(options, "organismDetails");

        try (final IDatabaseReader reader = databaseProvider.createReader(runId)) {
            // ETag: "runId_tick_organismId"
            final String etag = "\"" + runId + "_" + tickNumber + "_" + organismId + "\"";

            if (applyCacheHeaders(ctx, cacheConfig, etag)) {
                return;
            }

            final OrganismTickDetails details = reader.readOrganismDetails(tickNumber, organismId);

            // Return DTO directly (contains all fields including state.instructions)
            ctx.status(HttpStatus.OK).json(details);
        } catch (OrganismNotFoundException e) {
            // Specific 404 for missing organism or tick row, re-throw for central handler
            throw e;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof SQLException sqlEx) {
                if (isPoolExhaustion(sqlEx)) {
                    throw new PoolExhaustionException("Connection pool exhausted", sqlEx);
                }
                final String msg = sqlEx.getMessage();
                if (msg != null && (msg.contains("schema") || msg.contains("Schema"))) {
                    throw new NoRunIdException("Run ID not found: " + runId);
                }
            }
            throw new RuntimeException("Error retrieving organism details for runId: " + runId, e);
        } catch (SQLException e) {
            if (e.getMessage() != null
                    && (e.getMessage().contains("schema") || e.getMessage().contains("Schema"))) {
                throw new NoRunIdException("Run ID not found: " + runId);
            }
            throw e;
        }
    }

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
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid tick number: " + tickParam, e);
        }
    }

    private int parseOrganismId(final String organismParam) {
        if (organismParam == null || organismParam.trim().isEmpty()) {
            throw new IllegalArgumentException("OrganismId parameter is required");
        }
        try {
            final int id = Integer.parseInt(organismParam.trim());
            if (id < 0) {
                throw new IllegalArgumentException("OrganismId must be non-negative");
            }
            return id;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid organismId: " + organismParam, e);
        }
    }

    /**
     * Handles GET requests for the tick range of indexed organism data.
     * <p>
     * Route: GET /visualizer/api/organisms/ticks?runId=...
     * <p>
     * Returns the minimum and maximum tick numbers that have been indexed by the OrganismIndexer.
     * This is NOT the actual simulation tick range, but only the ticks that are available in the database.
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
     *
     * @param ctx The Javalin context containing request and response data.
     * @throws VisualizerBaseController.NoRunIdException if no run ID is available
     * @throws SQLException if database operation fails
     */
    @OpenApi(
        path = "ticks",
        methods = {HttpMethod.GET},
        summary = "Get organism tick range",
        description = "Returns the minimum and maximum tick numbers that have been indexed by the OrganismIndexer. This represents the ticks available in the database, not the actual simulation tick range.",
        tags = {"visualizer / organism"},
        queryParams = {
            @OpenApiParam(name = "runId", description = "Optional simulation run ID (defaults to latest run)", required = false)
        },
        responses = {
            @OpenApiResponse(status = "200", description = "OK", content = @OpenApiContent(from = TickRange.class)),
            @OpenApiResponse(status = "304", description = "Not Modified (cached response, ETag matches)"),
            @OpenApiResponse(status = "400", description = "Bad request (invalid parameters)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "404", description = "Not found (run ID not found or no ticks available)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "429", description = "Too many requests (connection pool exhausted)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "500", description = "Internal server error (database error)", content = @OpenApiContent(from = ErrorResponseDto.class))
        }
    )
    void getTicks(final Context ctx) throws SQLException {
        // Resolve run ID (query parameter → latest)
        final String runId = resolveRunId(ctx);
        
        LOGGER.debug("Retrieving organism tick range: runId={}", runId);
        
        // Parse cache configuration
        final CacheConfig cacheConfig = CacheConfig.fromConfig(options, "ticks");
        
        // Query database for tick range (needed for ETag generation if useETag=true)
        try (final IDatabaseReader reader = databaseProvider.createReader(runId)) {
            final TickRange tickRange = reader.getOrganismTickRange();
            
            if (tickRange == null) {
                // No ticks available - return 404
                throw new VisualizerBaseController.NoRunIdException("No organism ticks available for run: " + runId);
            }
            
            // Generate ETag: runId_maxTick (maxTick can change during simulation)
            final String etag = "\"" + runId + "_" + tickRange.maxTick() + "\"";
            
            // Apply cache headers (may return 304 Not Modified if ETag matches)
            if (applyCacheHeaders(ctx, cacheConfig, etag)) {
                // 304 Not Modified was sent - return early
                return;
            }
            
            // Return TickRange directly (DTO)
            ctx.status(HttpStatus.OK).json(tickRange);
        } catch (VisualizerBaseController.NoRunIdException e) {
            // Re-throw NoRunIdException directly (will be handled by exception handler)
            throw e;
        } catch (RuntimeException e) {
            // Check if the error is due to non-existent schema (run ID not found)
            if (e.getCause() instanceof SQLException sqlEx) {
                if (isPoolExhaustion(sqlEx)) {
                    throw new VisualizerBaseController.PoolExhaustionException("Connection pool exhausted", sqlEx);
                }
                String msg = sqlEx.getMessage();
                if (msg != null && (msg.contains("schema") || msg.contains("Schema"))) {
                    throw new VisualizerBaseController.NoRunIdException("Run ID not found: " + runId);
                }
            }
            // Other runtime errors - wrap to provide better context
            throw new RuntimeException("Error retrieving organism tick range for runId: " + runId, e);
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


