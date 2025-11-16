package org.evochora.node.processes.http.api.visualizer;

import com.typesafe.config.Config;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.OrganismNotFoundException;
import org.evochora.datapipeline.api.resources.database.OrganismTickDetails;
import org.evochora.datapipeline.api.resources.database.OrganismTickSummary;
import org.evochora.datapipeline.api.resources.database.TickRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        LOGGER.debug("Registering organism endpoints: list={}, detail={}", listPath, detailPath);

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
    void getOrganismsAtTick(final Context ctx) throws SQLException {
        final long tickNumber = parseTickNumber(ctx.pathParam("tick"));
        final String runId = resolveRunId(ctx);

        LOGGER.debug("Retrieving organisms for tick={} runId={}", tickNumber, runId);

        // Parse cache configuration (separate namespace "organisms")
        final CacheConfig cacheConfig = CacheConfig.fromConfig(options, "organisms");

        try (final IDatabaseReader reader = databaseProvider.createReader(runId)) {
            // Validate tick range first to distinguish 404 (out of range) from empty list
            final TickRange tickRange = reader.getTickRange();
            if (tickRange == null || tickNumber < tickRange.minTick() || tickNumber > tickRange.maxTick()) {
                throw new NoRunIdException("Tick " + tickNumber + " is outside available range for run: " + runId);
            }

            // Generate ETag: "runId_tick"
            final String etag = "\"" + runId + "_" + tickNumber + "\"";

            // Apply cache headers (may return 304 Not Modified if ETag matches)
            if (applyCacheHeaders(ctx, cacheConfig, etag)) {
                return;
            }

            final List<OrganismTickSummary> organisms = reader.readOrganismsAtTick(tickNumber);

            final Map<String, Object> response = new HashMap<>();
            response.put("runId", runId);
            response.put("tick", tickNumber);
            response.put("organisms", organisms);

            ctx.status(HttpStatus.OK).json(response);
        } catch (RuntimeException e) {
            // Check for schema / connection issues analogous to Environment/SimulationController
            if (e.getCause() instanceof SQLException) {
                final SQLException sqlEx = (SQLException) e.getCause();
                final String msg = sqlEx.getMessage();

                if (msg != null) {
                    final String lowerMsg = msg.toLowerCase();

                    if (msg.contains("schema") || msg.contains("Schema")) {
                        throw new NoRunIdException("Run ID not found: " + runId);
                    }

                    if (lowerMsg.contains("timeout")
                            || lowerMsg.contains("connection is not available")
                            || lowerMsg.contains("connection pool")) {
                        throw new PoolExhaustionException("Connection pool exhausted or timeout", sqlEx);
                    }
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
    void getOrganismDetails(final Context ctx) throws SQLException {
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

            final Map<String, Object> response = new HashMap<>();
            response.put("runId", runId);
            response.put("tick", details.tick);
            response.put("organismId", details.organismId);
            response.put("static", details.staticInfo);
            response.put("state", details.state);

            ctx.status(HttpStatus.OK).json(response);
        } catch (OrganismNotFoundException e) {
            // Specific 404 for missing organism or tick row
            throw new NoRunIdException("Organism " + organismId + " not found at tick " + tickNumber
                    + " for run: " + runId, e);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof SQLException) {
                final SQLException sqlEx = (SQLException) e.getCause();
                final String msg = sqlEx.getMessage();

                if (msg != null) {
                    final String lowerMsg = msg.toLowerCase();

                    if (msg.contains("schema") || msg.contains("Schema")) {
                        throw new NoRunIdException("Run ID not found: " + runId);
                    }

                    if (lowerMsg.contains("timeout")
                            || lowerMsg.contains("connection is not available")
                            || lowerMsg.contains("connection pool")) {
                        throw new PoolExhaustionException("Connection pool exhausted or timeout", sqlEx);
                    }
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
}


