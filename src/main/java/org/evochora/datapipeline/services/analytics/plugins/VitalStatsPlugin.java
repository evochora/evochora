package org.evochora.datapipeline.services.analytics.plugins;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.analytics.AbstractAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.IAnalyticsContext;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.analytics.VisualizationHint;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.utils.MetadataConfigHelper;

import com.typesafe.config.Config;

/**
 * Tracks births and deaths over time, the deaths split by what killed the organism.
 * <p>
 * <strong>Architecture:</strong> This plugin is completely <strong>stateless</strong>.
 * It stores only raw "facts" that can be extracted from a single tick:
 * <ul>
 *   <li>{@code tick} - Simulation tick number</li>
 *   <li>{@code total_born} - Monotonic counter of all organisms ever created</li>
 *   <li>{@code alive_count} - Current number of living organisms</li>
 *   <li>{@code deaths_energy}, {@code deaths_entropy}, {@code deaths_other} - The organisms this
 *       recording reports as dead, by cause</li>
 * </ul>
 * <p>
 * <strong>Causes.</strong> The runtime kills an organism when its energy has dropped to zero or
 * below, when its entropy exceeds the configured maximum, or on a fatal VM error, and it checks in
 * that order. Killing changes neither value, so the cause is read back from the dead organism's
 * state in the same order: energy first, then entropy, and anything else is "other".
 * <p>
 * <strong>Why this plugin must see every recording.</strong> A dead organism appears in exactly one
 * recording and is removed afterwards, so the per-cause counts are complete only if no recording
 * is skipped. The number of deaths itself needs no such care: {@code total_born - alive_count} is a
 * running total kept by the simulation, which survives a resume and whose difference is exact on
 * every level of detail.
 * <p>
 * <strong>Query-Time Computation:</strong>
 * <pre>
 * 1. Take the width of one window from the card, which asks for as many as it can show
 * 2. For each row: births = delta(total_born), deaths = delta(total_born - alive_count)
 * 3. Aggregate by bucket and split the deaths by the causes counted in it
 * </pre>
 * On the finest level every recording is a row, so the counted causes add up to the deaths. A
 * coarser level reads only some recordings: the deaths stay exact, and their split is estimated
 * from the causes counted in the rows read. Deaths of a bucket without any counted cause are
 * shown as unclassified rather than guessed.
 */
public class VitalStatsPlugin extends AbstractAnalyticsPlugin {

    /** Schema stores only raw facts - no derived values */
    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("total_born", ColumnType.BIGINT)
        .column("alive_count", ColumnType.INTEGER)
        .column("deaths_energy", ColumnType.INTEGER)
        .column("deaths_entropy", ColumnType.INTEGER)
        .column("deaths_other", ColumnType.INTEGER)
        .build();

    /**
     * Entropy above which the runtime kills an organism. Without a run configuration - a caller
     * that does not place rows on a run, such as a unit test of the energy rule - no entropy limit
     * is known and such deaths count as other.
     */
    private int maxEntropy = Integer.MAX_VALUE;

    @Override
    protected Fixed fixedSamplingInterval() {
        return new Fixed(1, "a dead organism is reported in exactly one recording and removed "
            + "afterwards, so a skipped recording loses the cause of its deaths for good");
    }

    @Override
    public void initialize(IAnalyticsContext context) {
        super.initialize(context);
        if (context != null && context.getMetadata() != null
                && !context.getMetadata().getResolvedConfigJson().isEmpty()) {
            Config resolvedConfig = MetadataConfigHelper.getResolvedConfig(context.getMetadata());
            this.maxEntropy = resolvedConfig.getInt("runtime.organism.max-entropy");
        }
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
     * @return Single row with [tick, total_born, alive_count, deaths_energy, deaths_entropy,
     *         deaths_other]
     */
    @Override
    public List<Object[]> extractRows(TickData tick) {
        int aliveCount = 0;
        int energyDeaths = 0;
        int entropyDeaths = 0;
        int otherDeaths = 0;
        for (OrganismState org : tick.getOrganismsList()) {
            if (!org.getIsDead()) {
                aliveCount++;
            } else if (org.getEnergy() <= 0) {
                energyDeaths++;
            } else if (org.getEntropyRegister() > maxEntropy) {
                entropyDeaths++;
            } else {
                otherDeaths++;
            }
        }
        return Collections.singletonList(new Object[] {
            tick.getTickNumber(),
            tick.getTotalOrganismsCreated(),
            aliveCount,
            energyDeaths,
            entropyDeaths,
            otherDeaths
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
     * The first row of the data has no predecessor; its deaths are the ones counted in it.
     *
     * @return SQL query string with {table} placeholder
     */
    private String generateAggregatedQuery() {
        return """
            WITH
            %s,
            raw AS (
                -- Rows written before the causes were recorded have no cause columns; they read as
                -- none counted, so their deaths show as unclassified
                SELECT
                    tick,
                    window_tick,
                    total_born,
                    alive_count,
                    COALESCE(deaths_energy, 0) AS deaths_energy,
                    COALESCE(deaths_entropy, 0) AS deaths_entropy,
                    COALESCE(deaths_other, 0) AS deaths_other
                FROM window_rows
            ),
            computed AS (
                SELECT
                    tick,
                    window_tick,
                    COALESCE(total_born - LAG(total_born) OVER (ORDER BY tick), 0) AS births,
                    COALESCE((total_born - alive_count)
                        - LAG(total_born - alive_count) OVER (ORDER BY tick),
                        deaths_energy + deaths_entropy + deaths_other) AS deaths,
                    deaths_energy,
                    deaths_entropy,
                    deaths_other
                FROM raw
            ),
            buckets AS (
                -- A window the level holds no recording in carries nothing rather than zeros: the
                -- level knows nothing there, which is not the same as nobody having been born
                SELECT
                    windows.window_tick AS tick,
                    CASE WHEN COUNT(computed.tick) = 0 THEN NULL
                         ELSE SUM(births) END::BIGINT AS births,
                    CASE WHEN COUNT(computed.tick) = 0 THEN NULL
                         ELSE GREATEST(0, SUM(deaths)) END::DOUBLE AS deaths,
                    SUM(deaths_energy)::DOUBLE AS energy,
                    SUM(deaths_entropy)::DOUBLE AS entropy,
                    SUM(deaths_other)::DOUBLE AS other
                FROM windows LEFT JOIN computed
                    ON computed.window_tick = windows.window_tick
                GROUP BY windows.window_tick
            )
            SELECT
                tick,
                births,
                CASE WHEN deaths IS NULL THEN NULL
                     WHEN energy + entropy + other = 0 THEN 0
                     ELSE -deaths * energy / (energy + entropy + other) END AS deaths_energy,
                CASE WHEN deaths IS NULL THEN NULL
                     WHEN energy + entropy + other = 0 THEN 0
                     ELSE -deaths * entropy / (energy + entropy + other) END AS deaths_entropy,
                CASE WHEN deaths IS NULL THEN NULL
                     WHEN energy + entropy + other = 0 THEN 0
                     ELSE -deaths * other / (energy + entropy + other) END AS deaths_other,
                CASE WHEN deaths IS NULL THEN NULL
                     WHEN energy + entropy + other = 0 THEN -deaths ELSE 0 END AS deaths_unclassified
            FROM buckets
            ORDER BY tick
            """.formatted(windowSource());
    }

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Birth & Death Rates";
        entry.description = "Births (up) and deaths by cause (down) over time. "
            + "On coarser levels the split by cause is estimated.";

        // Generate dataSources for all configured LOD levels
        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }

        // Use custom aggregated query instead of QuerySpec
        entry.generatedQuery = generateAggregatedQuery();
        entry.outputColumns = List.of("tick", "births",
            "deaths_energy", "deaths_entropy", "deaths_other", "deaths_unclassified");

        // Visualization: a cause that did not occur in the window is left out of the legend
        entry.visualization = VisualizationHint.chart("stacked-bar-chart", "tick")
            .with("y", List.of("births",
                "deaths_energy", "deaths_entropy", "deaths_other", "deaths_unclassified"))
            .with("yFormat", "integer")
            .with("yLabel", "Organisms")
            .with("hideEmpty", true)
            .with("labels", Map.of(
                "births", "Births",
                "deaths_energy", "Deaths: energy",
                "deaths_entropy", "Deaths: entropy",
                "deaths_other", "Deaths: other",
                "deaths_unclassified", "Deaths: unclassified"))
            .with("colors", Map.of(
                "births", "#4ade80",
                "deaths_energy", "#f87171",
                "deaths_entropy", "#c084fc",
                "deaths_other", "#fbbf24",
                "deaths_unclassified", "#9ca3af"));

        return entry;
    }
}
