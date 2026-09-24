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
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.MetadataNotFoundException;
import org.evochora.datapipeline.api.resources.database.OrganismNotFoundException;
import org.evochora.datapipeline.api.resources.database.dto.LineageMutations;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickDetails;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;
import org.evochora.datapipeline.api.resources.database.dto.TickRange;
import org.evochora.datapipeline.utils.MetadataConfigHelper;
import org.evochora.runtime.model.EnvironmentProperties;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import org.evochora.datapipeline.api.resources.database.dto.GenomeCarriers;
import org.evochora.node.processes.http.api.visualizer.dto.CladesResponseDto;
import java.util.LinkedHashMap;
import java.util.Map;
import org.evochora.node.processes.http.api.pipeline.dto.ErrorResponseDto;
import org.evochora.node.processes.http.api.visualizer.dto.OrganismDetailsResponseDto;
import org.evochora.node.processes.http.api.visualizer.dto.OrganismMutationsResponseDto;
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

    /** How many ticks a run is sampled at; see {@code clades.samples} in reference.conf. */
    private final int cladeSamples;

    /** Answers kept between requests, keyed by run and last sampled tick. */
    private final Cache<String, CladesResponseDto> answerCache;

    private static final Logger LOGGER = LoggerFactory.getLogger(OrganismController.class);

    /**
     * Constructs a new OrganismController.
     *
     * @param registry The central service registry for accessing shared services.
     * @param options  The HOCON configuration specific to this controller instance.
     */
    public OrganismController(final org.evochora.node.spi.ServiceRegistry registry, final Config options) {
        super(registry, options);

        this.cladeSamples = setting(options, "clades.samples", 60);
        this.answerCache = Caffeine.newBuilder()
            .maximumSize(setting(options, "clades.kept-answers", 4))
            .expireAfterAccess(Duration.ofSeconds(setting(options, "clades.keep-answers-for", 600)))
            .build();
    }

    /**
     * Reads a setting a controller is expected to have, and says so when it is missing.
     * <p>
     * Every setting of this controller is stated in reference.conf, so a missing one means the
     * configuration reaching it is not the one that was written - a controller declared without
     * an options block is handed an empty configuration. That is worth a word in the log: the
     * node keeps running on the documented value rather than failing to start, and whoever set
     * it up can see that their setting never arrived.
     *
     * @param options The controller's configuration
     * @param path The setting to read
     * @param documented The value reference.conf states for it
     * @return The configured value, or the documented one
     */
    private static int setting(final Config options, final String path, final int documented) {
        if (options.hasPath(path)) {
            return options.getInt(path);
        }
        LOGGER.warn("No configuration for '{}', using {} as stated in reference.conf", path, documented);
        return documented;
    }

    @Override
    public void registerRoutes(final Javalin app, final String basePath) {
        final String listPath = (basePath + "/{tick}").replaceAll("//", "/");
        final String detailPath = (basePath + "/{tick}/{organismId}").replaceAll("//", "/");
        final String mutationsPath = (basePath + "/{tick}/{organismId}/mutations").replaceAll("//", "/");
        final String ticksPath = (basePath + "/ticks").replaceAll("//", "/");
        final String cladesPath = (basePath + "/clades").replaceAll("//", "/");

        LOGGER.debug("Registering organism endpoints: list={}, detail={}, mutations={}, ticks={}, clades={}",
            listPath, detailPath, mutationsPath, ticksPath, cladesPath);

        // IMPORTANT: Register /ticks and /clades BEFORE /{tick} to avoid path parameter conflict
        // Javalin matches routes in registration order, so the named paths must come first
        app.get(ticksPath, this::getTicks);
        app.get(cladesPath, this::getClades);
        app.get(listPath, this::getOrganismsAtTick);
        app.get(detailPath, this::getOrganismDetails);
        app.get(mutationsPath, this::getOrganismMutations);

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
     *   "organisms": [ OrganismTickSummary... ],
     *   "totalOrganismCount": 4711,
     *   "genomeAncestors": { "genomeHash": "parentGenomeHash or null", ... }
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
    void getOrganismsAtTick(final Context ctx) throws SQLException, TickNotFoundException {
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

            final List<Long> genomes = organisms.stream().map(o -> o.genomeHash).toList();
            final Map<String, String> genomeAncestors = toStringMap(reader.readGenomeAncestors(genomes));

            ctx.status(HttpStatus.OK).json(new OrganismsResponseDto(organisms, totalOrganismCount, genomeAncestors));
        } catch (RuntimeException e) {
            handleDatabaseException(e, runId, "organisms");
        } catch (SQLException e) {
            if (isSchemaNotFound(e)) {
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
     *   "organismId": 1,
     *   "tick": 1234,
     *   "staticInfo": { ... },
     *   "state": { ... },
     *   "genomeAncestors": { "genomeHash": "parentGenomeHash or null", ... }
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
            @OpenApiResponse(status = "200", description = "OK", content = @OpenApiContent(from = OrganismDetailsResponseDto.class)),
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

            // The ancestry chain is coloured by genome, so the response carries the closure of the
            // genomes it names rather than relying on what the tick response happened to deliver.
            final List<Long> genomes = new ArrayList<>();
            details.lineage.forEach(entry -> genomes.add(entry.genomeHash()));
            final Map<String, String> genomeAncestors = toStringMap(reader.readGenomeAncestors(genomes));

            ctx.status(HttpStatus.OK).json(new OrganismDetailsResponseDto(
                details.organismId, details.tick, details.staticInfo, details.lineage,
                details.labelNamespaceMask, details.state, genomeAncestors));
        } catch (OrganismNotFoundException e) {
            throw e;
        } catch (RuntimeException e) {
            handleDatabaseException(e, runId, "organism details");
        } catch (SQLException e) {
            if (isSchemaNotFound(e)) {
                throw new NoRunIdException("Run ID not found: " + runId);
            }
            throw e;
        }
    }


    /**
     * Handles GET requests for the mutations of an organism's whole lineage.
     * <p>
     * Route: GET /visualizer/api/organisms/{tick}/{organismId}/mutations?runId=...
     * <p>
     * The answer does not depend on {@code {tick}} and the segment is not read: a mutation is
     * recorded once, at the birth of the organism that received it, and the answer is the same at
     * every tick of the run. The segment is there to keep this route apart from the two-segment
     * detail route {@code {tick}/{organismId}}.
     * <p>
     * Every event of the organism and of each of its ancestors is reported once, with the birth it
     * was recorded at as its origin. Cells carry absolute coordinates on the displayed body and
     * their molecules split into type and value; label values are moved into the displayed
     * organism's namespace by {@link LineageMutationTranslator}. Events without cells, such as the
     * label mask itself, are reported with an empty cell list.
     * <p>
     * Response format:
     * <pre>
     * {
     *   "organismId": 7,
     *   "events": [
     *     {
     *       "originOrganismId": 3, "originGeneration": 1, "originGenomeHash": "-4711",
     *       "originParentGenomeHash": "815", "originBirthTick": 120, "eventIndex": 0,
     *       "pluginClass": "org.evochora.runtime.worldgen.GeneSubstitutionPlugin",
     *       "kind": "substitution", "dv": [0, 1], "params": [],
     *       "cells": [
     *         {"coordinates": [12, 40],
     *          "before": {"moleculeType": 0, "moleculeValue": 5},
     *          "after": {"moleculeType": 0, "moleculeValue": 9}}
     *       ]
     *     }
     *   ]
     * }
     * </pre>
     *
     * @param ctx The Javalin context containing request and response data.
     * @throws IllegalArgumentException if the organismId is invalid
     * @throws NoRunIdException if no run ID is available
     * @throws OrganismNotFoundException if the organism is not indexed
     * @throws SQLException if database operations fail
     */
    @OpenApi(
        path = "{tick}/{organismId}/mutations",
        methods = {HttpMethod.GET},
        summary = "Get the mutations of an organism's lineage",
        description = "Returns every mutation the organism and its ancestors received at their births, placed on the organism's body. The tick segment is not read: the answer is the same at every tick.",
        tags = {"visualizer / organism"},
        pathParams = {
            @OpenApiParam(name = "tick", description = "Not read; keeps this route apart from the organism detail route", required = true, type = Long.class),
            @OpenApiParam(name = "organismId", description = "The organism ID", required = true, type = Integer.class)
        },
        queryParams = {
            @OpenApiParam(name = "runId", description = "Optional simulation run ID (defaults to latest run)", required = false)
        },
        responses = {
            @OpenApiResponse(status = "200", description = "OK", content = @OpenApiContent(from = OrganismMutationsResponseDto.class)),
            @OpenApiResponse(status = "304", description = "Not Modified (cached response, ETag matches)"),
            @OpenApiResponse(status = "400", description = "Bad request (invalid organismId)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "404", description = "Not found (organism or run ID not found)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "429", description = "Too many requests (connection pool exhausted)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "500", description = "Internal server error (database error)", content = @OpenApiContent(from = ErrorResponseDto.class))
        }
    )
    void getOrganismMutations(final Context ctx) throws SQLException, OrganismNotFoundException {
        final int organismId = parseOrganismId(ctx.pathParam("organismId"));
        final String runId = resolveRunId(ctx);

        LOGGER.debug("Retrieving lineage mutations: organismId={}, runId={}", organismId, runId);

        final CacheConfig cacheConfig = CacheConfig.fromConfig(options, "organismMutations");

        try (final IDatabaseReader reader = databaseProvider.createReader(runId)) {
            // The organism is enough to identify the answer once indexing has finished: the events
            // of a lineage are written once. While indexing runs, an ancestor's events can arrive
            // after the descendant's, which is why the cache is off by default.
            final String etag = "\"" + runId + "_" + organismId + "\"";

            if (applyCacheHeaders(ctx, cacheConfig, etag)) {
                return;
            }

            final List<LineageMutations> chain = reader.readLineageMutations(organismId);
            final SimulationMetadata metadata = reader.getMetadata();
            final EnvironmentProperties world = MetadataConfigHelper.environmentProperties(metadata);

            ctx.status(HttpStatus.OK).json(new OrganismMutationsResponseDto(
                organismId, LineageMutationTranslator.translate(chain, world)));
        } catch (OrganismNotFoundException e) {
            throw e;
        } catch (MetadataNotFoundException e) {
            throw new NoRunIdException("Metadata not found for run: " + runId, e);
        } catch (RuntimeException e) {
            handleDatabaseException(e, runId, "organism mutations");
        } catch (SQLException e) {
            if (isSchemaNotFound(e)) {
                throw new NoRunIdException("Run ID not found: " + runId);
            }
            throw e;
        }
    }

    /**
     * Converts a genome ancestor map to string keys and values.
     * <p>
     * Genome hashes are 64-bit and lose precision as JSON numbers, so they travel as strings.
     * A null value marks a root genome and is preserved as null.
     *
     * @param ancestors Genome hash to parent genome hash, null value for roots
     * @return The same mapping with string keys and values
     */
    /**
     * Handles GET requests for the descent of a run's genomes and the population at sampled ticks.
     * <p>
     * Route: GET /visualizer/api/organisms/clades?runId=...
     * <p>
     * Which ticks are sampled is decided here and not by the caller: they follow from the run's
     * recorded range, its interval and the configured number of samples. Two callers choosing
     * them for themselves would weigh the clades of a run differently and colour them
     * differently, which is why this is configuration and not a request parameter.
     * <p>
     * Answers what a view colouring by kinship needs before it knows which genomes it will ask
     * about: the whole tree, and the weight each line of descent carried over the run.
     *
     * @param ctx The Javalin context
     * @throws SQLException if database access fails
     */
    @OpenApi(
        path = "clades",
        methods = {HttpMethod.GET},
        summary = "Get the genome lineage of a run and the population at the given ticks",
        description = "Returns the genomes above the sampled ticks with their parents, and per "
                + "sampled tick how many organisms carried each. Genomes are named once and "
                + "referred to by index. The ticks sit on a grid that only grows at its end.",
        tags = {"visualizer / organism"},
        queryParams = {
            @OpenApiParam(name = "runId", description = "Optional simulation run ID (defaults to latest run)", required = false)
        },
        responses = {
            @OpenApiResponse(status = "200", description = "OK", content = @OpenApiContent(from = CladesResponseDto.class)),
            @OpenApiResponse(status = "304", description = "Not Modified (cached response, ETag matches)"),
            @OpenApiResponse(status = "404", description = "Not found (run ID or metadata not found)", content = @OpenApiContent(from = ErrorResponseDto.class)),
            @OpenApiResponse(status = "500", description = "Internal server error (database error)", content = @OpenApiContent(from = ErrorResponseDto.class))
        }
    )
    void getClades(final Context ctx) throws SQLException {
        final String runId = resolveRunId(ctx);

        final CacheConfig cacheConfig = CacheConfig.fromConfig(options, "clades");

        try (final IDatabaseReader reader = databaseProvider.createReader(runId)) {
            final TickRange range = reader.getOrganismTickRange();
            if (range == null) {
                ctx.status(HttpStatus.OK).json(new CladesResponseDto(List.of(), List.of(), List.of()));
                return;
            }
            final int interval = MetadataConfigHelper.getSamplingInterval(reader.getMetadata());
            // The grid gives candidates; which ticks a run actually recorded is its own answer
            final List<Long> ticks = reader.snapToRecordedTicks(
                    sampleTicks(range.minTick(), range.maxTick(), interval, cladeSamples));

            LOGGER.debug("Retrieving clades for runId={} ticks={}", runId, ticks.size());

            // The answer is settled by the sampled ticks, so the last of them names its version.
            // Not the last tick indexed: a run grows by a tick every few milliseconds while its
            // samples move once in many minutes, and an answer that never validates is one that
            // is rebuilt and resent for nothing.
            final String version = ticks.isEmpty() ? "empty" : String.valueOf(ticks.get(ticks.size() - 1));
            final String etag = "\"" + runId + "_clades_" + version + "\"";
            if (applyCacheHeaders(ctx, cacheConfig, etag)) {
                return;
            }

            ctx.status(HttpStatus.OK).json(answerFor(reader, runId + ":" + version, ticks));
        } catch (MetadataNotFoundException e) {
            throw new NoRunIdException("Metadata not found for run: " + runId, e);
        } catch (RuntimeException e) {
            handleDatabaseException(e, runId, "clades");
        } catch (SQLException e) {
            if (isSchemaNotFound(e)) {
                throw new NoRunIdException("Run ID not found: " + runId);
            }
            throw e;
        }
    }

    /**
     * The answer for one set of sampled ticks, built once and kept until the ticks move.
     * <p>
     * Building it reads the descent of the whole run and counts its organisms, which takes about
     * as long as everything else the endpoint does together. The sampled ticks move only when a
     * run has grown by a whole step of their grid, so between two of those the same answer is
     * asked for again and again - by a viewer that revalidates, by a second viewer, by a page
     * that was reloaded.
     *
     * @param reader Reader of the run
     * @param version Run and last sampled tick, which together settle what the answer holds
     * @param ticks The sampled ticks, ascending
     * @return The answer for these ticks
     * @throws SQLException if database read fails
     */
    CladesResponseDto answerFor(final IDatabaseReader reader, final String version,
                                        final List<Long> ticks) throws SQLException {
        final CladesResponseDto cached = answerCache.getIfPresent(version);
        if (cached != null) {
            return cached;
        }
        final Map<Long, Long> lineage = reader.readGenomeLineage();
        final List<GenomeCarriers> carriers = reader.readGenomeCounts(ticks);
        final CladesResponseDto answer = toCladesResponse(ancestryOf(lineage, carriers), carriers);
        answerCache.put(version, answer);
        return answer;
    }

    /**
     * The ticks a run is sampled at, on a grid that only ever grows at its end.
     * <p>
     * Spreading a fixed number of samples over the run would move every one of them as soon as the
     * run grows by a tick: a viewer would see the weights of its clades change although nothing in
     * the world did, and nothing computed for the run before would still fit. The samples
     * therefore sit on multiples of a step, and the step is the recording interval doubled until
     * the run fits into the number of samples asked for. Growing adds samples at the end; a
     * doubling drops every second one and leaves the rest exactly where they were.
     * <p>
     * The newest recorded tick is not added on top of the grid, however near a viewer usually
     * stands to it. It moves with every tick indexed, and a sample that moves is one the answer
     * changes with: it would be rebuilt and resent several times a minute for a run whose clades
     * shift once in an hour. What the grid does not reach yet is left to the view to show as
     * unanswered.
     *
     * @param minTick First recorded tick of the run
     * @param maxTick Last recorded tick of the run
     * @param interval The run's recording interval, at least 1
     * @param samples The most samples to return, at least 2
     * @return The ticks to sample, ascending, the first of the run among them
     */
    static List<Long> sampleTicks(final long minTick, final long maxTick, final int interval,
                                  final int samples) {
        long step = interval;
        while ((maxTick - minTick) / step >= samples) {
            step *= 2;
        }
        final List<Long> ticks = new ArrayList<>();
        for (long tick = minTick; tick <= maxTick; tick += step) {
            ticks.add(tick);
        }
        return ticks;
    }

    /**
     * Narrows a run's descent to what the sampled ticks stand on.
     * <p>
     * A run holds far more genomes than any of its ticks: most arose, carried a handful of
     * organisms and vanished between two samples. Sending all of them would make the answer
     * several times larger for lines no sample ever touches. What a viewer needs is the tree
     * above the genomes it sees, so every sampled genome is kept together with its ancestors, and
     * nothing else. A genome the viewer meets later, at a tick between the samples, arrives with
     * its own ancestry in the answer of the organism endpoint.
     *
     * @param lineage The descent of the whole run
     * @param carriers The sampled population
     * @return The entries of {@code lineage} on a path from a sampled genome upwards
     */
    static Map<Long, Long> ancestryOf(final Map<Long, Long> lineage,
                                      final List<GenomeCarriers> carriers) {
        final Map<Long, Long> kept = new LinkedHashMap<>();
        for (final GenomeCarriers entry : carriers) {
            Long genome = entry.genomeHash();
            // Walk upwards until a genome already kept, which carries its own ancestors with it
            while (genome != null && !kept.containsKey(genome) && lineage.containsKey(genome)) {
                final Long parent = lineage.get(genome);
                kept.put(genome, parent);
                genome = parent;
            }
        }
        return kept;
    }

    /**
     * Names every genome once and refers to it by position from there on.
     * <p>
     * A genome that carries organisms at a sampled tick but has no lineage entry - one that only
     * ever arose in an organism whose parent carried it unchanged - is named as well, so that no
     * sample points past the end of the list.
     *
     * @param lineage Genome to parent genome, null value for a genome that begins a line
     * @param carriers One entry per tick and genome
     * @return The response as it goes over the wire
     */
    static CladesResponseDto toCladesResponse(final Map<Long, Long> lineage,
                                              final List<GenomeCarriers> carriers) {
        final Map<Long, Integer> positions = new LinkedHashMap<>();
        final List<String> genomes = new ArrayList<>();
        for (final Long genome : lineage.keySet()) {
            positions.put(genome, genomes.size());
            genomes.add(String.valueOf(genome));
        }
        for (final GenomeCarriers entry : carriers) {
            positions.computeIfAbsent(entry.genomeHash(), genome -> {
                genomes.add(String.valueOf(genome));
                return genomes.size() - 1;
            });
        }

        final List<Integer> parents = new ArrayList<>(genomes.size());
        for (final String genome : genomes) {
            final Long parent = lineage.get(Long.valueOf(genome));
            final Integer position = parent == null ? null : positions.get(parent);
            parents.add(position == null ? -1 : position);
        }

        final Map<Long, List<int[]>> byTick = new LinkedHashMap<>();
        for (final GenomeCarriers entry : carriers) {
            byTick.computeIfAbsent(entry.tickNumber(), tick -> new ArrayList<>())
                .add(new int[] { positions.get(entry.genomeHash()), entry.carriers() });
        }
        final List<CladesResponseDto.CladeSampleDto> samples = new ArrayList<>(byTick.size());
        byTick.forEach((tick, entries) -> samples.add(new CladesResponseDto.CladeSampleDto(tick, entries)));

        return new CladesResponseDto(genomes, parents, samples);
    }

    private static Map<String, String> toStringMap(final Map<Long, Long> ancestors) {
        final Map<String, String> result = new LinkedHashMap<>(ancestors.size());
        ancestors.forEach((genome, parent) ->
            result.put(String.valueOf(genome), parent != null ? String.valueOf(parent) : null));
        return result;
    }

    /**
     * Handles database exceptions from RuntimeException wrappers with appropriate error mapping.
     *
     * @param e       The RuntimeException to inspect.
     * @param runId   The run ID for error context.
     * @param context Description of the operation (e.g., "organisms", "organism details").
     * @throws PoolExhaustionException if the cause is a pool exhaustion error.
     * @throws NoRunIdException        if the cause is a schema-not-found error.
     * @throws RuntimeException        if the cause is unrecognized.
     */
    private void handleDatabaseException(final RuntimeException e, final String runId, final String context) {
        if (e.getCause() instanceof SQLException sqlEx) {
            if (isPoolExhaustion(sqlEx)) {
                throw new PoolExhaustionException("Connection pool exhausted", sqlEx);
            }
            if (isSchemaNotFound(sqlEx)) {
                throw new NoRunIdException("Run ID not found: " + runId);
            }
        }
        throw new RuntimeException("Error retrieving " + context + " for runId: " + runId, e);
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
                throw new NoRunIdException("No organism ticks available for run: " + runId);
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
        } catch (NoRunIdException e) {
            throw e;
        } catch (RuntimeException e) {
            handleDatabaseException(e, runId, "organism tick range");
        } catch (SQLException e) {
            if (isSchemaNotFound(e)) {
                throw new NoRunIdException("Run ID not found: " + runId);
            }
            throw e;
        }
    }
}


