package org.evochora.datapipeline.services.analytics.plugins;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

    /** Set of all genome hashes ever observed (for cumulative total). */
    private Set<Long> allGenomesEverSeen;

    @Override
    public void initialize(IAnalyticsContext context) {
        super.initialize(context);
        this.allGenomesEverSeen = new HashSet<>();
    }

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    @Override
    public List<Object[]> extractRows(TickData tick) {
        // Count organisms per genome hash
        HashMap<Long, Integer> genomeCounts = new HashMap<>();
        int totalOrganisms = 0;

        for (OrganismState org : tick.getOrganismsList()) {
            long genomeHash = org.getGenomeHash();
            // Skip organisms without genome hash (should not happen after implementation)
            if (genomeHash == 0L) {
                continue;
            }
            genomeCounts.merge(genomeHash, 1, Integer::sum);
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

        return Collections.singletonList(new Object[] {
            tick.getTickNumber(),
            shannonIndex,
            totalGenomes,
            activeGenomes,
            dominantShare
        });
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
        // Estimate: Each unique genome hash is a Long (8 bytes) + HashSet overhead (~40 bytes per entry)
        // Worst case: every organism that ever lived had a unique genome
        // More realistic: mutation rate means maybe 10-50% of organisms have new genomes
        // Use conservative estimate: maxOrganisms * 0.5 unique genomes
        long estimatedUniqueGenomes = (long) (params.maxOrganisms() * 0.5);
        long bytesPerEntry = 48L; // Long + HashSet entry overhead
        long totalBytes = estimatedUniqueGenomes * bytesPerEntry;

        String explanation = String.format("~%d estimated unique genomes × %d bytes/entry (HashSet<Long>)",
            estimatedUniqueGenomes, bytesPerEntry);

        return Collections.singletonList(new MemoryEstimate(
            "Plugin: " + metricId,
            totalBytes,
            explanation,
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}
