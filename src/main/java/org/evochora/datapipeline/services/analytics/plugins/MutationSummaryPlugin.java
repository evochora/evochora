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

/**
 * Records what the mutation plugins did to each newborn, one row per event.
 * <p>
 * <strong>Metrics:</strong>
 * <ul>
 *   <li>{@code tick} - the recording the event was reported in</li>
 *   <li>{@code birth_tick}, {@code organism_id} - the newborn that received it</li>
 *   <li>{@code genome_hash}, {@code parent_genome_hash} - its genome and the parent's at the
 *       birth, so a row joins against the lineage and population tables</li>
 *   <li>{@code event_index} - ordinal of the event within this birth, in the order the plugins
 *       reported it</li>
 *   <li>{@code plugin_class}, {@code kind} - which plugin did it and what it calls the operation</li>
 *   <li>{@code cell_count} - how many molecules it wrote, {@code 0} for an event that wrote
 *       none</li>
 *   <li>{@code position} - the smallest of the offsets of its cells from the organism's own
 *       origin, as a JSON list such as {@code [13,4]}, empty for an event without cells</li>
 *   <li>{@code dv} - the newborn's direction vector when the plugin ran, as a JSON list</li>
 *   <li>{@code params} - the numbers the plugin documents for its kind, as a JSON list</li>
 * </ul>
 * <p>
 * <strong>Why beside the per-molecule table.</strong> Asking what founded a genome is asking about
 * events, not about molecules, and the answer has to be found in the browser, where a hash
 * aggregation over an unsorted column fails beyond a few thousand rows. Condensing the writes of a
 * run into events at read time is exactly that aggregation; condensing them while writing costs one
 * row per event instead of one per written molecule, which is what makes the table small enough to
 * be read whole. A plugin has one schema and one metric id, so the condensed form is a table of its
 * own, over the same events the per-molecule table reads.
 * <p>
 * <strong>The smallest position.</strong> One position stands for an event that wrote a chain of
 * cells, and the smallest offset is the one that does not depend on which end the plugin walked
 * from: it is the same cell whichever direction the newborn faced.
 * <p>
 * <strong>Why it must see every recording.</strong> The engine writes an organism's events with the
 * first recording after its birth and drops them afterwards. A plugin that skips that recording
 * does not see the events later - it never sees them.
 * <p>
 * An event without cells - the label mask, which changes every label by the same amount and no
 * molecule of its own - keeps its row with a cell count of zero, so that what a birth reported is
 * complete and a consumer decides for itself what counts as a cause.
 */
public class MutationSummaryPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("birth_tick", ColumnType.BIGINT)
        .column("organism_id", ColumnType.INTEGER)
        .column("genome_hash", ColumnType.BIGINT)
        .column("parent_genome_hash", ColumnType.BIGINT)
        .column("event_index", ColumnType.INTEGER)
        .column("plugin_class", ColumnType.VARCHAR)
        .column("kind", ColumnType.VARCHAR)
        .column("cell_count", ColumnType.INTEGER)
        .column("position", ColumnType.VARCHAR)
        .column("dv", ColumnType.VARCHAR)
        .column("params", ColumnType.VARCHAR)
        .build();

    /** The position an event without cells carries: it wrote nowhere, so it names no place. */
    private static final String NO_POSITION = "";

    /**
     * Turns the flat index of a written cell into an offset from the newborn's origin. Derived once
     * from the run metadata; {@code null} until a context carrying metadata has been supplied.
     */
    private RelativeCellPositions positions;

    /** Holds the smallest offset seen while walking an event's cells. */
    private int[] smallest;

    /** Holds the offset of the cell being compared against it. */
    private int[] candidate;

    @Override
    protected Fixed fixedSamplingInterval() {
        return new Fixed(1, "an organism's mutation events are written with the first recording "
            + "after its birth and dropped afterwards, so a skipped recording loses them for good "
            + "- they are events, not a state that can be sampled");
    }

    @Override
    protected Fixed fixedLodLevels() {
        return new Fixed(1, "a level of detail selects rows, and a selection from a table of "
            + "individual events is not a coarser picture of them but a different set of them");
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
            this.positions = new RelativeCellPositions(metricId,
                MetadataConfigHelper.environmentProperties(context.getMetadata()));
            this.smallest = new int[positions.dimensions()];
            this.candidate = new int[positions.dimensions()];
        }
    }

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Writes one row per event the mutation plugins reported at a birth in this recording. Returns
     * no row when the recording carries no event at all, which for a run past its early growth is
     * the common case.
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
                if (rows == null) {
                    rows = new ArrayList<>();
                }
                rows.add(new Object[] {
                    tick.getTickNumber(),
                    org.getBirthTick(),
                    org.getOrganismId(),
                    org.getGenomeHash(),
                    org.hasParentGenomeHash() ? org.getParentGenomeHash() : null,
                    eventIndex,
                    event.getPluginClass(),
                    event.getKind(),
                    event.getCellsCount(),
                    smallestPosition(event, org),
                    RelativeCellPositions.asJsonList(event.getDvList()),
                    RelativeCellPositions.asJsonList(event.getParamsList())
                });
                eventIndex++;
            }
        }
        return rows == null ? Collections.emptyList() : rows;
    }

    /**
     * Renders the smallest of an event's cell positions as a JSON list.
     * <p>
     * Smallest is meant component by component, in the order the components have: the offsets are
     * compared like words, so the one cell an event is named by follows from the positions alone.
     *
     * @param event the event to place, with the cells as the reporting plugin held them
     * @param org the organism the event belongs to, for its initial position
     * @return the smallest offset per dimension, for example {@code [13,4]}, or an empty string for
     *         an event that wrote no cell
     * @throws IllegalStateException if the plugin has no world to convert the indices in, or the
     *         organism states an origin that does not fit that world
     */
    private String smallestPosition(MutationEvent event, OrganismState org) {
        if (event.getCellsCount() == 0) {
            return NO_POSITION;
        }
        if (positions == null) {
            throw new IllegalStateException("Metric '" + metricId + "': the world shape is "
                + "unavailable because the plugin was initialized without an analytics context.");
        }
        positions.offsetsOf(event.getCells(0), org, smallest);
        for (int i = 1; i < event.getCellsCount(); i++) {
            positions.offsetsOf(event.getCells(i), org, candidate);
            if (isBefore(candidate, smallest)) {
                System.arraycopy(candidate, 0, smallest, 0, smallest.length);
            }
        }
        return RelativeCellPositions.asJsonList(smallest);
    }

    /**
     * Compares two offsets component by component, in the order the components have.
     *
     * @param left the offset to place
     * @param right the offset to place it against
     * @return whether {@code left} comes before {@code right}
     */
    private static boolean isBefore(int[] left, int[] right) {
        for (int d = 0; d < left.length; d++) {
            if (left[d] != right[d]) {
                return left[d] < right[d];
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The table has no chart of its own. Counting its rows per recording would show how many
     * mutations were applied, which the variation the population metrics carry already implies.
     * What the table is for is being read next to another one: the clade view names a band's
     * founding mutation from it.
     */
    @Override
    public ManifestEntry getManifestEntry() {
        return null;
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // The rows of one recording, held until the indexer has written them. Two events stands for
        // every organism the recording could report as newborn carrying a mutation and the label
        // mask beside it, and 200 bytes for one row's object array with its boxed numbers, its two
        // shared strings and the three short list texts it renders.
        long rowBytes = params.maxOrganisms() * 2L * 200L;
        return Collections.singletonList(new MemoryEstimate(
            "Plugin: " + metricId,
            rowBytes,
            String.format("%d max organisms × ~2 events × ~200 bytes/row", params.maxOrganisms()),
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}
