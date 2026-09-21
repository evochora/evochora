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
     * Generates the aggregated SQL query with dynamic bucket sizing.
     * Uses AVG() for each percentile to smooth the data over buckets.
     *
     * @return SQL query string with {table} placeholder
     */
    private String generateAggregatedQuery() {
        return """
            WITH
            params AS (
                SELECT {tickInterval}::BIGINT AS bucket_size
            )
            SELECT
                (FLOOR(tick / (SELECT bucket_size FROM params)) * (SELECT bucket_size FROM params))::BIGINT AS tick,
                AVG(p0)::INTEGER AS p0,
                AVG(p10)::INTEGER AS p10,
                AVG(p25)::INTEGER AS p25,
                AVG(p50)::INTEGER AS p50,
                AVG(p75)::INTEGER AS p75,
                AVG(p90)::INTEGER AS p90,
                AVG(p100)::INTEGER AS p100
            FROM {table}
            GROUP BY 1
            ORDER BY tick
            """;
    }

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Age Distribution";
        entry.description = "Percentiles of organism age distribution. "
            + "One point per time window of the resolution shown.";
        
        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }
        
        // Use aggregated query with bucketing
        entry.generatedQuery = generateAggregatedQuery();
        entry.outputColumns = List.of("tick", "p0", "p10", "p25", "p50", "p75", "p90", "p100");
        
        entry.visualization = VisualizationHint.chart("band-chart", "tick")
            .with("y", List.of("p0", "p10", "p25", "p50", "p75", "p90", "p100"));

        return entry;
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // This plugin is stateless. The list of ages is created and discarded within
        // the extractRows method. Its heap memory usage is negligible.
        return Collections.emptyList();
    }
}

