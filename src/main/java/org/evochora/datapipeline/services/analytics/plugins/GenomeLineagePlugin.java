package org.evochora.datapipeline.services.analytics.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.analytics.AbstractAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
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
 * shows, and which no population count carries: with no edge written down, the only way back to
 * it is following organisms one at a time.
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
 * A child carrying its parent's genome unchanged produces no row: that is one genome appearing
 * again, not a descent between two, and an edge from a genome to itself would make it its own
 * ancestor. Rows therefore appear where a genome is new, which is what a tree of descent is made
 * of.
 * <p>
 * <strong>One level of detail, every recorded tick.</strong> Both follow from the same thing: a
 * lineage is a structure, not a quantity. Leaving rows out does not coarsen it, it removes edges
 * and turns descendants into roots. Neither value is therefore configurable; the plugin sets them.
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
    protected Fixed fixedSamplingInterval() {
        return new Fixed(1, "it reports the organisms born since the previous recording, so a "
            + "skipped recording drops a whole birth window and the genomes born in it are missing "
            + "from the tree rather than late");
    }

    @Override
    protected Fixed fixedLodLevels() {
        return new Fixed(1, "a level of detail selects rows, and a selection from a lineage is a "
            + "tree with edges missing, in which descendants look like roots");
    }

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
            // A child that carries its parent's genome unchanged - the common case - is the same
            // genome, not a descent between two. Written as an edge it would make the genome its
            // own ancestor and it could never be a root of the tree.
            if (org.hasParentGenomeHash() && org.getParentGenomeHash() == org.getGenomeHash()) {
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
     * The table has no chart of its own. Counting its rows per recording would show how many
     * genomes appeared for the first time, which the genome diversity chart already carries as the
     * running total of genomes ever seen. What the table is for is being read as a whole: it is
     * the tree the clade view groups the population by.
     */
    @Override
    public ManifestEntry getManifestEntry() {
        return null;
    }
}
