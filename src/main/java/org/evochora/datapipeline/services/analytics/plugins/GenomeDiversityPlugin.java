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

/**
 * Measures how varied the living population is, in four numbers per recording.
 * <p>
 * <strong>What these numbers can and cannot show.</strong> They describe the spread of the
 * population at one moment, and they do it without knowing which genome descends from which.
 * That makes them blind to displacement: where mutation creates new genomes faster than selection
 * removes them, a branch can take over the population while {@code dominant_share} stays low and
 * {@code shannon_index} keeps rising, because every carrier of the winning branch carries a
 * slightly different genome. Displacement is visible in the genome population next to the lineage,
 * not here.
 * <p>
 * <strong>Performance:</strong> One row per tick, one pass over the organisms, and a counting map
 * reused across ticks.
 */
public class GenomeDiversityPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder(
            "How varied the genomes of the living organisms are. One row per recorded tick. Living "
            + "organisms without a genome are left out of every value.")
        .column("tick", ColumnType.BIGINT, "The recorded tick the row describes.")
        .column("shannon_index", ColumnType.DOUBLE,
            "Shannon index of the living genomes: minus the sum over the genomes of p times ln(p), "
            + "where p is the share of a genome among the living organisms that carry one. 0 when "
            + "all carry the same genome.")
        .column("total_genomes", ColumnType.INTEGER,
            "Distinct genomes the run has produced so far.")
        .column("active_genomes", ColumnType.INTEGER,
            "Distinct genomes with at least one living carrier.")
        .column("dominant_share", ColumnType.DOUBLE,
            "Share of the most common genome among the living organisms that carry one, from 0 to 1.")
        .build();

    /** Bytes per entry of the counting map: key, value and open-addressing overhead. */
    private static final int BYTES_PER_COUNT_ENTRY = 12;

    /** Reusable genome hash to count map, cleared and rebuilt each tick. */
    private Long2IntOpenHashMap genomeCounts;

    /** Reusable result row. Updated in place each tick. */
    private Object[] resultRow;

    /** Reusable singleton list wrapping resultRow. */
    private List<Object[]> resultList;

    @Override
    public void initialize(IAnalyticsContext context) {
        super.initialize(context);

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
        genomeCounts.clear();
        int totalOrganisms = 0;

        for (OrganismState org : tick.getOrganismsList()) {
            if (org.getIsDead()) continue;
            long hash = org.getGenomeHash();
            if (hash == 0L) {
                continue;
            }
            genomeCounts.addTo(hash, 1);
            totalOrganisms++;
        }

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

        resultRow[0] = tick.getTickNumber();
        resultRow[1] = shannonIndex;
        resultRow[2] = (int) tick.getTotalUniqueGenomes();
        resultRow[3] = genomeCounts.size();
        resultRow[4] = dominantShare;

        return resultList;
    }

    @Override
    public ManifestEntry getManifestEntry() {
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

        entry.visualization = VisualizationHint.chart("line-chart", "tick")
            .with("y", List.of("shannon_index", "dominant_share"))
            .with("yFormat", "decimal")
            .with("y2", List.of("total_genomes", "active_genomes"))
            .with("y2Format", "integer");

        return entry;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Every organism could carry a genome of its own, so the counting map is bounded by the
     * organism limit.
     */
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        long countBytes = params.maxOrganisms() * (long) BYTES_PER_COUNT_ENTRY;
        return Collections.singletonList(new MemoryEstimate(
            "Plugin: " + metricId,
            countBytes,
            String.format("%d max organisms × %d bytes/genome count",
                params.maxOrganisms(), BYTES_PER_COUNT_ENTRY),
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}
