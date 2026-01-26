package org.evochora.node.processes.http.api.visualizer;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.evochora.datapipeline.api.contracts.CellHttpResponse;
import org.evochora.datapipeline.api.contracts.EnvironmentHttpResponse;
import org.evochora.datapipeline.api.contracts.MinimapData;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.delta.ChunkCorruptedException;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.MetadataNotFoundException;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
import org.evochora.datapipeline.api.resources.database.dto.SpatialRegion;
import org.evochora.datapipeline.api.resources.database.dto.TickRange;
import org.evochora.datapipeline.utils.MoleculeDataUtils;
import org.evochora.datapipeline.utils.delta.DeltaCodec;
import org.evochora.node.processes.http.api.pipeline.dto.ErrorResponseDto;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.typesafe.config.Config;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

import com.google.protobuf.ByteString;

/**
 * HTTP controller for environment data visualization.
 * <p>
 * Provides REST API endpoints for retrieving environment cell data from the database.
 * Supports spatial filtering and run ID resolution for multi-simulation environments.
 * <p>
 * <strong>Delta Compression:</strong> Environment data is stored as chunks containing
 * a snapshot plus deltas. This controller implements a Caffeine LRU cache to optimize
 * sequential tick access (e.g., scrubbing through ticks in the visualizer).
 * <p>
 * <strong>Decompression Strategy:</strong> Chunks are cached as-is and decompressed
 * on-demand for each request using {@code DeltaCodec.decompressTick()}. This provides:
 * <ul>
 *   <li>~50-100ms savings on cache hit (avoids DB load)</li>
 *   <li>Memory-efficient caching (compressed chunks)</li>
 *   <li>Simple cache key: runId + firstTick</li>
 * </ul>
 * <p>
 * Key features:
 * <ul>
 *   <li>Chunk caching with Caffeine LRU cache</li>
 *   <li>Spatial region filtering (2D/3D coordinates)</li>
 *   <li>Run ID resolution (query parameter → latest run)</li>
 *   <li>HTTP cache headers for immutable past ticks</li>
 *   <li>Comprehensive error handling (400/404/500)</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong> This controller is thread-safe. The Caffeine caches
 * (chunks, environment properties) are thread-safe. {@code DeltaCodec.Decoder} instances
 * are created per-request (not cached) because they maintain mutable internal state that
 * is not thread-safe for concurrent access.
 */
public class EnvironmentController extends VisualizerBaseController {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnvironmentController.class);
    
    /**
     * Guard for one-time instruction set initialization.
     */
    private static final AtomicBoolean INSTRUCTION_INITIALIZED = new AtomicBoolean(false);
    
    /**
     * LRU cache for TickDataChunks.
     * <p>
     * Key format: "runId:firstTick" where firstTick is the snapshot tick number.
     * Value: The raw TickDataChunk (compressed form).
     */
    private final Cache<String, TickDataChunk> chunkCache;
    
    /**
     * Cache for environment properties per runId.
     * <p>
     * Used to avoid repeated metadata lookups and for Decoder construction.
     */
    private final Cache<String, EnvironmentProperties> envPropsCache;

    /**
     * Aggregator for generating minimap data from environment cells.
     */
    private final MinimapAggregator minimapAggregator;

    /**
     * Constructs a new EnvironmentController with chunk caching.
     * <p>
     * Cache configuration is read from options:
     * <ul>
     *   <li>{@code chunk-cache.maximum-size} - Maximum number of chunks to cache (default: 100)</li>
     *   <li>{@code chunk-cache.expire-after-access} - Expiration time in seconds (default: 300)</li>
     * </ul>
     *
     * @param registry The central service registry for accessing shared services.
     * @param options  The HOCON configuration specific to this controller instance.
     */
    public EnvironmentController(final org.evochora.node.spi.ServiceRegistry registry, final Config options) {
        super(registry, options);
        
        // Read chunk cache configuration (server-side Caffeine cache)
        int maxSize = options.hasPath("chunk-cache.maximum-size") 
            ? options.getInt("chunk-cache.maximum-size") 
            : 100;
        int expireAfterAccessSeconds = options.hasPath("chunk-cache.expire-after-access") 
            ? options.getInt("chunk-cache.expire-after-access") 
            : 300;
        
        // Build chunk cache
        this.chunkCache = Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterAccess(Duration.ofSeconds(expireAfterAccessSeconds))
            .recordStats()  // Enable stats for monitoring
            .build();
        
        // Build environment properties cache (small, long TTL)
        this.envPropsCache = Caffeine.newBuilder()
            .maximumSize(50)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();
        
        // Note: Decoders are NOT cached because they are not thread-safe.
        // Each request creates its own Decoder instance to avoid concurrent
        // modification of internal state (MutableCellState, currentChunk, currentTick).

        // Minimap aggregator is stateless and thread-safe
        this.minimapAggregator = new MinimapAggregator();

        LOGGER.info("EnvironmentController chunk cache initialized: maxSize={}, expireAfterAccess={}s",
            maxSize, expireAfterAccessSeconds);
    }

    @Override
    public void registerRoutes(final Javalin app, final String basePath) {
        final String tickPath = (basePath + "/{tick}").replaceAll("//", "/");
        final String ticksPath = (basePath + "/ticks").replaceAll("//", "/");
        
        LOGGER.debug("Registering environment endpoints: tick={}, ticks={}", tickPath, ticksPath);
        
        // IMPORTANT: Register /ticks BEFORE /{tick} to avoid path parameter conflict
        // Javalin matches routes in registration order, so /ticks must come first
        app.get(ticksPath, this::getTicks);
        app.get(tickPath, this::getEnvironment);
        
        // Setup common exception handlers from base class
        setupExceptionHandlers(app);
    }

    /**
     * Handles GET requests for environment data at a specific tick.
     * <p>
     * Route: GET /{tick}?region=x1,x2,y1,y2&runId=...
     * <p>
     * This method uses a chunk cache to optimize sequential tick access.
     * On cache miss, it loads the chunk from the database and caches it.
     * Decompression to the specific tick is performed on each request.
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
     *   "cells": [
     *     {"coordinates": [5, 10], "moleculeType": "ENERGY", "moleculeValue": 255, "ownerId": 7}
     *   ]
     * }
     * </pre>
     *
     * @param ctx The Javalin context containing request and response data.
     * @throws IllegalArgumentException if tick parameter is invalid
     * @throws VisualizerBaseController.NoRunIdException if no run ID is available
     * @throws SQLException if database operation fails
     * @throws TickNotFoundException if the tick does not exist
     */
    @OpenApi(
        path = "{tick}",
        methods = {HttpMethod.GET},
        summary = "Get environment data at a specific tick",
        description = "Returns environment cell data for a specific tick with optional spatial region filtering",
        tags = {"visualizer / environment"},
        pathParams = {
            @OpenApiParam(name = "tick", description = "The tick number", required = true, type = Long.class)
        },
        queryParams = {
            @OpenApiParam(name = "region", description = "Optional spatial region as comma-separated bounds (e.g., \"0,100,0,100\")", required = false),
            @OpenApiParam(name = "runId", description = "Optional simulation run ID (defaults to latest run)", required = false),
            @OpenApiParam(name = "minimap", description = "Include minimap data in response (presence of parameter enables)", required = false)
        },
        responses = {
            @OpenApiResponse(status = "200", description = "OK (application/x-protobuf binary)", content = @OpenApiContent(from = byte[].class)),
            @OpenApiResponse(status = "304", description = "Not Modified (cached response, ETag matches)"),
            @OpenApiResponse(status = "400", description = "Bad request (invalid tick or region format)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "404", description = "Not found (tick or run ID not found)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "429", description = "Too many requests (connection pool exhausted)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "500", description = "Internal server error (database error)", content = @OpenApiContent(from = ErrorResponseDto.class))
        }
    )
    void getEnvironment(final Context ctx) throws SQLException, TickNotFoundException {
        final long totalStartNs = System.nanoTime();
        
        // Parse and validate tick parameter
        final long tickNumber = parseTickNumber(ctx.pathParam("tick"));
        
        // Parse region parameter (optional)
        final String regionParam = ctx.queryParam("region");
        final SpatialRegion region = parseRegion(regionParam);

        // Parse minimap parameter (optional - presence enables minimap)
        final boolean includeMinimap = ctx.queryParam("minimap") != null;

        // Resolve run ID (query parameter → latest)
        final String runId = resolveRunId(ctx);

        LOGGER.debug("Retrieving environment data: tick={}, runId={}, region={}, minimap={}",
            tickNumber, runId, region, includeMinimap);
        
        // Parse cache configuration
        final CacheConfig cacheConfig = CacheConfig.fromConfig(options, "environment");
        
        // Generate ETag: only runId (tick is already in URL path, so redundant in ETag)
        final String etag = "\"" + runId + "\"";
        
        // Apply cache headers (may return 304 Not Modified if ETag matches)
        if (applyCacheHeaders(ctx, cacheConfig, etag)) {
            // 304 Not Modified was sent - return early (skip database query)
            return;
        }
        
        try {
            // --- Timing: Data Loading ---
            final long loadStartNs = System.nanoTime();
            
            // Get or load environment properties (cached)
            final EnvironmentProperties envProps = getOrLoadEnvProps(runId);
            
            // Get or load chunk (cached) - this is typically the bottleneck
            final long chunkStartNs = System.nanoTime();
            final TickDataChunk chunk = getOrLoadChunk(runId, tickNumber);
            final long chunkLoadTimeMs = (System.nanoTime() - chunkStartNs) / 1_000_000;
            
            // Create decoder for this request (NOT cached - Decoder is not thread-safe)
            final DeltaCodec.Decoder decoder = new DeltaCodec.Decoder(envProps);
            
            final long loadTimeMs = (System.nanoTime() - loadStartNs) / 1_000_000;
            
            // --- Timing: Decompression ---
            final long decompressStartNs = System.nanoTime();
            
            // Decompress to get the specific tick
            final TickData tickData;
            try {
                tickData = decoder.decompressTick(chunk, tickNumber);
            } catch (ChunkCorruptedException e) {
                throw new SQLException("Corrupted chunk for tick " + tickNumber + ": " + e.getMessage(), e);
            }
            
            final long decompressTimeMs = (System.nanoTime() - decompressStartNs) / 1_000_000;
            
            // --- Timing: Transform ---
            final long transformStartNs = System.nanoTime();
            
            // Ensure instruction set is initialized before resolving opcode names
            ensureInstructionSetInitialized();

            // Convert to Protobuf response format (using IDs instead of strings)
            final EnvironmentHttpResponse response = convertTickDataToProtobuf(
                    tickData, tickNumber, region, envProps, includeMinimap);
            final int cellCount = response.getCellsCount();
            
            final long transformTimeMs = (System.nanoTime() - transformStartNs) / 1_000_000;
            
            // --- Timing: Serialization ---
            final long serializeStartNs = System.nanoTime();
            
            // Serialize to Protobuf binary
            final byte[] responseBytes = response.toByteArray();
            
            final long serializeTimeMs = (System.nanoTime() - serializeStartNs) / 1_000_000;
            final long totalTimeMs = (System.nanoTime() - totalStartNs) / 1_000_000;
            
            // Add timing headers (X-Timing-Db-Ms is the DB query portion of Load)
            ctx.header("X-Timing-Load-Ms", String.valueOf(loadTimeMs));
            ctx.header("X-Timing-Db-Ms", String.valueOf(chunkLoadTimeMs));
            ctx.header("X-Timing-Decompress-Ms", String.valueOf(decompressTimeMs));
            ctx.header("X-Timing-Transform-Ms", String.valueOf(transformTimeMs));
            ctx.header("X-Timing-Serialize-Ms", String.valueOf(serializeTimeMs));
            ctx.header("X-Timing-Total-Ms", String.valueOf(totalTimeMs));
            ctx.header("X-Cell-Count", String.valueOf(cellCount));
            // Note: Content-Length is intentionally NOT set here.
            // Jetty's GzipHandler compresses the response, so setting Content-Length
            // to the uncompressed size would cause HTTP/2 stream errors.
            
            // Return Protobuf binary
            ctx.contentType("application/x-protobuf");
            ctx.status(HttpStatus.OK).result(responseBytes);
            
        } catch (RuntimeException e) {
            handleDatabaseException(e, runId);
        }
    }
    
    /**
     * Gets or loads environment properties for a run (cached).
     */
    private EnvironmentProperties getOrLoadEnvProps(final String runId) throws SQLException {
        return envPropsCache.get(runId, key -> {
            try (final IDatabaseReader reader = databaseProvider.createReader(runId)) {
                final SimulationMetadata metadata = reader.getMetadata();
                return extractEnvironmentProperties(metadata);
            } catch (SQLException | MetadataNotFoundException e) {
                throw new RuntimeException("Failed to load environment properties for " + runId, e);
            }
        });
    }
    
    /**
     * Gets or loads the chunk containing the specified tick (cached).
     * <p>
     * The cache key is "runId:firstTick" where firstTick is the snapshot tick of the chunk.
     * Since we don't know the firstTick before loading, we first check if any cached chunk
     * contains the requested tick, otherwise we load from the database.
     */
    private TickDataChunk getOrLoadChunk(final String runId, final long tickNumber) throws SQLException, TickNotFoundException {
        // Try to find a cached chunk that contains this tick
        // This is a simple linear scan - acceptable for small cache sizes
        for (var entry : chunkCache.asMap().entrySet()) {
            if (entry.getKey().startsWith(runId + ":")) {
                TickDataChunk cached = entry.getValue();
                if (chunkContainsTick(cached, tickNumber)) {
                    LOGGER.debug("Chunk cache hit: runId={}, tick={}", runId, tickNumber);
                    return cached;
                }
            }
        }
        
        // Cache miss - load from database
        LOGGER.debug("Chunk cache miss: runId={}, tick={}", runId, tickNumber);
        
        try (final IDatabaseReader reader = databaseProvider.createReader(runId)) {
            final TickDataChunk chunk = reader.readChunkContaining(tickNumber);
            
            // Cache with key "runId:firstTick"
            final long firstTick = chunk.getSnapshot().getTickNumber();
            final String cacheKey = runId + ":" + firstTick;
            chunkCache.put(cacheKey, chunk);
            
            return chunk;
        } catch (RuntimeException e) {
            // Check if this is a schema/runId error
            if (e.getCause() instanceof SQLException) {
                SQLException sqlEx = (SQLException) e.getCause();
                if (sqlEx.getMessage() != null && 
                    (sqlEx.getMessage().contains("schema") || sqlEx.getMessage().contains("Schema"))) {
                    throw new VisualizerBaseController.NoRunIdException("Run ID not found: " + runId);
                }
            }
            throw e;
        }
    }
    
    /**
     * Checks if a chunk contains the specified tick number.
     */
    private boolean chunkContainsTick(final TickDataChunk chunk, final long tickNumber) {
        if (!chunk.hasSnapshot()) {
            return false;
        }
        
        final long firstTick = chunk.getSnapshot().getTickNumber();
        final long lastTick;
        if (chunk.getDeltasCount() > 0) {
            lastTick = chunk.getDeltas(chunk.getDeltasCount() - 1).getTickNumber();
        } else {
            lastTick = firstTick;
        }
        
        return tickNumber >= firstTick && tickNumber <= lastTick;
    }
    
    /**
     * Converts TickData to Protobuf EnvironmentHttpResponse, with optional region filtering and minimap.
     * <p>
     * Uses numeric IDs instead of string names for molecule types and opcodes.
     * Clients resolve IDs to names using mappings from the metadata endpoint.
     *
     * @param tickData The tick data containing cell information.
     * @param tickNumber The tick number for this response.
     * @param region Optional region filter (null for all cells).
     * @param envProps Environment properties for coordinate conversion.
     * @param includeMinimap If true, generate and include minimap data in response.
     * @return Protobuf response ready for serialization.
     */
    private EnvironmentHttpResponse convertTickDataToProtobuf(final TickData tickData,
                                                               final long tickNumber,
                                                               final SpatialRegion region,
                                                               final EnvironmentProperties envProps,
                                                               final boolean includeMinimap) {
        final var cellColumns = tickData.getCellColumns();
        final int cellCount = cellColumns.getFlatIndicesCount();
        final int dimensions = envProps.getDimensions();

        final EnvironmentHttpResponse.Builder responseBuilder = EnvironmentHttpResponse.newBuilder()
                .setTickNumber(tickNumber)
                .setTotalCells(envProps.getTotalCells());

        for (int i = 0; i < cellCount; i++) {
            final int flatIndex = cellColumns.getFlatIndices(i);
            final int[] coords = envProps.flatIndexToCoordinates(flatIndex);

            // Filter by region if provided
            if (region != null && !isInRegion(coords, region, dimensions)) {
                continue;
            }

            final int moleculeInt = cellColumns.getMoleculeData(i);
            final int moleculeType = moleculeInt & org.evochora.runtime.Config.TYPE_MASK;
            final int moleculeValue = MoleculeDataUtils.extractSignedValue(moleculeInt);
            final int marker = (moleculeInt & org.evochora.runtime.Config.MARKER_MASK)
                    >> org.evochora.runtime.Config.MARKER_SHIFT;
            final int ownerId = cellColumns.getOwnerIds(i);

            // Build cell with IDs (not string names)
            final CellHttpResponse.Builder cellBuilder = CellHttpResponse.newBuilder()
                    .setMoleculeType(moleculeType)
                    .setMoleculeValue(moleculeValue)
                    .setOwnerId(ownerId)
                    .setMarker(marker);

            // Add coordinates
            for (int coord : coords) {
                cellBuilder.addCoordinates(coord);
            }

            // For CODE molecules, include opcode ID (which equals moleculeValue)
            if (moleculeType == org.evochora.runtime.Config.TYPE_CODE) {
                cellBuilder.setOpcodeId(moleculeValue);
            } else {
                cellBuilder.setOpcodeId(-1);  // Not a CODE molecule
            }

            responseBuilder.addCells(cellBuilder.build());
        }

        // Generate minimap if requested
        if (includeMinimap) {
            final var minimapResult = minimapAggregator.aggregate(cellColumns, envProps);
            if (minimapResult != null) {
                responseBuilder.setMinimap(MinimapData.newBuilder()
                        .setWidth(minimapResult.width())
                        .setHeight(minimapResult.height())
                        .setCellTypes(ByteString.copyFrom(minimapResult.cellTypes()))
                        .build());
            }
        }

        return responseBuilder.build();
    }
    
    /**
     * Checks if coordinates are within the specified region.
     */
    private boolean isInRegion(final int[] coords, final SpatialRegion region, final int dimensions) {
        final int[] bounds = region.bounds;
        
        if (dimensions == 2) {
            final int xMin = bounds[0], xMax = bounds[1];
            final int yMin = bounds[2], yMax = bounds[3];
            return coords[0] >= xMin && coords[0] <= xMax && 
                   coords[1] >= yMin && coords[1] <= yMax;
        } else {
            for (int d = 0; d < dimensions; d++) {
                final int min = bounds[d * 2];
                final int max = bounds[d * 2 + 1];
                if (coords[d] < min || coords[d] > max) {
                    return false;
                }
            }
            return true;
        }
    }
    
    /**
     * Extracts environment properties from metadata.
     */
    private EnvironmentProperties extractEnvironmentProperties(final SimulationMetadata metadata) {
        final var envConfig = metadata.getEnvironment();
        
        final int[] shape = new int[envConfig.getShapeCount()];
        for (int i = 0; i < envConfig.getShapeCount(); i++) {
            shape[i] = envConfig.getShape(i);
        }
        
        final boolean isToroidal = envConfig.getToroidalCount() > 0 && envConfig.getToroidal(0);
        
        return new EnvironmentProperties(shape, isToroidal);
    }
    
    /**
     * Ensures the instruction set is initialized (one-time, thread-safe).
     */
    private static void ensureInstructionSetInitialized() {
        if (INSTRUCTION_INITIALIZED.compareAndSet(false, true)) {
            Instruction.init();
        }
    }
    
    /**
     * Handles database exceptions with appropriate error mapping.
     */
    private void handleDatabaseException(final RuntimeException e, final String runId) throws SQLException {
        if (e.getCause() instanceof SQLException sqlEx) {
            final String msg = sqlEx.getMessage();
            
            if (msg != null) {
                final String lowerMsg = msg.toLowerCase();
                
                // Check for schema errors FIRST
                if (msg.contains("schema") || msg.contains("Schema")) {
                    throw new VisualizerBaseController.NoRunIdException("Run ID not found: " + runId);
                }
                
                // Check for connection pool timeout/exhaustion
                if (lowerMsg.contains("timeout") || 
                    lowerMsg.contains("connection is not available") ||
                    lowerMsg.contains("connection pool")) {
                    throw new VisualizerBaseController.PoolExhaustionException("Connection pool exhausted or timeout", sqlEx);
                }
            }
        }
        throw new RuntimeException("Error retrieving environment data for runId: " + runId, e);
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

    /**
     * Handles GET requests for the tick range of indexed environment data.
     * <p>
     * Route: GET /visualizer/api/environment/ticks?runId=...
     * <p>
     * Returns the minimum and maximum tick numbers that have been indexed by the EnvironmentIndexer.
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
        summary = "Get environment tick range",
        description = "Returns the minimum and maximum tick numbers that have been indexed by the EnvironmentIndexer. This represents the ticks available in the database, not the actual simulation tick range.",
        tags = {"visualizer / environment"},
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
        
        LOGGER.debug("Retrieving environment tick range: runId={}", runId);
        
        // Parse cache configuration
        final CacheConfig cacheConfig = CacheConfig.fromConfig(options, "ticks");
        
        // Query database for tick range (needed for ETag generation if useETag=true)
        try (final IDatabaseReader reader = databaseProvider.createReader(runId)) {
            final TickRange tickRange = reader.getTickRange();
            
            if (tickRange == null) {
                // No ticks available - return 404
                throw new VisualizerBaseController.NoRunIdException("No environment ticks available for run: " + runId);
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
            // createReader throws RuntimeException if setSchema fails (schema doesn't exist)
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("Failed to create reader")) {
                // This is likely a schema error - treat as 404
                throw new VisualizerBaseController.NoRunIdException("Run ID not found: " + runId);
            }
            
            if (e.getCause() instanceof SQLException) {
                SQLException sqlEx = (SQLException) e.getCause();
                String msg = sqlEx.getMessage();
                
                if (msg != null) {
                    String lowerMsg = msg.toLowerCase();
                    
                    // Check for schema errors FIRST (before pool exhaustion)
                    if (msg.contains("schema") || msg.contains("Schema") || 
                        msg.contains("not found") || msg.contains("does not exist")) {
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
            throw new RuntimeException("Error retrieving environment tick range for runId: " + runId, e);
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
