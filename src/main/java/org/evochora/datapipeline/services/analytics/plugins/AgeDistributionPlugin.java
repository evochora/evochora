package org.evochora.datapipeline.services.analytics.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.evochora.datapipeline.api.analytics.AbstractAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.analytics.VisualizationHint;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;

/**
 * Tracks the age distribution of the population using percentiles.
 * <p>
 * <strong>Metrics:</strong>
 * <ul>
 *   <li>{@code tick} - Simulation tick number</li>
 *   <li>{@code p0} - Minimum age</li>
 *   <li>{@code p10} - 10th percentile age</li>
 *   <li>{@code p25} - 25th percentile age (1st quartile)</li>
 *   <li>{@code p50} - Median age</li>
 *   <li>{@code p75} - 75th percentile age (3rd quartile)</li>
 *   <li>{@code p90} - 90th percentile age</li>
 *   <li>{@code p100} - Maximum age</li>
 * </ul>
 * <p>
 * This provides a robust visualization of age structure that scales automatically
 * with the lifespan of organisms (whether 100 or 1,000,000 ticks).
 * <p>
 * <strong>Bucket Aggregation:</strong> Data is aggregated into one point per window using AVG()
 * for smooth visualization regardless of total tick count.
 */
public class AgeDistributionPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("p0", ColumnType.INTEGER)
        .column("p10", ColumnType.INTEGER)
        .column("p25", ColumnType.INTEGER)
        .column("p50", ColumnType.INTEGER)
        .column("p75", ColumnType.INTEGER)
        .column("p90", ColumnType.INTEGER)
        .column("p100", ColumnType.INTEGER)
        .build();

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    @Override
    public List<Object[]> extractRows(TickData tick) {
        long currentTick = tick.getTickNumber();
        List<OrganismState> organisms = tick.getOrganismsList();
        
        if (organisms.isEmpty()) {
            // No organisms -> all ages are 0
            return Collections.singletonList(new Object[] {
                currentTick, 0, 0, 0, 0, 0, 0, 0
            });
        }
        
        // Collect ages (exclude dead organisms)
        List<Integer> ages = new ArrayList<>(organisms.size());
        for (OrganismState org : organisms) {
            if (org.getIsDead()) continue;
            long birthTick = org.getBirthTick();
            int age = (int) (currentTick - birthTick);
            if (age < 0) age = 0; // Should not happen
            ages.add(age);
        }
        
        // Sort for percentile calculation
        Collections.sort(ages);
        
        Object[] row = new Object[] {
            currentTick,
            Percentiles.of(ages, 0),
            Percentiles.of(ages, 10),
            Percentiles.of(ages, 25),
            Percentiles.of(ages, 50),
            Percentiles.of(ages, 75),
            Percentiles.of(ages, 90),
            Percentiles.of(ages, 100)
        };

        return Collections.singletonList(row);
    }

    /**
     * Builds the query the browser runs over the loaded rows: one recording per window of the
     * resolution shown, the earliest of that window, with its percentiles as they were measured.
     * <p>
     * The percentiles are not averaged over a window. A percentile is a position in a distribution,
     * not a quantity that can be added and divided: the mean of the medians of ten recordings is
     * not the median of what lived through them, it weighs a recording of twelve organisms like one
     * of two thousand, and it hands back to an outlier the influence a percentile is chosen to deny
     * it. The levels of this metric are sampled rather than summed for the same reason, and this
     * query follows them.
     *
     * @return SQL query string with {table} placeholder
     */
    private String generateAggregatedQuery() {
        return """
            WITH
            params AS (
                SELECT GREATEST(1, (MAX(tick) - MIN(tick)) / {buckets})::BIGINT AS bucket_size
                FROM {table}
            )
            SELECT
                MIN(tick)::BIGINT AS tick,
                ARG_MIN(p0, tick)::INTEGER AS p0,
                ARG_MIN(p10, tick)::INTEGER AS p10,
                ARG_MIN(p25, tick)::INTEGER AS p25,
                ARG_MIN(p50, tick)::INTEGER AS p50,
                ARG_MIN(p75, tick)::INTEGER AS p75,
                ARG_MIN(p90, tick)::INTEGER AS p90,
                ARG_MIN(p100, tick)::INTEGER AS p100
            FROM {table}
            GROUP BY FLOOR(tick / (SELECT bucket_size FROM params))
            ORDER BY tick
            """;
    }

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Age Distribution";
        entry.description = "How old the living are: percentiles of their age, one recording per "
            + "time window. The oldest organism has its own line and its own scale.";
        
        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }
        
        // Use aggregated query with bucketing
        entry.generatedQuery = generateAggregatedQuery();
        entry.outputColumns = List.of("tick", "p0", "p10", "p25", "p50", "p75", "p90", "p100");
        
        // The band holds the percentiles that describe the spread. The youngest organism is always
        // a newborn and says nothing; the oldest is one organism, and how far it reaches grows with
        // the size of the population, so it gets a line and a scale of its own rather than
        // stretching the band that everything else is read in
        entry.visualization = VisualizationHint.chart("band-chart", "tick")
            .with("y", List.of("p10", "p25", "p50", "p75", "p90"))
            .with("yLabel", "Age of the living, in ticks")
            .with("yFormat", "integer")
            .with("y2", List.of("p100"))
            .with("labels", java.util.Map.of("p100", "Oldest organism"))
            .with("y2Label", "Oldest organism, in ticks")
            .with("y2Format", "integer");

        return entry;
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // This plugin is stateless. The list of ages is created and discarded within
        // the extractRows method. Its heap memory usage is negligible.
        return Collections.emptyList();
    }
}

