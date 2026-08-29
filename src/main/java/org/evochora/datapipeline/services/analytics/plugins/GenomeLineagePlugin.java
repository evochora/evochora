package org.evochora.datapipeline.services.analytics.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.analytics.AbstractAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.analytics.VisualizationHint;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;

/**
 * Records which genome descends from which, so that the lineage of a run can be reconstructed
 * without asking a running node about individual organisms.
 * <p>
 * <strong>Metrics:</strong>
 * <ul>
 *   <li>{@code tick} - the recording the genome first appeared in</li>
 *   <li>{@code genome_hash} - the genome</li>
 *   <li>{@code parent_genome_hash} - the genome it arose from, {@code NULL} for a founding
 *       organism and {@code 0} when the parent carried no genome at all</li>
 *   <li>{@code first_birth_tick} - the earliest birth among its carriers in that recording</li>
 * </ul>
 * <p>
 * <strong>What it is for.</strong> A selective sweep is one genome's descendants displacing
 * everything else. Seeing it requires knowing who descends from whom - which no aggregate curve
 * shows, and which was previously reconstructed from organism snapshots at the cost of dozens of
 * serial requests against a running node.
 * <p>
 * <strong>Why a row per birth window rather than per living organism.</strong> Writing every
 * living genome at every recording would repeat the same edge thousands of times. Instead only
 * organisms born since the previous recording are considered: every organism falls into exactly
 * one such window, so every genome is written when it first appears, and the table stays roughly
 * as large as the number of genomes the run produced.
 * <p>
 * That reasoning is why this plugin reads every recorded tick rather than following the tuning
 * profile - a skipped recording would drop a whole birth window, and the genomes born in it would
 * be missing from the tree, not merely late. Dead organisms are read as well: a genome whose
 * carriers all died within one window would otherwise never be recorded.
 * <p>
 * Organisms without genome molecules are not genomes and get no row. They can still be a parent:
 * their children are written with a parent genome of 0, which the reconstruction has to treat as a
 * root rather than as a founder.
 */
public class GenomeLineagePlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("genome_hash", ColumnType.BIGINT)
        .column("parent_genome_hash", ColumnType.BIGINT)
        .column("first_birth_tick", ColumnType.BIGINT)
        .build();

    /** One genome and the genome it arose from; {@code parentGenome} is null for a founder. */
    private record Edge(long genome, Long parentGenome) { }

    /** Reused across ticks; holds the earliest birth per edge seen in the current recording. */
    private final Map<Edge, Long> edgesInTick = new LinkedHashMap<>();

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Reports the genomes of the organisms born since the previous recording. Returns no row when
     * none were born, which for a run past its early growth is the common case.
     */
    @Override
    public List<Object[]> extractRows(TickData tick) {
        long bornAfter = tick.getTickNumber() - getEffectiveSamplingInterval(0);
        edgesInTick.clear();

        for (OrganismState org : tick.getOrganismsList()) {
            if (org.getGenomeHash() == 0L || org.getBirthTick() <= bornAfter) {
                continue;
            }
            Edge edge = new Edge(org.getGenomeHash(),
                org.hasParentGenomeHash() ? org.getParentGenomeHash() : null);
            edgesInTick.merge(edge, org.getBirthTick(), Math::min);
        }

        if (edgesInTick.isEmpty()) {
            return Collections.emptyList();
        }

        List<Object[]> rows = new ArrayList<>(edgesInTick.size());
        for (Map.Entry<Edge, Long> entry : edgesInTick.entrySet()) {
            rows.add(new Object[] {
                tick.getTickNumber(),
                entry.getKey().genome(),
                entry.getKey().parentGenome(),
                entry.getValue()
            });
        }
        return rows;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The chart counts the rows per recording, which is the number of genomes the run produced in
     * that window. The table itself is not a time series - it is read as a whole to build the
     * tree - which is also why this metric keeps a single level of detail: a coarser one would
     * hold a subset of the edges, and a tree missing edges is worse than no tree, because
     * descendants silently turn into roots.
     */
    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "New Genomes";
        entry.description = "How many genomes appeared for the first time in each recording. The "
            + "underlying table holds one row per genome with the genome it arose from.";

        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }

        entry.generatedQuery = "SELECT tick, count(*)::BIGINT AS new_genomes FROM {table} "
            + "GROUP BY tick ORDER BY tick";
        entry.outputColumns = List.of("tick", "new_genomes");

        entry.visualization = new VisualizationHint();
        entry.visualization.type = "line-chart";
        entry.visualization.config = new HashMap<>();
        entry.visualization.config.put("x", "tick");
        entry.visualization.config.put("y", List.of("new_genomes"));
        entry.visualization.config.put("yFormat", "integer");

        return entry;
    }
}
