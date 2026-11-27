package org.evochora.datapipeline.services.analytics.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    /** How many columns stand before the deaths by cause: tick, total_born and alive_count. */
    private static final int COLUMNS_BEFORE_CAUSES = 3;

    /** The colour each cause of death is drawn in on the card. */
    private static final Map<String, String> CAUSE_COLORS = Map.of(
        "energy", "#f87171",
        "entropy", "#c084fc");

    /** Schema stores only raw facts - no derived values */
    private static final ParquetSchema SCHEMA = schema();

    /**
     * Builds the schema: the two running totals, and one column of deaths per cause
     * {@link DeathCause} knows, in its order.
     *
     * @return the schema
     */
    private static ParquetSchema schema() {
        ParquetSchema.Builder builder = ParquetSchema.builder(
                "Births and deaths. One row per recorded tick. The births between two rows are the "
                + "change of total_born, which is exact on every level of detail. The deaths are "
                + "counted by cause, and a row of a coarser level holds the deaths of all the "
                + "recorded ticks it stands for.")
            .column("tick", ColumnType.BIGINT, "The recorded tick the row describes.")
            .column("total_born", ColumnType.BIGINT,
                "Organisms created since the run began, the ancestors included.")
            .column("alive_count", ColumnType.INTEGER, "Organisms alive at the tick.");
        for (String cause : DeathCause.CAUSES) {
            builder.column(columnOf(cause), ColumnType.INTEGER, Aggregation.SUM,
                "Organisms reported as dead since the previous row because "
                + DeathCause.meaningOf(cause) + ". A dead organism is reported by one recorded "
                + "tick only.");
        }
        return builder.build();
    }

    /**
     * Names the column that counts the deaths of one cause.
     *
     * @param cause one of {@link DeathCause#CAUSES}
     * @return the column's name
     */
    private static String columnOf(String cause) {
        return "deaths_" + cause;
    }

    /** Reads the cause of a death; {@code null} until a run has been supplied. */
    private DeathCause deathCause;

    @Override
    protected Fixed fixedSamplingInterval() {
        return new Fixed(1, "a dead organism is reported in exactly one recording and removed "
            + "afterwards, so a skipped recording loses the cause of its deaths for good");
    }

    @Override
    public void initialize(IAnalyticsContext context) {
        super.initialize(context);
        if (context != null) {
            this.deathCause = DeathCause.forRun(metricId, context);
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
     * @return Single row with tick, total_born, alive_count and the deaths of each cause
     * @throws IllegalStateException if the plugin was initialized without an analytics context,
     *         since the limit a death by entropy is recognized by would then be unknown, or if a
     *         dead organism's state fits no cause of death
     */
    @Override
    public List<Object[]> extractRows(TickData tick) {
        if (deathCause == null) {
            throw new IllegalStateException("Metric '" + metricId + "': the cause of a death cannot "
                + "be read because the plugin was initialized without an analytics context.");
        }
        int aliveCount = 0;
        int[] deaths = new int[DeathCause.CAUSES.size()];
        for (OrganismState org : tick.getOrganismsList()) {
            if (!org.getIsDead()) {
                aliveCount++;
            } else {
                deaths[deathCause.of(org)]++;
            }
        }
        Object[] row = new Object[COLUMNS_BEFORE_CAUSES + deaths.length];
        row[0] = tick.getTickNumber();
        row[1] = tick.getTotalOrganismsCreated();
        row[2] = aliveCount;
        for (int cause = 0; cause < deaths.length; cause++) {
            row[COLUMNS_BEFORE_CAUSES + cause] = deaths[cause];
        }
        return Collections.singletonList(row);
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
        StringBuilder carried = new StringBuilder();
        StringBuilder summed = new StringBuilder();
        for (String cause : DeathCause.CAUSES) {
            String column = columnOf(cause);
            carried.append(",\n        ").append(column);
            summed.append(",\n    (-SUM(").append(column).append("))::BIGINT AS ").append(column);
        }
        return """
            WITH
            %s,
            computed AS (
                SELECT
                    tick,
                    window_tick,
                    COALESCE(total_born - LAG(total_born) OVER (ORDER BY tick), 0) AS births%s
                FROM window_rows
            )
            -- A window the level holds no recording in carries nothing rather than zeros: the
            -- level knows nothing there, which is not the same as nobody having been born
            SELECT
                windows.window_tick AS tick,
                SUM(births)::BIGINT AS births%s
            FROM windows LEFT JOIN computed
                ON computed.window_tick = windows.window_tick
            GROUP BY windows.window_tick
            ORDER BY tick
            """.formatted(windowSource(), carried, summed);
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

        // The births, and one series per cause of death in the order DeathCause numbers them
        List<String> series = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, String> colors = new LinkedHashMap<>();
        series.add("births");
        labels.put("births", "Births");
        colors.put("births", "#4ade80");
        for (String cause : DeathCause.CAUSES) {
            String column = columnOf(cause);
            series.add(column);
            labels.put(column, "Deaths: " + cause);
            colors.put(column, colorOf(cause));
        }

        // Use custom aggregated query instead of QuerySpec
        entry.generatedQuery = generateAggregatedQuery();
        List<String> outputColumns = new ArrayList<>();
        outputColumns.add("tick");
        outputColumns.addAll(series);
        entry.outputColumns = outputColumns;

        // Visualization: a cause that did not occur in the window is left out of the legend
        entry.visualization = VisualizationHint.chart("stacked-bar-chart", "tick")
            .with("y", series)
            .with("yFormat", "integer")
            .with("yLabel", "Organisms")
            .with("hideEmpty", true)
            .with("labels", labels)
            .with("colors", colors);

        return entry;
    }

    /**
     * Finds the colour a cause of death is drawn in.
     *
     * @param cause one of {@link DeathCause#CAUSES}
     * @return the colour
     * @throws IllegalStateException if the card names no colour for the cause, which is a cause
     *         added to {@link DeathCause} and not to this card
     */
    private String colorOf(String cause) {
        String color = CAUSE_COLORS.get(cause);
        if (color == null) {
            throw new IllegalStateException("Metric '" + metricId + "': the card has no colour for "
                + "the cause of death '" + cause + "'.");
        }
        return color;
    }
}
