package org.evochora.datapipeline.services.analytics.plugins;

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
import org.evochora.datapipeline.utils.MetadataConfigHelper;

import com.typesafe.config.Config;

/**
 * Generates population metrics (alive count, avg energy %, avg entropy %) in Parquet format.
 * <p>
 * <strong>Metrics:</strong>
 * <ul>
 *   <li>{@code tick} - Simulation tick number</li>
 *   <li>{@code alive_count} - Number of living organisms</li>
 *   <li>{@code avg_energy} - Average energy as % of maxEnergy (0-100)</li>
 *   <li>{@code avg_entropy} - Average entropy as % of maxEntropy (0-100)</li>
 * </ul>
 * <p>
 * Maximum values for normalization are read from simulation metadata
 * ({@code runtime.organism.max-energy} and {@code runtime.organism.max-entropy}).
 * Both values are normalized to 0-100% so they are directly comparable on the same Y-axis.
 */
public class PopulationMetricsPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("alive_count", ColumnType.INTEGER)
        .column("avg_energy", ColumnType.DOUBLE)
        .column("avg_entropy", ColumnType.DOUBLE)
        .build();

    /** Maximum energy per organism, used for percentage normalization. */
    private int maxEnergy;

    /** Maximum entropy per organism, used for percentage normalization. */
    private int maxEntropy;

    @Override
    public void initialize(IAnalyticsContext context) {
        super.initialize(context);
        if (context != null && context.getMetadata() != null && !context.getMetadata().getResolvedConfigJson().isEmpty()) {
            Config resolvedConfig = MetadataConfigHelper.getResolvedConfig(context.getMetadata());
            this.maxEnergy = resolvedConfig.getInt("runtime.organism.max-energy");
            this.maxEntropy = resolvedConfig.getInt("runtime.organism.max-entropy");
        }
    }

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    @Override
    public List<Object[]> extractRows(TickData tick) {
        // Count alive organisms and sum energy/entropy
        int alive = 0;
        long totalEnergy = 0;
        long totalEntropy = 0;

        for (OrganismState org : tick.getOrganismsList()) {
            alive++;
            totalEnergy += org.getEnergy();
            totalEntropy += org.getEntropyRegister();
        }

        // Calculate averages as % of maximum
        double avgEnergyPct = alive > 0 ? (double) totalEnergy / alive / maxEnergy * 100.0 : 0.0;
        double avgEntropyPct = alive > 0 ? (double) totalEntropy / alive / maxEntropy * 100.0 : 0.0;

        // Return single row for this tick
        return Collections.singletonList(new Object[] {
            tick.getTickNumber(),   // tick (BIGINT)
            alive,                   // alive_count (INTEGER)
            avgEnergyPct,            // avg_energy (DOUBLE, 0-100%)
            avgEntropyPct            // avg_entropy (DOUBLE, 0-100%)
        });
    }

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Population Overview";
        entry.description = "Overview of alive organisms, average energy %, and average entropy % over time.";

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
        entry.visualization.config.put("y", List.of("alive_count"));
        entry.visualization.config.put("yFormat", "integer");
        entry.visualization.config.put("y2", List.of("avg_energy", "avg_entropy"));
        entry.visualization.config.put("y2Format", "percent");

        return entry;
    }
}
