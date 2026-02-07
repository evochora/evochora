package org.evochora.datapipeline.services.analytics.plugins;

import java.util.ArrayList;
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
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;

import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Unified genome analytics plugin tracking both diversity metrics and population distribution.
 * <p>
 * Iterates the organism list <strong>once per tick</strong> to compute all genome-related metrics,
 * then produces two frontend charts via {@link #getManifestEntries()}:
 * <ol>
 *   <li><strong>Genome Diversity</strong> (line-chart): Shannon index, total/active genome counts,
 *       and dominant genome share over time.</li>
 *   <li><strong>Genome Population</strong> (stacked-area-chart): Population distribution across the
 *       top N genomes, with remaining genomes aggregated as "other".</li>
 * </ol>
 * <p>
 * <strong>Schema:</strong>
 * <ul>
 *   <li>{@code tick} - Simulation tick number</li>
 *   <li>{@code shannon_index} - Shannon diversity index (H = -&Sigma;(p&iota; &times; ln(p&iota;)))</li>
 *   <li>{@code total_genomes} - Cumulative count of unique genomes ever observed</li>
 *   <li>{@code active_genomes} - Count of genomes with at least one living organism</li>
 *   <li>{@code dominant_share} - Population share of the most common genome (0.0-1.0)</li>
 *   <li>{@code genome_data} - JSON map of genome label to count (e.g., {@code {"a3Bf2k":42,"other":5}})</li>
 * </ul>
 * <p>
 * <strong>Configuration:</strong>
 * <ul>
 *   <li>{@code topN} - Number of top genomes to track individually (default: 10)</li>
 * </ul>
 * <p>
 * <strong>Performance:</strong> This plugin outputs exactly 1 row per tick. All working collections
 * are pre-allocated and reused for zero allocation during steady-state operation.
 * <p>
 * This plugin is stateful: it tracks cumulative genome populations and all genomes ever seen.
 * Memory usage scales with unique genome count.
 */
public class GenomeAnalyticsPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("shannon_index", ColumnType.DOUBLE)
        .column("total_genomes", ColumnType.INTEGER)
        .column("active_genomes", ColumnType.INTEGER)
        .column("dominant_share", ColumnType.DOUBLE)
        .column("genome_data", ColumnType.VARCHAR)
        .build();

    /** Base62 characters for genome label encoding. */
    private static final String BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** Bytes per entry in LongOpenHashSet (long + overhead). */
    private static final int BYTES_PER_HASH_SET_ENTRY = 12;

    /** Bytes per entry in Long2LongOpenHashMap (key + value + overhead). */
    private static final int BYTES_PER_CUMULATIVE_ENTRY = 24;

    /** Bytes per entry in Long2ObjectOpenHashMap for label cache (key + String ref + String object). */
    private static final int BYTES_PER_LABEL_CACHE_ENTRY = 80;

    /** Number of top genomes to track individually. */
    private int topN = 10;

    // ========================================================================
    // Stateful Data (persists across ticks)
    // ========================================================================

    /** Set of all genome hashes ever observed (for cumulative total). */
    private LongOpenHashSet allGenomesEverSeen;

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

    /** Reusable genome hash to count map, cleared and rebuilt each tick. */
    private Long2IntOpenHashMap genomeCounts;

    /** Reusable buffer for sorting entries during rebuildTopN. */
    private ArrayList<long[]> sortBuffer;

    /** Reusable result row. Updated in place each tick. */
    private Object[] resultRow;

    /** Reusable singleton list wrapping resultRow. */
    private List<Object[]> resultList;

    /** Reusable StringBuilder for JSON serialization. */
    private StringBuilder jsonBuilder;

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
        this.allGenomesEverSeen = new LongOpenHashSet();
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
        this.resultRow = new Object[6];
        this.resultList = Collections.singletonList(resultRow);
        this.jsonBuilder = new StringBuilder(256);
    }

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    @Override
    public List<Object[]> extractRows(TickData tick) {
        // ---- Phase 1: Build genome counts (single organism iteration) ----
        genomeCounts.clear();
        int totalOrganisms = 0;

        for (OrganismState org : tick.getOrganismsList()) {
            long hash = org.getGenomeHash();
            if (hash == 0L) {
                continue;
            }
            genomeCounts.addTo(hash, 1);
            totalOrganisms++;
        }

        // ---- Phase 2: Diversity metrics ----

        // Update cumulative set
        for (LongIterator it = genomeCounts.keySet().iterator(); it.hasNext(); ) {
            allGenomesEverSeen.add(it.nextLong());
        }

        int activeGenomes = genomeCounts.size();
        int totalGenomes = allGenomesEverSeen.size();

        // Shannon index and dominant share
        double shannonIndex = 0.0;
        double dominantShare = 0.0;
        int maxCount = 0;

        if (totalOrganisms > 0) {
            for (int count : genomeCounts.values()) {
                double p = (double) count / totalOrganisms;
                shannonIndex -= p * Math.log(p);
                if (count > maxCount) {
                    maxCount = count;
                }
            }
            dominantShare = (double) maxCount / totalOrganisms;
        }

        // ---- Phase 3: Population JSON ----

        String genomeData;
        if (genomeCounts.isEmpty()) {
            genomeData = "{}";
        } else {
            // Update cumulative population and check if re-sort needed
            for (var it = genomeCounts.long2IntEntrySet().fastIterator(); it.hasNext(); ) {
                var entry = it.next();
                long hash = entry.getLongKey();
                int tickCount = entry.getIntValue();
                long newCount = cumulativePopulation.addTo(hash, tickCount) + tickCount;

                if (!trackedGenomeSet.contains(hash) && newCount > topNThreshold) {
                    topNDirty = true;
                }
            }

            if (topNDirty) {
                rebuildTopN();
            }

            // Build JSON: {"label1":count1,"label2":count2,...,"other":countN}
            jsonBuilder.setLength(0);
            jsonBuilder.append('{');
            int otherCount = 0;
            boolean first = true;

            for (var it = genomeCounts.long2IntEntrySet().fastIterator(); it.hasNext(); ) {
                var entry = it.next();
                long hash = entry.getLongKey();
                int count = entry.getIntValue();
                if (trackedGenomeSet.contains(hash)) {
                    if (!first) jsonBuilder.append(',');
                    jsonBuilder.append('"').append(getCachedLabel(hash)).append("\":").append(count);
                    first = false;
                } else {
                    otherCount += count;
                }
            }

            if (otherCount > 0) {
                if (!first) jsonBuilder.append(',');
                jsonBuilder.append("\"other\":").append(otherCount);
            }

            jsonBuilder.append('}');
            genomeData = jsonBuilder.toString();
        }

        // ---- Phase 4: Assemble result row ----
        resultRow[0] = tick.getTickNumber();
        resultRow[1] = shannonIndex;
        resultRow[2] = totalGenomes;
        resultRow[3] = activeGenomes;
        resultRow[4] = dominantShare;
        resultRow[5] = genomeData;

        return resultList;
    }

    /**
     * Rebuilds the top N genome set and updates threshold.
     * Reuses sortBuffer to minimize allocations.
     */
    private void rebuildTopN() {
        int size = cumulativePopulation.size();
        sortBuffer.clear();
        sortBuffer.ensureCapacity(size);

        for (var it = cumulativePopulation.long2LongEntrySet().fastIterator(); it.hasNext(); ) {
            var entry = it.next();
            sortBuffer.add(new long[] { entry.getLongKey(), entry.getLongValue() });
        }

        sortBuffer.sort((a, b) -> Long.compare(b[1], a[1]));

        trackedGenomeSet.clear();
        int count = 0;
        for (long[] entry : sortBuffer) {
            if (count >= topN) break;
            trackedGenomeSet.add(entry[0]);
            topNThreshold = entry[1];
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
     *
     * @param hash The genome hash to format
     * @return A 6-character Base62 string representation, or "------" for hash 0
     */
    static String formatGenomeHash(long hash) {
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
                result[i] = BASE62_CHARS.charAt((int) Long.remainderUnsigned(n, 62));
                n = Long.divideUnsigned(n, 62);
            }
        }

        return new String(result);
    }

    /**
     * Returns {@code null} since this plugin uses {@link #getManifestEntries()} instead.
     *
     * @return Always {@code null}
     */
    @Override
    public ManifestEntry getManifestEntry() {
        return null;
    }

    /**
     * Returns two manifest entries for the two charts produced by this plugin.
     * <p>
     * Both charts share the same underlying Parquet data but select different columns:
     * <ul>
     *   <li><strong>genome_diversity</strong>: Line chart showing Shannon index, genome counts,
     *       and dominant genome share.</li>
     *   <li><strong>genome_population</strong>: Stacked area chart showing population distribution
     *       across top N genomes.</li>
     * </ul>
     *
     * @return Two manifest entries (genome_diversity and genome_population)
     */
    @Override
    public List<ManifestEntry> getManifestEntries() {
        return List.of(buildDiversityManifest(), buildPopulationManifest());
    }

    /**
     * Builds the manifest entry for the genome diversity line chart.
     */
    private ManifestEntry buildDiversityManifest() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = "genome_diversity";
        entry.storageMetricId = metricId;
        entry.name = "Genome Diversity";
        entry.description = "Shannon diversity index, total/active genome counts, and dominant genome share over time.";

        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }

        entry.visualization = new VisualizationHint();
        entry.visualization.type = "line-chart";
        entry.visualization.config = new HashMap<>();
        entry.visualization.config.put("x", "tick");
        entry.visualization.config.put("y", List.of("shannon_index", "dominant_share"));
        entry.visualization.config.put("yFormat", "decimal");
        entry.visualization.config.put("y2", List.of("total_genomes", "active_genomes"));
        entry.visualization.config.put("y2Format", "integer");

        return entry;
    }

    /**
     * Builds the manifest entry for the genome population stacked area chart.
     */
    private ManifestEntry buildPopulationManifest() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = "genome_population";
        entry.storageMetricId = metricId;
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
        entry.visualization.config.put("jsonColumn", "genome_data");
        entry.visualization.config.put("yFormat", "integer");

        return entry;
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        long estimatedUniqueGenomes = (long) (params.maxOrganisms() * 0.5);

        long hashSetBytes = estimatedUniqueGenomes * BYTES_PER_HASH_SET_ENTRY;
        long cumulativeBytes = estimatedUniqueGenomes * BYTES_PER_CUMULATIVE_ENTRY;
        long labelCacheBytes = estimatedUniqueGenomes * BYTES_PER_LABEL_CACHE_ENTRY;
        long sortBufferBytes = estimatedUniqueGenomes * 16L;
        long genomeCountsBytes = estimatedUniqueGenomes * 12L;

        long totalBytes = hashSetBytes + cumulativeBytes + labelCacheBytes + sortBufferBytes + genomeCountsBytes;

        String explanation = String.format(
            "~%d unique genomes: allGenomesEverSeen=%s, cumulativePopulation=%s, labelCache=%s, sortBuffer=%s, genomeCounts=%s",
            estimatedUniqueGenomes,
            SimulationParameters.formatBytes(hashSetBytes),
            SimulationParameters.formatBytes(cumulativeBytes),
            SimulationParameters.formatBytes(labelCacheBytes),
            SimulationParameters.formatBytes(sortBufferBytes),
            SimulationParameters.formatBytes(genomeCountsBytes)
        );

        return Collections.singletonList(new MemoryEstimate(
            "Plugin: " + metricId,
            totalBytes,
            explanation,
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}
