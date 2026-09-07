package org.evochora.datapipeline.services.analytics.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.evochora.datapipeline.api.analytics.AbstractAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.IAnalyticsContext;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.analytics.VisualizationHint;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;

import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Records how the living population is distributed over the genomes carrying it.
 * <p>
 * <strong>Metrics:</strong>
 * <ul>
 *   <li>{@code tick} - the recording</li>
 *   <li>{@code genome_hash} - a genome with at least one living carrier</li>
 *   <li>{@code count} - how many living organisms carry it</li>
 * </ul>
 * <p>
 * <strong>Why every genome and not a ranking.</strong> Which genomes matter is a question of the
 * moment one looks at: a genome sweeping through the population between two zoom levels is
 * irrelevant over the whole run and dominant in its own window. A ranking decided while writing
 * would have to answer that question once and for all, and answering it needs a memory of earlier
 * ticks - which an analytics plugin cannot have, because several indexers share the work and see
 * different parts of it. Writing every genome moves the choice to where the window is known.
 * <p>
 * Read together with the genome lineage, the rows also carry the descent of a population: the
 * share of a whole branch of the tree is the sum of the counts of its genomes.
 * <p>
 * Organisms without genome molecules carry no genome and are not counted; the population metric
 * reports them as the difference between its living and bodied counts.
 */
public class GenomePopulationPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("genome_hash", ColumnType.BIGINT)
        .column("count", ColumnType.INTEGER)
        .build();

    /** Bytes per entry of the counting map: key, value and open-addressing overhead. */
    private static final int BYTES_PER_COUNT_ENTRY = 24;

    /** Reused across ticks; holds the carriers per genome of the current recording. */
    private Long2IntOpenHashMap genomeCounts;

    /** Metric holding the lineage the clade view reads alongside these counts. */
    private String lineageMetricId = "genome_lineage";

    /** Metric holding the mutation events a band's founding mutation is named from. */
    private String mutationSummaryMetricId = "mutation_summary";

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code lineageMetricId} or {@code mutationSummaryMetricId}
     *         is configured empty, which would leave the chart looking for a table under no name
     *         and showing nothing
     */
    @Override
    public void configure(Config config) {
        super.configure(config);
        this.lineageMetricId = companionMetricId(config, "lineageMetricId",
            "the lineage this chart groups the population by", lineageMetricId);
        this.mutationSummaryMetricId = companionMetricId(config, "mutationSummaryMetricId",
            "the mutation events a band's founding mutation is named from", mutationSummaryMetricId);
    }

    /**
     * Reads the name of a table this chart is read next to.
     *
     * @param config the plugin's configuration
     * @param option the option naming the metric
     * @param holds what the named table holds, for the message
     * @param fallback the name to keep when the option is absent
     * @return the metric id to load that table under
     * @throws IllegalArgumentException if the option is configured empty
     */
    private String companionMetricId(Config config, String option, String holds, String fallback) {
        if (!config.hasPath(option)) {
            return fallback;
        }
        String configured = config.getString(option).trim();
        if (configured.isEmpty()) {
            throw new IllegalArgumentException("Metric '" + metricId + "': " + option
                + " names the metric holding " + holds + ", and cannot be empty.");
        }
        return configured;
    }

    @Override
    public void initialize(IAnalyticsContext context) {
        super.initialize(context);
        this.genomeCounts = new Long2IntOpenHashMap();
        this.genomeCounts.defaultReturnValue(0);
    }

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Returns one row per genome with a living carrier, and no row at all once the population has
     * died out.
     */
    @Override
    public List<Object[]> extractRows(TickData tick) {
        genomeCounts.clear();

        for (OrganismState org : tick.getOrganismsList()) {
            if (org.getIsDead() || org.getGenomeHash() == 0L) {
                continue;
            }
            genomeCounts.addTo(org.getGenomeHash(), 1);
        }

        if (genomeCounts.isEmpty()) {
            return Collections.emptyList();
        }

        List<Object[]> rows = new ArrayList<>(genomeCounts.size());
        for (Long2IntMap.Entry entry : genomeCounts.long2IntEntrySet()) {
            rows.add(new Object[] {
                tick.getTickNumber(),
                entry.getLongKey(),
                entry.getIntValue()
            });
        }
        return rows;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The chart groups the genomes into clades - the descendants of one branch of the lineage -
     * and stacks their shares of the whole population. Which branch is shown is chosen while
     * looking: clicking a clade enters it and shows what it is made of, so a cascade of sweeps is
     * followed one level at a time. Shares stay shares of the population, so entering a small
     * clade shows a small band, not a full one.
     * <p>
     * A band also says what started it. Every genome has its founding mutation in the events of
     * its first carrier, so the chart reads the mutation summary next to the lineage and names
     * kind, number of cells and position on the band. A band whose genome has no event with cells
     * arose from something other than a mutation plugin, which the chart says instead of leaving
     * the question open.
     * <p>
     * Genome hashes travel as text. They are 64 bit, and a JavaScript number keeps 53 of them -
     * two genomes would silently become one, and the tree would join branches that never met.
     */
    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = "genome_clades";
        entry.storageMetricId = metricId;
        entry.name = "Clade Shares";
        entry.description = "How the living population divides between the branches of the genome "
            + "lineage. Click a band to open it into its child clades.";

        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }

        entry.generatedQuery = "SELECT tick, genome_hash::VARCHAR AS genome_hash, count "
            + "FROM {table} ORDER BY tick";
        entry.outputColumns = List.of("tick", "genome_hash", "count");

        // Both companions arrive as written, row for row. Neither is condensed here: which of
        // several rows of a genome counts is a question the chart answers anyway - the earliest
        // one - and grouping by genome hash is a hash aggregation over an unsorted column, which
        // the browser's DuckDB build does not survive beyond a few thousand rows. Every query that
        // works today groups by tick, along which the files are ordered. Both tables carry one
        // row per genome and per mutation event rather than per recording, so the chart walks
        // them once per redraw.
        entry.companions = List.of(
            new ManifestEntry.Companion(lineageMetricId,
                "SELECT genome_hash::VARCHAR AS genome_hash, "
                    + "parent_genome_hash::VARCHAR AS parent_genome_hash, first_birth_tick "
                    + "FROM {table}"),
            new ManifestEntry.Companion(mutationSummaryMetricId,
                "SELECT genome_hash::VARCHAR AS genome_hash, birth_tick, organism_id, "
                    + "event_index, kind, plugin_class, cell_count, position FROM {table}"));

        entry.visualization = VisualizationHint.chart("clade-area-chart", "tick")
            .with("groupBy", "genome_hash")
            .with("y", "count")
            .with("yFormat", "percent")
            .with("lineageMetric", lineageMetricId)
            .with("causeMetric", mutationSummaryMetricId);

        return entry;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Every organism could carry a genome of its own, so the counting map is bounded by the
     * organism limit.
     */
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        long countBytes = params.maxOrganisms() * (long) BYTES_PER_COUNT_ENTRY;
        return Collections.singletonList(new MemoryEstimate(
            "Plugin: " + metricId,
            countBytes,
            String.format("%d max organisms × %d bytes/genome count",
                params.maxOrganisms(), BYTES_PER_COUNT_ENTRY),
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}
