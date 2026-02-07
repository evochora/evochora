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
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Tracks genome diversity metrics over time.
 * <p>
 * <strong>Metrics:</strong>
 * <ul>
 *   <li>{@code tick} - Simulation tick number</li>
 *   <li>{@code shannon_index} - Shannon diversity index (H = -Σ(pᵢ × ln(pᵢ)))</li>
 *   <li>{@code total_genomes} - Cumulative count of unique genomes ever observed</li>
 *   <li>{@code active_genomes} - Count of genomes with at least one living organism</li>
 *   <li>{@code dominant_share} - Population share of the most common genome (0.0-1.0)</li>
 * </ul>
 * <p>
 * <strong>Shannon Index:</strong> Measures both the number of different genomes and their
 * evenness of distribution. H=0 means only one genome exists. Higher values indicate
 * greater diversity.
 * <p>
 * <strong>Performance:</strong> This plugin is optimized for zero allocation during
 * steady-state operation. All working collections are pre-allocated and reused.
 * <p>
 * This plugin is stateful: it tracks all genome hashes ever seen to compute
 * cumulative totals. Memory usage scales with unique genome count.
 */
public class GenomeDiversityPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("shannon_index", ColumnType.DOUBLE)
        .column("total_genomes", ColumnType.INTEGER)
        .column("active_genomes", ColumnType.INTEGER)
        .column("dominant_share", ColumnType.DOUBLE)
        .build();

    /** Bytes per entry in LongOpenHashSet (long + overhead). */
    private static final int BYTES_PER_HASH_SET_ENTRY = 12;

    /** Bytes per entry in Long2IntOpenHashMap for per-tick counts. */
    private static final int BYTES_PER_TICK_COUNT_ENTRY = 16;

    // ========================================================================
    // Stateful Data (persists across ticks)
    // ========================================================================

    /** Set of all genome hashes ever observed (for cumulative total). */
    private LongOpenHashSet allGenomesEverSeen;

    // ========================================================================
    // Reusable Working Memory (zero allocation per tick)
    // ========================================================================

    /** Reusable map for per-tick genome counts. Cleared at start of each extractRows call. */
    private Long2IntOpenHashMap genomeCounts;

    /** Reusable result row. Updated in place each tick. */
    private Object[] resultRow;

    /** Reusable singleton list wrapping resultRow. */
    private List<Object[]> resultList;

    @Override
    public void initialize(IAnalyticsContext context) {
        super.initialize(context);

        // Stateful data
        this.allGenomesEverSeen = new LongOpenHashSet();

        // Reusable working memory
        this.genomeCounts = new Long2IntOpenHashMap();
        this.genomeCounts.defaultReturnValue(0);
        this.resultRow = new Object[5];
        this.resultList = Collections.singletonList(resultRow);
    }

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    @Override
    public List<Object[]> extractRows(TickData tick) {
        // Clear reusable collection
        genomeCounts.clear();
        int totalOrganisms = 0;

        // Count organisms per genome hash (zero allocation)
        for (OrganismState org : tick.getOrganismsList()) {
            long genomeHash = org.getGenomeHash();
            if (genomeHash == 0L) {
                continue;
            }
            genomeCounts.addTo(genomeHash, 1);
            totalOrganisms++;
            allGenomesEverSeen.add(genomeHash);
        }

        // Calculate metrics
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

        // Update result row in place (zero allocation)
        resultRow[0] = tick.getTickNumber();
        resultRow[1] = shannonIndex;
        resultRow[2] = totalGenomes;
        resultRow[3] = activeGenomes;
        resultRow[4] = dominantShare;

        return resultList;
    }

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
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
        entry.visualization.config.put("y2", List.of("total_genomes", "active_genomes"));

        return entry;
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // Estimate: 50% of all organisms will have unique genomes over simulation lifetime
        long estimatedUniqueGenomes = (long) (params.maxOrganisms() * 0.5);

        // Stateful data: LongOpenHashSet grows with unique genomes
        long hashSetBytes = estimatedUniqueGenomes * BYTES_PER_HASH_SET_ENTRY;

        // Reusable working memory: bounded by organisms per tick
        long genomeCountsBytes = params.maxOrganisms() * BYTES_PER_TICK_COUNT_ENTRY;

        long totalBytes = hashSetBytes + genomeCountsBytes;

        String explanation = String.format(
            "~%d unique genomes: allGenomesEverSeen=%s, genomeCounts=%s",
            estimatedUniqueGenomes,
            SimulationParameters.formatBytes(hashSetBytes),
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
