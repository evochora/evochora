package org.evochora.datapipeline.services.analytics.plugins;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.evochora.datapipeline.api.analytics.AbstractAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.analytics.VisualizerSpec;
import org.evochora.datapipeline.api.contracts.TickData;

/**
 * Tracks birth and death rates over time.
 * <p>
 * <strong>Architecture:</strong> This plugin is completely <strong>stateless</strong>.
 * It stores only raw "facts" that can be extracted from a single tick:
 * <ul>
 *   <li>{@code tick} - Simulation tick number</li>
 *   <li>{@code total_born} - Monotonic counter of all organisms ever created</li>
 *   <li>{@code alive_count} - Current number of living organisms</li>
 * </ul>
 * <p>
 * The derived values {@code births} and {@code deaths} are computed at query time
 * using SQL window functions with automatic bucketing for visualization.
 * <p>
 * <strong>Design Principles:</strong>
 * <ul>
 *   <li>Correct behavior with competing consumers (no shared state)</li>
 *   <li>Raw data is always accurate regardless of processing order</li>
 *   <li>Dynamic bucket aggregation (always ~100 bars regardless of tick count)</li>
 *   <li>Deaths shown as negative values for mirrored bar chart display</li>
 * </ul>
 * <p>
 * <strong>Query-Time Computation:</strong>
 * <pre>
 * 1. Calculate bucket_size = (max_tick - min_tick) / 100
 * 2. For each tick: births = delta(total_born), deaths = population balance
 * 3. Aggregate by bucket: SUM(births), SUM(deaths)
 * </pre>
 */
public class VitalStatsPlugin extends AbstractAnalyticsPlugin {
    
    /** Target number of buckets for aggregation (~100 bars in chart) */
    private static final int TARGET_BUCKETS = 100;

    /** Schema stores only raw facts - no derived values */
    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("total_born", ColumnType.BIGINT)
        .column("alive_count", ColumnType.INTEGER)
        .build();

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
     * @return Single row with [tick, total_born, alive_count]
     */
    @Override
    public List<Object[]> extractRows(TickData tick) {
        return Collections.singletonList(new Object[] {
            tick.getTickNumber(),
            tick.getTotalOrganismsCreated(),
            tick.getOrganismsList().size()
        });
    }

    /**
     * Generates the aggregated SQL query with dynamic bucket sizing.
     * <p>
     * The query automatically calculates the bucket size to produce ~100 buckets,
     * regardless of total tick count. This ensures readable bar charts even for
     * very long simulations.
     * <p>
     * Deaths are returned as negative values for mirrored bar chart display.
     *
     * @return SQL query string with {table} placeholder
     */
    private String generateAggregatedQuery() {
        return """
            WITH
            params AS (
                -- Calculate bucket size: (max - min) / TARGET_BUCKETS, minimum 1
                SELECT GREATEST(1, (MAX(tick) - MIN(tick)) / %d)::BIGINT AS bucket_size
                FROM {table}
            ),
            computed AS (
                SELECT
                    tick,
                    total_born,
                    alive_count,
                    COALESCE(total_born - LAG(total_born) OVER (ORDER BY tick), 0) AS births,
                    -GREATEST(0, COALESCE(
                        (LAG(alive_count) OVER (ORDER BY tick) + 
                         COALESCE(total_born - LAG(total_born) OVER (ORDER BY tick), 0)) 
                        - alive_count, 0)) AS deaths
                FROM {table}
            )
            SELECT
                (FLOOR(tick / (SELECT bucket_size FROM params)) * (SELECT bucket_size FROM params))::BIGINT AS tick,
                SUM(births)::BIGINT AS births,
                SUM(deaths)::BIGINT AS deaths
            FROM computed
            GROUP BY 1
            ORDER BY tick
            """.formatted(TARGET_BUCKETS);
    }

    /**
     * Defines how the data should be visualized in the frontend.
     */
    @Override
    public VisualizerSpec getVisualizerSpec() {
        return VisualizerSpec.builder()
            .chartType("bar-chart")
            .xAxis("tick")
            .yAxis("births", "deaths")
            .option("colors", java.util.Map.of(
                "births", "#4ade80",  // Green for births
                "deaths", "#f87171"   // Red for deaths
            ))
            .build();
    }

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Birth & Death Rates";
        entry.description = "Tracks organism births and deaths over time. "
            + "Data is aggregated into ~" + TARGET_BUCKETS + " time buckets for readability. "
            + "Births shown positive (up), deaths shown negative (down).";
        
        // Generate dataSources for all configured LOD levels
        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }
        
        // Use custom aggregated query instead of QuerySpec
        entry.generatedQuery = generateAggregatedQuery();
        entry.outputColumns = List.of("tick", "births", "deaths");
        
        // Visualization
        entry.visualization = getVisualizerSpec().toVisualizationHint();
        entry.customVisualizerPath = getVisualizerSpec().getCustomVisualizerPath();

        return entry;
    }
}

