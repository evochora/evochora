package org.evochora.datapipeline.services.analytics.plugins;

import java.util.ArrayList;
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
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;

import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

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
 * <strong>Performance:</strong> This plugin is optimized for zero allocation during
 * steady-state operation. All working collections are pre-allocated and reused.
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

    /** Bytes per entry in Long2LongOpenHashMap (key + value + overhead). */
    private static final int BYTES_PER_CUMULATIVE_ENTRY = 24;

    /** Bytes per entry in Long2ObjectOpenHashMap for label cache (key + String ref + String object). */
    private static final int BYTES_PER_LABEL_CACHE_ENTRY = 80;

    /** Bytes per entry in Long2IntOpenHashMap for per-tick counts. */
    private static final int BYTES_PER_TICK_COUNT_ENTRY = 16;

    /** Number of top genomes to track individually. */
    private int topN = 10;

    // ========================================================================
    // Stateful Data (persists across ticks)
    // ========================================================================

    /** Cumulative population per genome (for consistent ranking). */
    private Long2LongOpenHashMap cumulativePopulation;

    /** Current set of tracked genome hashes (for O(1) lookup). */
    private LongOpenHashSet trackedGenomeSet;

    /** Cache for Base62 labels (hash -> label). Uses primitive map to avoid boxing. */
    private Long2ObjectOpenHashMap<String> labelCache;

    /** Threshold for re-sorting: minimum cumulative count in current top N. */
    private long topNThreshold;

    /** Flag indicating if top N needs recalculation. */
    private boolean topNDirty;

    // ========================================================================
    // Reusable Working Memory (zero allocation per tick)
    // ========================================================================

    /** Reusable map for per-tick genome counts. Cleared at start of each extractRows call. */
    private Long2IntOpenHashMap genomeCounts;

    /** Reusable buffer for sorting entries during rebuildTopN. */
    private ArrayList<long[]> sortBuffer;

    /** Reusable result list. Cleared at start of each extractRows call. */
    private ArrayList<Object[]> resultRows;

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

        // Stateful data
        this.cumulativePopulation = new Long2LongOpenHashMap();
        this.cumulativePopulation.defaultReturnValue(0L);
        this.trackedGenomeSet = new LongOpenHashSet(topN);
        this.labelCache = new Long2ObjectOpenHashMap<>();

        this.topNThreshold = 0;
        this.topNDirty = true;

        // Reusable working memory
        this.genomeCounts = new Long2IntOpenHashMap();
        this.genomeCounts.defaultReturnValue(0);
        this.sortBuffer = new ArrayList<>();
        this.resultRows = new ArrayList<>(topN + 1); // topN + "other"
    }

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    @Override
    public List<Object[]> extractRows(TickData tick) {
        // Clear reusable collections
        genomeCounts.clear();
        resultRows.clear();

        // Count organisms per genome hash (zero allocation)
        for (OrganismState org : tick.getOrganismsList()) {
            long genomeHash = org.getGenomeHash();
            if (genomeHash == 0L) {
                continue;
            }
            genomeCounts.addTo(genomeHash, 1);
        }

        if (genomeCounts.isEmpty()) {
            return List.of();
        }

        // Update cumulative population and check if re-sort needed
        for (var it = genomeCounts.long2IntEntrySet().fastIterator(); it.hasNext(); ) {
            var entry = it.next();
            long hash = entry.getLongKey();
            int tickCount = entry.getIntValue();
            long newCount = cumulativePopulation.addTo(hash, tickCount) + tickCount;

            // Check if this genome could now enter top N
            if (!trackedGenomeSet.contains(hash) && newCount > topNThreshold) {
                topNDirty = true;
            }
        }

        // Rebuild top N only when needed
        if (topNDirty) {
            rebuildTopN();
        }

        // Build output rows
        long tickNumber = tick.getTickNumber();
        int otherCount = 0;

        for (var it = genomeCounts.long2IntEntrySet().fastIterator(); it.hasNext(); ) {
            var entry = it.next();
            long hash = entry.getLongKey();
            int count = entry.getIntValue();
            if (trackedGenomeSet.contains(hash)) {
                resultRows.add(new Object[] {
                    tickNumber,
                    getCachedLabel(hash),
                    count
                });
            } else {
                otherCount += count;
            }
        }

        if (otherCount > 0) {
            resultRows.add(new Object[] {
                tickNumber,
                "other",
                otherCount
            });
        }

        return resultRows;
    }

    /**
     * Rebuilds the top N genome set and updates threshold.
     * Reuses sortBuffer to minimize allocations.
     */
    private void rebuildTopN() {
        // Reuse sort buffer, resize only if needed
        int size = cumulativePopulation.size();
        sortBuffer.clear();
        sortBuffer.ensureCapacity(size);

        // Collect entries - must allocate long[] for each entry (no way around this)
        for (var it = cumulativePopulation.long2LongEntrySet().fastIterator(); it.hasNext(); ) {
            var entry = it.next();
            sortBuffer.add(new long[] { entry.getLongKey(), entry.getLongValue() });
        }

        // Sort by value descending
        sortBuffer.sort((a, b) -> Long.compare(b[1], a[1]));

        trackedGenomeSet.clear();
        int count = 0;
        for (long[] entry : sortBuffer) {
            if (count >= topN) break;
            trackedGenomeSet.add(entry[0]);
            topNThreshold = entry[1]; // Last one added = minimum
            count++;
        }

        topNDirty = false;
    }

    /**
     * Gets cached Base62 label for a genome hash.
     * Uses primitive Long2ObjectOpenHashMap to avoid boxing.
     */
    private String getCachedLabel(long hash) {
        String label = labelCache.get(hash);
        if (label == null) {
            label = formatGenomeHash(hash);
            labelCache.put(hash, label);
        }
        return label;
    }

    /**
     * Formats a genome hash as 6-character Base62 string.
     * Uses unsigned interpretation of the 64-bit hash value.
     */
    private static String formatGenomeHash(long hash) {
        if (hash == 0L) {
            return "------";
        }

        char[] result = new char[6];
        long n = hash;

        for (int i = 5; i >= 0; i--) {
            if (n >= 0) {
                result[i] = BASE62_CHARS.charAt((int) (n % 62));
                n = n / 62;
            } else {
                // Unsigned division for negative longs
                result[i] = BASE62_CHARS.charAt((int) Long.remainderUnsigned(n, 62));
                n = Long.divideUnsigned(n, 62);
            }
        }

        return new String(result);
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
        // Estimate: 50% of all organisms will have unique genomes over simulation lifetime
        long estimatedUniqueGenomes = (long) (params.maxOrganisms() * 0.5);

        // Stateful data structures (grow with unique genomes)
        long cumulativeBytes = estimatedUniqueGenomes * BYTES_PER_CUMULATIVE_ENTRY;
        long labelCacheBytes = estimatedUniqueGenomes * BYTES_PER_LABEL_CACHE_ENTRY;

        // Reusable working memory (bounded by organisms per tick, not cumulative)
        long genomeCountsBytes = params.maxOrganisms() * BYTES_PER_TICK_COUNT_ENTRY;
        long sortBufferBytes = estimatedUniqueGenomes * 16L; // long[] with 2 elements

        long totalBytes = cumulativeBytes + labelCacheBytes + genomeCountsBytes + sortBufferBytes;

        String explanation = String.format(
            "~%d unique genomes: cumulativePopulation=%s, labelCache=%s, genomeCounts=%s, sortBuffer=%s",
            estimatedUniqueGenomes,
            SimulationParameters.formatBytes(cumulativeBytes),
            SimulationParameters.formatBytes(labelCacheBytes),
            SimulationParameters.formatBytes(genomeCountsBytes),
            SimulationParameters.formatBytes(sortBufferBytes)
        );

        return Collections.singletonList(new MemoryEstimate(
            "Plugin: " + metricId,
            totalBytes,
            explanation,
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}
