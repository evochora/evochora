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
 * Tracks the generation depth of organisms.
 * <p>
 * <strong>Metrics:</strong>
 * <ul>
 *   <li>{@code tick} - Simulation tick number</li>
 *   <li>{@code max_depth} - Maximum lineage depth currently alive</li>
 *   <li>{@code avg_depth} - Average lineage depth currently alive</li>
 * </ul>
 * <p>
 * The depth is read from each organism, which carries it since birth. It is deliberately not
 * derived from parent chains: a parent is removed from the simulation when it dies, so a consumer
 * walking the chain would find its ancestors missing and count from wherever the chain breaks -
 * which is what happened whenever the indexer restarted, and it happened silently.
 * <p>
 * Reading a recorded fact instead makes each row a function of its tick alone, so the values do
 * not depend on how much of the stream this instance has seen, on the order the chunks arrived in,
 * or on how many instances share the work.
 */
public class GenerationDepthPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("max_depth", ColumnType.INTEGER)
        .column("avg_depth", ColumnType.DOUBLE)
        .build();

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    @Override
    public List<Object[]> extractRows(TickData tick) {
        int maxDepth = 0;
        long sumDepth = 0;
        int count = 0;

        for (OrganismState org : tick.getOrganismsList()) {
            if (org.getIsDead()) continue;
            int depth = org.getGeneration();
            if (depth > maxDepth) maxDepth = depth;
            sumDepth += depth;
            count++;
        }

        double avgDepth = count > 0 ? (double) sumDepth / count : 0.0;

        return Collections.singletonList(new Object[] {
            tick.getTickNumber(),
            maxDepth,
            avgDepth
        });
    }

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Generation Depth";
        entry.description = "Maximum and average lineage depth of living organisms.";
        
        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }
        
        entry.visualization = VisualizationHint.chart("line-chart", "tick")
            .with("y", List.of("max_depth", "avg_depth"));

        return entry;
    }

}

