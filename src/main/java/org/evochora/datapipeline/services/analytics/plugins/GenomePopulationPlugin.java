package org.evochora.datapipeline.services.analytics.plugins;

import java.util.ArrayList;
import java.util.Comparator;
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
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;

import com.typesafe.config.Config;

import java.util.Collections;

/**
 * Tracks population distribution across genomes over time.
 * <p>
 * Outputs one row per genome per tick (long format), limited to top N genomes
 * plus an aggregated "other" category. The frontend chart pivots this data
 * for stacked area visualization.
 * <p>
 * <strong>Schema:</strong>
 * <ul>
 *   <li>{@code tick} - Simulation tick number</li>
 *   <li>{@code genome_label} - Base62 genome hash (6 chars) or "other"</li>
 *   <li>{@code count} - Number of living organisms with this genome</li>
 * </ul>
 * <p>
 * <strong>Configuration:</strong>
 * <ul>
 *   <li>{@code topN} - Number of top genomes to track individually (default: 10)</li>
 * </ul>
 * <p>
 * This plugin is stateful: tracks cumulative genome populations to ensure
 * consistent tracking of top genomes across ticks.
 */
public class GenomePopulationPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("genome_label", ColumnType.VARCHAR)
        .column("count", ColumnType.INTEGER)
        .build();

    /** Base62 characters for genome label encoding. */
    private static final String BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** Number of top genomes to track individually. */
    private int topN = 10;

    /** Cumulative population per genome (for consistent ranking). */
    private Map<Long, Long> cumulativePopulation;

    /** Current list of tracked genome hashes (ordered by cumulative population). */
    private List<Long> trackedGenomes;

    @Override
    public void configure(Config config) {
        super.configure(config);
        if (config.hasPath("topN")) {
            this.topN = config.getInt("topN");
        }
    }

    @Override
    public void initialize(IAnalyticsContext context) {
        super.initialize(context);
        this.cumulativePopulation = new HashMap<>();
        this.trackedGenomes = new ArrayList<>();
    }

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    @Override
    public List<Object[]> extractRows(TickData tick) {
        // Count organisms per genome hash
        HashMap<Long, Integer> genomeCounts = new HashMap<>();

        for (OrganismState org : tick.getOrganismsList()) {
            long genomeHash = org.getGenomeHash();
            if (genomeHash == 0L) {
                continue; // Skip organisms without genome hash
            }
            genomeCounts.merge(genomeHash, 1, Integer::sum);
        }

        if (genomeCounts.isEmpty()) {
            return List.of();
        }

        // Update cumulative population
        for (Map.Entry<Long, Integer> entry : genomeCounts.entrySet()) {
            cumulativePopulation.merge(entry.getKey(), (long) entry.getValue(), Long::sum);
        }

        // Rebuild tracked genomes list (top N by cumulative population)
        trackedGenomes = new ArrayList<>(cumulativePopulation.keySet());
        trackedGenomes.sort(Comparator.comparing(cumulativePopulation::get).reversed());
        if (trackedGenomes.size() > topN) {
            trackedGenomes = new ArrayList<>(trackedGenomes.subList(0, topN));
        }

        // Build output rows
        List<Object[]> rows = new ArrayList<>();
        long tickNumber = tick.getTickNumber();
        int otherCount = 0;

        for (Map.Entry<Long, Integer> entry : genomeCounts.entrySet()) {
            if (trackedGenomes.contains(entry.getKey())) {
                // Individual row for tracked genome
                rows.add(new Object[] {
                    tickNumber,
                    formatGenomeHash(entry.getKey()),
                    entry.getValue()
                });
            } else {
                // Aggregate into "other"
                otherCount += entry.getValue();
            }
        }

        // Add "other" row if there are non-tracked genomes
        if (otherCount > 0) {
            rows.add(new Object[] {
                tickNumber,
                "other",
                otherCount
            });
        }

        return rows;
    }

    /**
     * Formats a genome hash as 6-character Base62 string.
     *
     * @param hash The genome hash value
     * @return 6-character Base62 representation
     */
    private static String formatGenomeHash(long hash) {
        if (hash == 0L) {
            return "------";
        }

        StringBuilder result = new StringBuilder(6);
        long n = hash;

        // Handle negative values (treat as unsigned)
        if (n < 0) {
            n = n + Long.MIN_VALUE;
            n = n ^ Long.MIN_VALUE;
        }

        for (int i = 0; i < 6; i++) {
            int index = (int) (Math.abs(n) % 62);
            result.insert(0, BASE62_CHARS.charAt(index));
            n = n / 62;
        }

        return result.toString();
    }

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Genome Population";
        entry.description = "Population distribution across top " + topN + " genomes over time.";

        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }

        entry.visualization = new VisualizationHint();
        entry.visualization.type = "stacked-area-chart";
        entry.visualization.config = new HashMap<>();
        entry.visualization.config.put("x", "tick");
        entry.visualization.config.put("y", "count");
        entry.visualization.config.put("groupBy", "genome_label");

        return entry;
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // Track cumulative population for all unique genomes
        long estimatedUniqueGenomes = (long) (params.maxOrganisms() * 0.5);
        long bytesPerEntry = 56L; // Long key + Long value + HashMap entry overhead
        long totalBytes = estimatedUniqueGenomes * bytesPerEntry;

        String explanation = String.format("~%d estimated unique genomes × %d bytes/entry (cumulativePopulation map)",
            estimatedUniqueGenomes, bytesPerEntry);

        return Collections.singletonList(new MemoryEstimate(
            "Plugin: " + metricId,
            totalBytes,
            explanation,
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}
