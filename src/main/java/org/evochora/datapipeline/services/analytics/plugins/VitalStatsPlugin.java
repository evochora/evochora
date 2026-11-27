package org.evochora.datapipeline.services.analytics.plugins;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.analytics.AbstractAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.Aggregation;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.IAnalyticsContext;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.analytics.VisualizationHint;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;

/**
 * Tracks births and deaths over time, the deaths split by what killed the organism.
 * <p>
 * <strong>Architecture:</strong> This plugin is completely <strong>stateless</strong>.
 * It stores only raw "facts" that can be extracted from a single tick: the organisms created so
 * far, the organisms alive, and the organisms the recording reports as dead, by cause.
 * <p>
 * <strong>Causes.</strong> {@link DeathCause} reads the cause of each death from the dead
 * organism's state, by the rule the runtime kills with.
 * <p>
 * <strong>Why this plugin must see every recording.</strong> A dead organism appears in exactly one
 * recording and is removed afterwards, so the per-cause counts are complete only if no recording
 * is skipped. The number of births needs no such care: {@code total_born} is a running total kept
 * by the simulation, which survives a resume and whose difference is exact on every level of
 * detail.
 * <p>
 * <strong>Levels of detail.</strong> The two running totals are states and are sampled. The deaths
 * by cause are events of one recording and are declared {@link Aggregation#SUM}, so a coarser
 * level adds the recordings of its window instead of keeping one of them: every level holds all
 * the deaths of the run with their causes.
 * <p>
 * <strong>Query-Time Computation:</strong>
 * <pre>
 * 1. Take the width of one window from the card, which asks for as many as it can show
 * 2. For each row: births = delta(total_born)
 * 3. Aggregate by bucket: the births and the deaths of each cause added up
 * </pre>
 */
public class VitalStatsPlugin extends AbstractAnalyticsPlugin {

    /** Schema stores only raw facts - no derived values */
    private static final ParquetSchema SCHEMA = ParquetSchema.builder(
            "Births and deaths. One row per recorded tick. The births between two rows are the "
            + "change of total_born, which is exact on every level of detail. The deaths are "
            + "counted by cause, and a row of a coarser level holds the deaths of all the recorded "
            + "ticks it stands for.")
        .column("tick", ColumnType.BIGINT, "The recorded tick the row describes.")
        .column("total_born", ColumnType.BIGINT,
            "Organisms created since the run began, the ancestors included.")
        .column("alive_count", ColumnType.INTEGER, "Organisms alive at the tick.")
        .column("deaths_energy", ColumnType.INTEGER, Aggregation.SUM,
            "Organisms reported as dead since the previous row because "
            + DeathCause.meaningOf("energy") + ". A dead organism is reported by one recorded "
            + "tick only.")
        .column("deaths_entropy", ColumnType.INTEGER, Aggregation.SUM,
            "Organisms reported as dead since the previous row because "
            + DeathCause.meaningOf("entropy") + ". A dead organism is reported by one recorded "
            + "tick only.")
        .build();

    /** Reads the cause of a death; knows no entropy limit until a run has been supplied. */
    private DeathCause deathCause = DeathCause.forRun(null);

    @Override
    protected Fixed fixedSamplingInterval() {
        return new Fixed(1, "a dead organism is reported in exactly one recording and removed "
            + "afterwards, so a skipped recording loses the cause of its deaths for good");
    }

    @Override
    public void initialize(IAnalyticsContext context) {
        super.initialize(context);
        this.deathCause = DeathCause.forRun(context);
    }

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    /**
     * Extracts raw facts from a single tick.
     * <p>
     * This method is completely stateless - it only reads values directly
     * available in the TickData, with no reference to previous ticks.
     *
     * @param tick The tick data to process
     * @return Single row with [tick, total_born, alive_count, deaths_energy, deaths_entropy]
     * @throws IllegalStateException if a dead organism's state fits no cause of death
     */
    @Override
    public List<Object[]> extractRows(TickData tick) {
        int aliveCount = 0;
        int[] deaths = new int[DeathCause.CAUSES.size()];
        for (OrganismState org : tick.getOrganismsList()) {
            if (!org.getIsDead()) {
                aliveCount++;
            } else {
                deaths[deathCause.of(org)]++;
            }
        }
        return Collections.singletonList(new Object[] {
            tick.getTickNumber(),
            tick.getTotalOrganismsCreated(),
            aliveCount,
            deaths[DeathCause.ENERGY],
            deaths[DeathCause.ENTROPY]
        });
    }

    /**
     * Generates the aggregated SQL query with dynamic bucket sizing.
     * <p>
     * The query automatically calculates the bucket size to produce one point per window,
     * regardless of total tick count. This ensures readable bar charts even for
     * very long simulations.
     * <p>
     * Deaths are returned as negative values, one column per cause, for mirrored stacked bars.
     * The first row of the data has no predecessor and therefore no births.
     *
     * @return SQL query string with {table} placeholder
     */
    private String generateAggregatedQuery() {
        return """
            WITH
            %s,
            computed AS (
                SELECT
                    tick,
                    window_tick,
                    COALESCE(total_born - LAG(total_born) OVER (ORDER BY tick), 0) AS births,
                    deaths_energy,
                    deaths_entropy
                FROM window_rows
            )
            -- A window the level holds no recording in carries nothing rather than zeros: the
            -- level knows nothing there, which is not the same as nobody having been born
            SELECT
                windows.window_tick AS tick,
                SUM(births)::BIGINT AS births,
                (-SUM(deaths_energy))::BIGINT AS deaths_energy,
                (-SUM(deaths_entropy))::BIGINT AS deaths_entropy
            FROM windows LEFT JOIN computed
                ON computed.window_tick = windows.window_tick
            GROUP BY windows.window_tick
            ORDER BY tick
            """.formatted(windowSource());
    }

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Birth & Death Rates";
        entry.description = "Births (up) and deaths by cause (down) over time.";

        // Generate dataSources for all configured LOD levels
        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }

        // Use custom aggregated query instead of QuerySpec
        entry.generatedQuery = generateAggregatedQuery();
        entry.outputColumns = List.of("tick", "births", "deaths_energy", "deaths_entropy");

        // Visualization: a cause that did not occur in the window is left out of the legend
        entry.visualization = VisualizationHint.chart("stacked-bar-chart", "tick")
            .with("y", List.of("births", "deaths_energy", "deaths_entropy"))
            .with("yFormat", "integer")
            .with("yLabel", "Organisms")
            .with("hideEmpty", true)
            .with("labels", Map.of(
                "births", "Births",
                "deaths_energy", "Deaths: energy",
                "deaths_entropy", "Deaths: entropy"))
            .with("colors", Map.of(
                "births", "#4ade80",
                "deaths_energy", "#f87171",
                "deaths_entropy", "#c084fc"));

        return entry;
    }
}
