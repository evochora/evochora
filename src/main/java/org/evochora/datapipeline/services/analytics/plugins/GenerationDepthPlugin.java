package org.evochora.datapipeline.services.analytics.plugins;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * This plugin is stateful: it maintains a map of {@code organismId -> generationDepth}.
 * To avoid unbounded memory growth, it prunes IDs of dead organisms from its state map on each tick.
 */
public class GenerationDepthPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("max_depth", ColumnType.INTEGER)
        .column("avg_depth", ColumnType.DOUBLE)
        .build();

    // Map: OrganismID -> Generation Depth
    private Map<Integer, Integer> depthMap;

    @Override
    public void initialize(IAnalyticsContext context) {
        super.initialize(context);
        this.depthMap = new HashMap<>();
    }

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    @Override
    public List<Object[]> extractRows(TickData tick) {
        Set<Integer> currentAliveIds = new HashSet<>();
        int maxDepth = 0;
        long sumDepth = 0;
        int count = 0;
        
        for (OrganismState org : tick.getOrganismsList()) {
            int id = org.getOrganismId();
            currentAliveIds.add(id);
            
            // Determine depth, memoize it in the map
            int depth = depthMap.computeIfAbsent(id, orgId -> {
                if (org.hasParentId()) {
                    // If parent is known, depth = parent + 1.
                    // If parent is not in map (e.g., died before tracking started), default to 0.
                    return depthMap.getOrDefault(org.getParentId(), 0) + 1;
                }
                // No parent = Generation 0
                return 0;
            });
            
            if (depth > maxDepth) maxDepth = depth;
            sumDepth += depth;
            count++;
        }
        
        // Memory Cleanup: Remove organisms that are no longer alive
        // Crucial to prevent OOM in long runs
        if (depthMap.size() > currentAliveIds.size()) {
            depthMap.keySet().retainAll(currentAliveIds);
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
        
        entry.visualization = new VisualizationHint();
        entry.visualization.type = "line-chart";
        entry.visualization.config = new HashMap<>();
        entry.visualization.config.put("x", "tick");
        entry.visualization.config.put("y", List.of("max_depth", "avg_depth"));

        return entry;
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // Estimate: maxOrganisms * (Integer key + Integer value + map entry overhead)
        // A reasonable estimate is ~64 bytes per organism in the depthMap.
        long mapBytes = params.maxOrganisms() * 64L;
        
        String explanation = String.format("%d max organisms × 64 bytes/organism (depthMap)",
            params.maxOrganisms());
            
        return Collections.singletonList(new MemoryEstimate(
            "Plugin: " + metricId,
            mapBytes,
            explanation,
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}

