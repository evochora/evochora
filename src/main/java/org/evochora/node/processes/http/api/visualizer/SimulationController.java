package org.evochora.node.processes.http.api.visualizer;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import io.javalin.config.RoutesConfig;
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
import org.evochora.runtime.model.Topology;
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
    public void registerRoutes(final RoutesConfig routes, final String basePath) {
        final String metadataPath = (basePath + "/metadata").replaceAll("//", "/");
        
        LOGGER.debug("Registering simulation endpoint: metadata={}", metadataPath);
        
        routes.get(metadataPath, this::getMetadata);
        
        // Setup common exception handlers from base class
        setupExceptionHandlers(routes);
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
        description = "Returns a run's metadata: its compiled programs and resolved configuration, with the environment and the sampling interval lifted out of it, and the names of the molecule types and opcodes",
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
                          "simulationRunId": "20251006-14302512-550e8400-e29b-41d4-a716-446655440000",
                          "startTimeMs": "1759761025120",
                          "initialSeed": "42",
                          "programs": [],
                          "resolvedConfigJson": "{}",
                          "buildRevision": "e681924a0c3d5b9e7f1a2b4c6d8e0f1a3b5c7d9e",
                          "environment": {
                            "shape": [2048, 1152],
                            "topology": "TORUS"
                          },
                          "samplingInterval": 1,
                          "moleculeTypes": {"0": "CODE", "1048576": "DATA"},
                          "moleculeTypeShift": 20,
                          "opcodes": {"0": "NOP"}
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
            if (e.getCause() instanceof SQLException sqlEx) {
                if (isPoolExhaustion(sqlEx)) {
                    throw new VisualizerBaseController.PoolExhaustionException("Connection pool exhausted", sqlEx);
                }
                if (isSchemaNotFound(sqlEx)) {
                    throw new VisualizerBaseController.NoRunIdException("Run ID not found: " + runId);
                }
            }
            // Other runtime errors - wrap to provide better context
            throw new RuntimeException("Error retrieving metadata for runId: " + runId, e);
        } catch (SQLException e) {
            if (isSchemaNotFound(e)) {
                throw new VisualizerBaseController.NoRunIdException("Run ID not found: " + runId);
            }
            throw e;
        }
    }
    
    /**
     * Adds type mappings and expands resolvedConfigJson to the metadata JSON.
     * <p>
     * This method enriches the protobuf-serialized metadata for client consumption:
     * <ul>
     *   <li>Extracts environment, samplingInterval from resolvedConfigJson to top-level, the
     *       environment's topology under its {@link Topology} name</li>
     *   <li>moleculeTypes: the molecule type constants of {@code Config} to their names. The keys
     *       carry the type bits at their position within a packed molecule and are therefore
     *       multiples of {@code 1 << Config.TYPE_SHIFT}, with CODE at 0. Cell responses report {@code moleculeType} on the same scale.</li>
     *   <li>moleculeTypeShift: {@code Config.TYPE_SHIFT}, the bit position of the type inside a
     *       packed molecule. A client that holds a raw type index shifts it by this amount to
     *       obtain the key of the {@code moleculeTypes} map.</li>
     *   <li>opcodes: the opcode IDs to the names of their instructions.</li>
     * </ul>
     *
     * @param metadataJson The original metadata JSON string.
     * @return JSON string with added type mappings and expanded config.
     * @throws IllegalArgumentException if the environment names no topology {@link Topology} reads
     */
    private String addTypeMappings(final String metadataJson) {
        final ObjectMapper mapper = new ObjectMapper();
        final ObjectNode root = (ObjectNode) mapper.readTree(metadataJson);

        // Expand resolvedConfigJson into top-level fields for API backward compatibility
        if (root.has("resolvedConfigJson")) {
            final String configJson = root.get("resolvedConfigJson").asString();
            if (configJson != null && !configJson.isEmpty()) {
                final ObjectNode configNode = (ObjectNode) mapper.readTree(configJson);

                // Extract environment to top level, its topology under the name Topology gives it,
                // so that the client compares against one spelling only
                if (configNode.has("environment")) {
                    final ObjectNode environment = (ObjectNode) configNode.get("environment");
                    environment.put("topology", Topology.parse(environment.get("topology").asString()).name());
                    root.set("environment", environment);
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
        root.put("moleculeTypeShift", org.evochora.runtime.Config.TYPE_SHIFT);

        // Add opcode mappings (id -> name)
        final ObjectNode opcodes = mapper.createObjectNode();
        for (Map.Entry<Integer, String> entry : Instruction.getAllInstructions().entrySet()) {
            opcodes.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        root.set("opcodes", opcodes);

        return mapper.writeValueAsString(root);
    }

}

