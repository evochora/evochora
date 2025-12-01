package org.evochora.datapipeline.services.analytics.plugins;

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

/**
 * Generates population metrics (alive count, total deaths, avg energy) in Parquet format.
 * <p>
 * This plugin demonstrates the simplified plugin API: only define schema and row extraction,
 * the indexer handles all I/O (DuckDB, Parquet, storage).
 * <p>
 * <strong>Metrics:</strong>
 * <ul>
 *   <li>{@code tick} - Simulation tick number</li>
 *   <li>{@code alive_count} - Number of living organisms</li>
 *   <li>{@code total_dead} - Cumulative death count (totalCreated - alive)</li>
 *   <li>{@code avg_energy} - Average energy per organism</li>
 * </ul>
 * <p>
 * <strong>Note:</strong> 'Dead count per tick' is not tracked because TickData only contains
 * living organisms. To track deaths per tick, we would need stateful tracking across batches,
 * which is complex with sampling. Thus, we use the cumulative total_dead metric.
 */
public class PopulationMetricsPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("alive_count", ColumnType.INTEGER)
        .column("total_dead", ColumnType.BIGINT)
        .column("avg_energy", ColumnType.DOUBLE)
        .build();

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    @Override
    public List<Object[]> extractRows(TickData tick) {
        // Count alive organisms and sum energy
        int alive = 0;
        long totalEnergy = 0;
        
        for (OrganismState org : tick.getOrganismsList()) {
            alive++;
            totalEnergy += org.getEnergy();
        }
        
        // Calculate total dead based on monotonic counter
        long totalCreated = tick.getTotalOrganismsCreated();
        long totalDead = totalCreated - alive;
        
        // Calculate average energy
        double avgEnergy = alive > 0 ? (double) totalEnergy / alive : 0.0;
        
        // Return single row for this tick
        return Collections.singletonList(new Object[] {
            tick.getTickNumber(),   // tick (BIGINT)
            alive,                   // alive_count (INTEGER)
            totalDead,               // total_dead (BIGINT)
            avgEnergy                // avg_energy (DOUBLE)
        });
    }

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Population Overview";
        entry.description = "Overview of alive organisms, total deaths, and average energy over time.";
        
        // Generate dataSources for all configured LOD levels
        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }
        
        entry.visualization = new VisualizationHint();
        entry.visualization.type = "line-chart";
        entry.visualization.config = new HashMap<>();
        entry.visualization.config.put("x", "tick");
        entry.visualization.config.put("y", List.of("alive_count", "total_dead"));
        entry.visualization.config.put("y2", List.of("avg_energy")); // Second axis

        return entry;
    }
}
