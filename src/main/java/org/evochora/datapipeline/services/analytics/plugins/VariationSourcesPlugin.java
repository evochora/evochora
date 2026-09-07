package org.evochora.datapipeline.services.analytics.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.analytics.AbstractAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.IAnalyticsContext;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.analytics.VisualizationHint;
import org.evochora.datapipeline.api.contracts.MutationEvent;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.utils.MetadataConfigHelper;

/**
 * Records where the variation of a recording's births came from, one row per source.
 * <p>
 * <strong>Metrics:</strong>
 * <ul>
 *   <li>{@code tick} - the recording</li>
 *   <li>{@code source} - what made this newborn's genome what it is</li>
 *   <li>{@code births} - how many of the recording's births that source accounts for</li>
 * </ul>
 * <p>
 * <strong>The sources.</strong> Every birth carries exactly one of them, so the rows of a recording
 * add up to its births and their shares add up to one:
 * <ul>
 *   <li>{@code bodiless} - the newborn has no genome at all</li>
 *   <li>{@code unchanged} - its genome is the parent's, copied without a difference</li>
 *   <li>the {@code kind}s of the events the mutation plugins reported for the birth, joined by
 *       {@code +} in the order the plugins ran, for a newborn whose genome differs and that carries
 *       events</li>
 *   <li>{@code no-event} - the genome differs and no plugin claims it: a defective copy, or cells
 *       another organism overwrote</li>
 * </ul>
 * An event that wrote no cell - the label mask, which changes every label by the same amount and no
 * molecule of its own - is not a source and is left out of the join, so a birth that carries only
 * such an event reads as whatever its genome says: unchanged, or the copy channel.
 * <p>
 * <strong>What it is for.</strong> The copy channel is invisible in every other curve: nothing
 * persisted says that a genome changed without a mutation plugin doing it, and it can only be found
 * as the remainder the plugins do not explain. Here it is a band of its own, with its episodes,
 * next to the bands of the mutation kinds - and whether mutator lineages gain ground over a run is
 * the shape those bands take.
 * <p>
 * <strong>Which states are births.</strong> A newborn is a state that has a parent and was born
 * after the previous recording. Recordings lie on a fixed grid that a pause or a resume does not
 * shift, so the rule needs no memory of earlier ticks: the first recording of a run sees only
 * founders, which have no parent and are not births, and the first recording after a resume sees
 * exactly the births since the checkpoint. Newborns that died before the recording are in it as
 * dead states and count like the others - a birth happened either way.
 * <p>
 * <strong>Why it must see every recording.</strong> A birth appears as a newborn in exactly one
 * recording, and an organism's mutation events are written with that recording and dropped
 * afterwards. A plugin that skips it does not see those births later - it never sees them.
 * <p>
 * A recording without births produces no row: a zero would read as a source that accounted for
 * nothing rather than as the absence of anything to account for.
 */
public class VariationSourcesPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("source", ColumnType.VARCHAR)
        .column("births", ColumnType.INTEGER)
        .build();

    /** The source of a newborn that carries no genome, and therefore no mutation either. */
    private static final String BODILESS = "bodiless";

    /** The source of a newborn whose genome is the parent's. */
    private static final String UNCHANGED = "unchanged";

    /** The source of a newborn whose genome differs and that carries no event with cells. */
    private static final String NO_EVENT = "no-event";

    /** Separates the kinds of a birth that several mutation plugins wrote to. */
    private static final char KIND_SEPARATOR = '+';

    /** Bytes per row of one recording: the object array, the boxed numbers and the source text. */
    private static final int BYTES_PER_ROW = 120;

    /**
     * How many simulation ticks lie between two recordings, which is the window a state's birth
     * tick has to fall into for the state to be a newborn of this recording. Zero until a context
     * carrying metadata has been supplied.
     */
    private int recordingInterval;

    /** Reused across ticks; holds the births per source of the current recording. */
    private final Map<String, Integer> birthsPerSource = new LinkedHashMap<>();

    /** Reused across births; joins the kinds of the events that wrote cells. */
    private final StringBuilder kinds = new StringBuilder();

    @Override
    protected Fixed fixedSamplingInterval() {
        return new Fixed(1, "a birth is reported as a newborn in exactly one recording, so a "
            + "skipped recording loses its births for good - births are events, not a state that "
            + "can be sampled");
    }

    @Override
    protected Fixed fixedLodLevels() {
        return new Fixed(1, "a level of detail selects rows, and a selection from the sources of a "
            + "recording is not a coarser picture of them but a share of the births of another "
            + "recording");
    }

    /**
     * {@inheritDoc}
     * <p>
     * Reads the run's recording interval, which is the window a newborn's birth tick has to fall
     * into.
     */
    @Override
    public void initialize(IAnalyticsContext context) {
        super.initialize(context);
        if (context != null) {
            this.recordingInterval = MetadataConfigHelper.getSamplingInterval(context.getMetadata());
        }
    }

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Counts the births this recording reports and groups them by what varied at each. Returns no
     * row when nobody was born.
     *
     * @throws IllegalStateException if the plugin was initialized without an analytics context,
     *         since the window a birth belongs to would then be unknown
     */
    @Override
    public List<Object[]> extractRows(TickData tick) {
        if (recordingInterval < 1) {
            throw new IllegalStateException("Metric '" + metricId + "': the recording interval is "
                + "unavailable because the plugin was initialized without an analytics context.");
        }

        birthsPerSource.clear();
        long previousRecording = tick.getTickNumber() - recordingInterval;
        for (OrganismState org : tick.getOrganismsList()) {
            if (!org.hasParentId() || org.getBirthTick() <= previousRecording) {
                continue;
            }
            birthsPerSource.merge(sourceOf(org), 1, Integer::sum);
        }

        if (birthsPerSource.isEmpty()) {
            return Collections.emptyList();
        }

        List<Object[]> rows = new ArrayList<>(birthsPerSource.size());
        for (Map.Entry<String, Integer> entry : birthsPerSource.entrySet()) {
            rows.add(new Object[] {
                tick.getTickNumber(),
                entry.getKey(),
                entry.getValue()
            });
        }
        return rows;
    }

    /**
     * Names what varied at one birth.
     * <p>
     * The genome decides before the events do. A newborn without a genome has nothing that could
     * have varied, whatever its parent carried, and one whose genome is the parent's received no
     * variation even where a plugin wrote outside the body.
     *
     * @param org the newborn, with its genome, its parent's and the events of its birth
     * @return the one source this birth counts towards
     */
    private String sourceOf(OrganismState org) {
        if (org.getGenomeHash() == 0L) {
            return BODILESS;
        }
        if (org.hasParentGenomeHash() && org.getGenomeHash() == org.getParentGenomeHash()) {
            return UNCHANGED;
        }
        kinds.setLength(0);
        for (MutationEvent event : org.getBirthMutationsList()) {
            if (event.getCellsCount() == 0) {
                continue;
            }
            if (kinds.length() > 0) {
                kinds.append(KIND_SEPARATOR);
            }
            kinds.append(event.getKind());
        }
        return kinds.isEmpty() ? NO_EVENT : kinds.toString();
    }

    /**
     * {@inheritDoc}
     * <p>
     * The chart stacks the sources of every recording to the full height, so a band is the share of
     * the recording's births that source accounts for, not their number. Shares are what the
     * question is about: births per recording swing with the population, and a channel that grows
     * while the population shrinks looks like a decline in every count.
     */
    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Variation Sources";
        entry.description = "What varied at each birth, as shares of the births of a recording: "
            + "one band per mutation kind, one for genomes copied unchanged, one for newborns "
            + "without a genome, and one for the genomes that changed without any mutation plugin "
            + "doing it.";

        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }

        // The sources of a run are not known while writing - a plugin names its own kinds, and two
        // of them at one birth make a third source - so the rows carry the source as a value and
        // the chart makes a band of each.
        entry.visualization = VisualizationHint.chart("stacked-area-chart", "tick")
            .with("groupBy", "source")
            .with("y", "births")
            .with("yAxisMode", "percent");

        return entry;
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // The counted sources of one recording and the rows they become. Every organism the
        // recording could report as newborn stands for a source of its own, which is the bound and
        // not a case: sources are mutation kinds and their combinations, of which a run has a
        // handful.
        long rowBytes = params.maxOrganisms() * (long) BYTES_PER_ROW;
        return Collections.singletonList(new MemoryEstimate(
            "Plugin: " + metricId,
            rowBytes,
            String.format("%d max organisms × ~%d bytes/source row",
                params.maxOrganisms(), BYTES_PER_ROW),
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}
