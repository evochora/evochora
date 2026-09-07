package org.evochora.datapipeline.services.analytics.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.evochora.datapipeline.api.analytics.AbstractAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.IAnalyticsContext;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.contracts.MutationEvent;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.utils.MetadataConfigHelper;
import org.evochora.runtime.model.EnvironmentProperties;

/**
 * Records what the mutation plugins did to each newborn, one row per changed molecule.
 * <p>
 * <strong>Metrics:</strong>
 * <ul>
 *   <li>{@code tick} - the recording the event was reported in</li>
 *   <li>{@code birth_tick}, {@code organism_id}, {@code parent_id} - the newborn and its parent</li>
 *   <li>{@code genome_hash}, {@code parent_genome_hash} - its genome and the parent's at the
 *       birth, so a row joins against the lineage and population tables</li>
 *   <li>{@code event_index} - ordinal of the event within this birth, in the order the plugins
 *       reported it</li>
 *   <li>{@code plugin_class}, {@code kind} - which plugin did it and what it calls the operation</li>
 *   <li>{@code position} - where the molecule sits relative to the organism's own origin, as a
 *       JSON list such as {@code [13,4]}; DuckDB reads it as a typed list with
 *       {@code position::INTEGER[]}</li>
 *   <li>{@code old_value}, {@code new_value} - the packed molecule before and after the write</li>
 * </ul>
 * <p>
 * <strong>What it is for.</strong> Without it, what a mutation did has to be reconstructed by
 * diffing a child's body against its parent's, which needs both bodies recorded and produces
 * heuristics rather than facts. With the writes themselves in a table, a mutation class against
 * fate is a join, and the births whose genome changed without any plugin event are what remains
 * when the recorded events are subtracted - the copy channel, which no reconstruction names.
 * <p>
 * <strong>Relative positions.</strong> A mutation an ancestor received sits at the same offset from
 * every descendant's origin, so an offset is what makes the events of a lineage comparable at all.
 * The offset is taken along the shortest way around the world, by the rule the genome hash is built
 * with, so that the positions of a row and of the hash it carries agree.
 * <p>
 * <strong>Why it must see every recording.</strong> The engine writes an organism's events with the
 * first recording after its birth and drops them afterwards. A plugin that skips that recording
 * does not see the events later - it never sees them.
 * <p>
 * An event without cells changed no molecule and therefore produces no row.
 */
public class MutationEventsPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("birth_tick", ColumnType.BIGINT)
        .column("organism_id", ColumnType.INTEGER)
        .column("parent_id", ColumnType.INTEGER)
        .column("genome_hash", ColumnType.BIGINT)
        .column("parent_genome_hash", ColumnType.BIGINT)
        .column("event_index", ColumnType.INTEGER)
        .column("plugin_class", ColumnType.VARCHAR)
        .column("kind", ColumnType.VARCHAR)
        .column("position", ColumnType.VARCHAR)
        .column("old_value", ColumnType.INTEGER)
        .column("new_value", ColumnType.INTEGER)
        .build();

    /**
     * The world the run took place in, needed to turn a flat index into a coordinate. Derived once
     * from the run metadata; {@code null} until a context carrying metadata has been supplied.
     */
    private EnvironmentProperties environmentProperties;

    /** Reused across rows so that turning a flat index into a coordinate allocates nothing. */
    private int[] cellCoordinate;

    @Override
    protected Fixed fixedSamplingInterval() {
        return new Fixed(1, "an organism's mutation events are written with the first recording "
            + "after its birth and dropped afterwards, so a skipped recording loses them for good "
            + "- they are events, not a state that can be sampled");
    }

    @Override
    protected Fixed fixedLodLevels() {
        return new Fixed(1, "a level of detail selects rows, and a selection from a table of "
            + "individual writes is not a coarser picture of them but a different set of them");
    }

    /**
     * {@inheritDoc}
     * <p>
     * Derives the world shape and topology from the run metadata, which every relative position
     * this metric writes is computed with.
     */
    @Override
    public void initialize(IAnalyticsContext context) {
        super.initialize(context);
        if (context != null) {
            this.environmentProperties = new EnvironmentProperties(
                MetadataConfigHelper.getEnvironmentShape(context.getMetadata()),
                MetadataConfigHelper.isEnvironmentToroidal(context.getMetadata()));
            this.cellCoordinate = new int[environmentProperties.getDimensions()];
        }
    }

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Writes one row per molecule the mutation plugins changed at a birth reported in this
     * recording. Returns no row when the recording carries no event with cells, which for a run
     * past its early growth is the common case.
     *
     * @throws IllegalStateException if the plugin was initialized without an analytics context,
     *         since the world shape a position is computed with would then be unknown
     */
    @Override
    public List<Object[]> extractRows(TickData tick) {
        List<Object[]> rows = null;
        for (OrganismState org : tick.getOrganismsList()) {
            int eventIndex = 0;
            for (MutationEvent event : org.getBirthMutationsList()) {
                for (int i = 0; i < event.getCellsCount(); i++) {
                    if (rows == null) {
                        rows = new ArrayList<>();
                    }
                    rows.add(new Object[] {
                        tick.getTickNumber(),
                        org.getBirthTick(),
                        org.getOrganismId(),
                        org.hasParentId() ? org.getParentId() : null,
                        org.getGenomeHash(),
                        org.hasParentGenomeHash() ? org.getParentGenomeHash() : null,
                        eventIndex,
                        event.getPluginClass(),
                        event.getKind(),
                        relativePosition(event.getCells(i), org),
                        event.getOldValues(i),
                        event.getNewValues(i)
                    });
                }
                eventIndex++;
            }
        }
        return rows == null ? Collections.emptyList() : rows;
    }

    /**
     * Renders a cell's position relative to the organism's own origin as a JSON list.
     *
     * @param flatIndex the cell's absolute flat index, as the reporting plugin held it
     * @param org the organism the event belongs to, for its initial position
     * @return the offset per dimension, for example {@code [13,4]}
     * @throws IllegalStateException if the plugin has no world to convert the index in, or the
     *         organism states an origin that does not fit that world
     */
    private String relativePosition(int flatIndex, OrganismState org) {
        if (environmentProperties == null) {
            throw new IllegalStateException("Metric '" + metricId + "': the world shape is "
                + "unavailable because the plugin was initialized without an analytics context.");
        }
        if (org.getInitialPosition().getComponentsCount() != cellCoordinate.length) {
            throw new IllegalStateException("Metric '" + metricId + "': organism "
                + org.getOrganismId() + " states an initial position with "
                + org.getInitialPosition().getComponentsCount() + " components in a "
                + cellCoordinate.length + "-dimensional world, so no offset can be computed for it");
        }
        environmentProperties.flatIndexToCoordinates(flatIndex, cellCoordinate);
        StringBuilder text = new StringBuilder(2 + 4 * cellCoordinate.length);
        text.append('[');
        for (int d = 0; d < cellCoordinate.length; d++) {
            if (d > 0) {
                text.append(',');
            }
            text.append(EnvironmentProperties.relativeOffset(
                cellCoordinate[d],
                org.getInitialPosition().getComponents(d),
                environmentProperties.getDimensionSize(d),
                environmentProperties.isToroidal()));
        }
        return text.append(']').toString();
    }

    /**
     * {@inheritDoc}
     * <p>
     * The table has no chart of its own. What it holds are individual writes, and a curve over
     * them would be a count of mutations, which the birth and genome metrics already carry. What
     * the table is for is being read as a whole, joined against the lineage by genome hash.
     */
    @Override
    public ManifestEntry getManifestEntry() {
        return null;
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // The rows of one recording, held until the indexer has written them. 17 rows stands for a
        // median duplication on every organism the recording could report as newborn, and 120
        // bytes for one row's object array with its boxed numbers and its two shared strings.
        long rowBytes = params.maxOrganisms() * 17L * 120L;
        return Collections.singletonList(new MemoryEstimate(
            "Plugin: " + metricId,
            rowBytes,
            String.format("%d max organisms × ~17 changed molecules × ~120 bytes/row",
                params.maxOrganisms()),
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}
