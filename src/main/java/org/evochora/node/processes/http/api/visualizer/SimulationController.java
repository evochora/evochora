package org.evochora.node.processes.http.api.visualizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.utils.protobuf.ProtobufConverter;
import org.evochora.node.processes.http.api.pipeline.dto.ErrorResponseDto;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.MoleculeTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
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
        
        LOGGER.debug("Registering simulation endpoint: metadata={}", metadataPath);
        
        app.get(metadataPath, this::getMetadata);
        
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
    @OpenApi(
        path = "metadata",
        methods = {HttpMethod.GET},
        summary = "Get simulation metadata",
        description = "Returns simulation metadata including world shape, energy strategies, and initial organism setup",
        tags = {"visualizer / simulation"},
        queryParams = {
            @OpenApiParam(name = "runId", description = "Optional simulation run ID (defaults to latest run)", required = false)
        },
        responses = {
            @OpenApiResponse(
                status = "200", 
                description = "Ok (Returns JSON representation of SimulationMetadata protobuf, including environment properties, and program artifacts)",
                content = @OpenApiContent(
                    from = Map.class,
                    example = """
                        JSON:
                        {
                          "simulationRunId": "2025100614302512-550e8400-e29b-41d4-a716-446655440000",
                          
                          "environment": {
                            "dimensions": 2,
                            "shape": [800, 600],
                            "toroidal": [true, true]
                          },
                          "plugins": [
                            {
                              "pluginClass": "org.evochora.runtime.worldgen.GeyserCreator",
                              "configJson": "{\\"geyserCount\\": 5, \\"energyPerTick\\": 10}"
                            }
                          ],
                          "programs": [],
                          "initialOrganisms": [
                            {
                              "organismId": 1,
                              "programId": "main",
                              "position": [375, 285],
                              "initialEnergy": 10000
                            }
                          ],
                          "userMetadata": {
                            "experiment": "test-run",
                            "version": "1.0"
                          },
                          "resolvedConfigJson": "{}",
                          "samplingInterval": 1
                        }
                        """
                )
            ),
            @OpenApiResponse(status = "304", description = "Not Modified (cached response, ETag matches)"),
            @OpenApiResponse(status = "400", description = "Bad request (invalid parameters)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "404", description = "Not found (run ID or metadata not found)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "429", description = "Too many requests (connection pool exhausted)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "500", description = "Internal server error (database error)", content = @OpenApiContent(from = ErrorResponseDto.class))
        }
    )
    void getMetadata(final Context ctx) throws SQLException {
        // Resolve run ID (query parameter → latest)
        final String runId = resolveRunId(ctx);
        
        LOGGER.debug("Retrieving simulation metadata: runId={}", runId);
        
        // Parse cache configuration
        final CacheConfig cacheConfig = CacheConfig.fromConfig(options, "metadata");
        
        // Generate ETag: only runId (endpoint path identifies resource, so _metadata suffix is redundant)
        final String etag = "\"" + runId + "\"";
        
        // Apply cache headers (may return 304 Not Modified if ETag matches)
        if (applyCacheHeaders(ctx, cacheConfig, etag)) {
            // 304 Not Modified was sent - return early (skip database query)
            return;
        }
        
        // Query database for metadata
        try (final IDatabaseReader reader = databaseProvider.createReader(runId)) {
            final SimulationMetadata metadata = reader.getMetadata();
            
            // Convert Protobuf to JSON and add type mappings for client-side ID resolution
            final String metadataJson = ProtobufConverter.toJson(metadata);
            final String jsonString = addTypeMappings(metadataJson);
            
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
     * Adds type mappings and expands resolvedConfigJson to the metadata JSON.
     * <p>
     * This method enriches the protobuf-serialized metadata for client consumption:
     * <ul>
     *   <li>Extracts environment, samplingInterval from resolvedConfigJson to top-level</li>
     *   <li>moleculeTypes: {0: "CODE", 1: "DATA", 2: "ENERGY", 3: "STRUCTURE"}</li>
     *   <li>opcodes: {0x00: "NOP", 0x10: "ADD", ...}</li>
     * </ul>
     *
     * @param metadataJson The original metadata JSON string.
     * @return JSON string with added type mappings and expanded config.
     */
    private String addTypeMappings(final String metadataJson) {
        try {
            final ObjectMapper mapper = new ObjectMapper();
            final ObjectNode root = (ObjectNode) mapper.readTree(metadataJson);

            // Expand resolvedConfigJson into top-level fields for API backward compatibility
            if (root.has("resolvedConfigJson")) {
                final String configJson = root.get("resolvedConfigJson").asText();
                if (configJson != null && !configJson.isEmpty()) {
                    final ObjectNode configNode = (ObjectNode) mapper.readTree(configJson);

                    // Extract environment to top level
                    if (configNode.has("environment")) {
                        root.set("environment", configNode.get("environment"));
                    }

                    // Extract samplingInterval to top level
                    if (configNode.has("samplingInterval")) {
                        root.set("samplingInterval", configNode.get("samplingInterval"));
                    }
                }
            }

            // Ensure instruction set is initialized before getting opcodes
            Instruction.init();

            // Add molecule type mappings (id -> name)
            final ObjectNode moleculeTypes = mapper.createObjectNode();
            for (Map.Entry<Integer, String> entry : MoleculeTypeRegistry.getAllTypes().entrySet()) {
                moleculeTypes.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            root.set("moleculeTypes", moleculeTypes);

            // Add opcode mappings (id -> name)
            final ObjectNode opcodes = mapper.createObjectNode();
            for (Map.Entry<Integer, String> entry : Instruction.getAllInstructions().entrySet()) {
                opcodes.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            root.set("opcodes", opcodes);

            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            // If JSON manipulation fails, return original (shouldn't happen)
            LOGGER.warn("Failed to add type mappings to metadata JSON", e);
            return metadataJson;
        }
    }

}

