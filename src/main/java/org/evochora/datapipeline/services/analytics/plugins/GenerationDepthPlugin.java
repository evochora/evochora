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

import com.typesafe.config.Config;

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
 * <p>
 * <strong>Genome depth.</strong> The chart also shows how many genome changes separate the living
 * genomes from their founders - the depth in the genome lineage rather than in the replications.
 * It writes no data of its own for that: the chart reads the genome population and the genome
 * lineage as companions and derives the depth from them. The population follows the chart's level
 * of detail, as it is a value over time; the lineage is structure and is read whole.
 */
public class GenerationDepthPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("max_depth", ColumnType.INTEGER)
        .column("avg_depth", ColumnType.DOUBLE)
        .build();

    /** Metric holding the living genomes and their carriers per recording. */
    private String populationMetricId = "genome_population";

    /** Metric holding the edges from each genome to the genome of its parent. */
    private String lineageMetricId = "genome_lineage";

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code populationMetricId} or {@code lineageMetricId} is
     *         configured empty
     */
    @Override
    public void configure(Config config) {
        super.configure(config);
        this.populationMetricId = companionMetricId(config, "populationMetricId",
            "the living genomes the genome depth is averaged over", populationMetricId);
        this.lineageMetricId = companionMetricId(config, "lineageMetricId",
            "the genome lineage the genome depth is counted in", lineageMetricId);
    }

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
        entry.description = "Lineage depth of living organisms: replications (left) and genome "
            + "changes (right) since the founders.";
        
        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }
        
        // Genome hashes leave as text: 64 bits do not survive a JavaScript number
        entry.companions = List.of(
            new ManifestEntry.Companion(populationMetricId,
                "SELECT tick, genome_hash::VARCHAR AS genome_hash, count FROM {table}", true),
            new ManifestEntry.Companion(lineageMetricId,
                "SELECT genome_hash::VARCHAR AS genome_hash, "
                    + "parent_genome_hash::VARCHAR AS parent_genome_hash, first_birth_tick FROM {table}"));

        entry.visualization = VisualizationHint.chart("line-chart", "tick")
            .with("y", List.of("max_depth", "avg_depth"))
            .with("derivedY2", "genome-depth")
            .with("populationMetric", populationMetricId)
            .with("lineageMetric", lineageMetricId);

        return entry;
    }

}

