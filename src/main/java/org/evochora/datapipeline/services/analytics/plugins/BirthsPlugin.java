package org.evochora.datapipeline.services.analytics.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import org.evochora.datapipeline.utils.MetadataConfigHelper;

/**
 * The life table of a run: who was born when, to whom, with which genome and with which variation,
 * one row per birth.
 * <p>
 * <strong>Metrics:</strong>
 * <ul>
 *   <li>{@code tick} - the recording the birth was reported in</li>
 *   <li>{@code birth_tick} - the tick the newborn was born at</li>
 *   <li>{@code organism_id}, {@code parent_id} - the newborn and the organism it was replicated
 *       from</li>
 *   <li>{@code parent_birth_tick} - when that parent was itself born, so the distance between two
 *       generations is one subtraction and needs no second table</li>
 *   <li>{@code generation} - replications between the founders and this newborn, as the newborn
 *       carries it since its birth</li>
 *   <li>{@code genome_hash}, {@code parent_genome_hash} - its genome and the parent's at the birth,
 *       so a row joins against the lineage and population tables</li>
 *   <li>{@code variation} - the class {@link BirthVariation} sorts the birth into, as the index of
 *       that class</li>
 * </ul>
 * <p>
 * <strong>What it is for.</strong> Every other table counts births; this one keeps them. A question
 * about a line rather than about a population - how long a generation takes, which births carried a
 * genome on and which ended with their carrier, what a founder's descendants did - is a question
 * about single births and their parents, and it can only be asked where the births still stand one
 * by one. The table stays small: a run writes one row per organism that was ever born, not one per
 * organism and recording.
 * <p>
 * <strong>Which states are births.</strong> A newborn is a state that has a parent and was born
 * after the previous recording. Recordings lie on a fixed grid that a pause or a resume does not
 * shift, so the rule needs no memory of earlier ticks: the first recording of a run sees only
 * founders, which have no parent and are not births, and the first recording after a resume sees
 * exactly the births since the checkpoint. Newborns that died before the recording are in it as
 * dead states and get their row like the others - a birth happened either way.
 * <p>
 * The parent of such a newborn is in the same recording, alive or as a dead state: it was alive
 * when the newborn was born, which is after the previous recording, and a dead organism is written
 * once before it is dropped. That is what lets the parent's birth tick be read from the recording
 * itself rather than from a table of all the earlier ones.
 * <p>
 * <strong>Why it must see every recording.</strong> A birth appears as a newborn in exactly one
 * recording. A plugin that skips it does not see that birth later - it never sees it.
 * <p>
 * <strong>Why there is only one level of detail.</strong> A coarser level selects rows, and a
 * selection from a table of individual births is not a coarser picture of the run's births but a
 * different set of them: the lines it keeps would be the ones whose births happened to fall on the
 * kept recordings, and every question about a line would be answered from an arbitrary part of it.
 */
public class BirthsPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("birth_tick", ColumnType.BIGINT)
        .column("organism_id", ColumnType.INTEGER)
        .column("parent_id", ColumnType.INTEGER)
        .column("parent_birth_tick", ColumnType.BIGINT)
        .column("generation", ColumnType.INTEGER)
        .column("genome_hash", ColumnType.BIGINT)
        .column("parent_genome_hash", ColumnType.BIGINT)
        .column("variation", ColumnType.INTEGER)
        .build();

    /**
     * How many simulation ticks lie between two recordings, which is the window a state's birth
     * tick has to fall into for the state to be a newborn of this recording. Zero until a context
     * carrying metadata has been supplied.
     */
    private int recordingInterval;

    @Override
    protected Fixed fixedSamplingInterval() {
        return new Fixed(1, "a birth is reported as a newborn in exactly one recording, so a "
            + "skipped recording loses its births for good - births are events, not a state that "
            + "can be sampled");
    }

    @Override
    protected Fixed fixedLodLevels() {
        return new Fixed(1, "a level of detail selects rows, and a selection from a table of "
            + "individual births is not a coarser picture of them but a different set of them");
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
     * Writes one row per newborn this recording reports. Returns no row when nobody was born, which
     * for a run past its early growth is the common case.
     *
     * @throws IllegalStateException if the plugin was initialized without an analytics context,
     *         since the window a birth belongs to would then be unknown, or if a newborn's parent
     *         is not among the states of this recording, where the birth tick of that parent has to
     *         be read
     */
    @Override
    public List<Object[]> extractRows(TickData tick) {
        if (recordingInterval < 1) {
            throw new IllegalStateException("Metric '" + metricId + "': the recording interval is "
                + "unavailable because the plugin was initialized without an analytics context.");
        }

        List<Object[]> rows = null;
        Map<Integer, Long> birthTicks = null;
        long previousRecording = tick.getTickNumber() - recordingInterval;
        for (OrganismState org : tick.getOrganismsList()) {
            if (!org.hasParentId() || org.getBirthTick() <= previousRecording) {
                continue;
            }
            if (rows == null) {
                rows = new ArrayList<>();
                birthTicks = birthTicksById(tick);
            }
            rows.add(new Object[] {
                tick.getTickNumber(),
                org.getBirthTick(),
                org.getOrganismId(),
                org.getParentId(),
                parentBirthTick(org, birthTicks),
                org.getGeneration(),
                org.getGenomeHash(),
                org.hasParentGenomeHash() ? org.getParentGenomeHash() : null,
                BirthVariation.classify(org)
            });
        }
        return rows == null ? Collections.emptyList() : rows;
    }

    /**
     * Collects the birth tick of every state of one recording, by organism id.
     *
     * @param tick the recording to read
     * @return the birth tick of each organism the recording holds, alive or dead
     */
    private static Map<Integer, Long> birthTicksById(TickData tick) {
        Map<Integer, Long> birthTicks = new HashMap<>(tick.getOrganismsCount() * 2);
        for (OrganismState org : tick.getOrganismsList()) {
            birthTicks.put(org.getOrganismId(), org.getBirthTick());
        }
        return birthTicks;
    }

    /**
     * Reads when the parent of one newborn was born.
     *
     * @param org        the newborn, whose parent id is set
     * @param birthTicks the birth ticks of this recording's states, by organism id
     * @return the parent's birth tick
     * @throws IllegalStateException if the parent is not among this recording's states, which every
     *         parent of a newborn of this recording is
     */
    private Long parentBirthTick(OrganismState org, Map<Integer, Long> birthTicks) {
        Long parentBirthTick = birthTicks.get(org.getParentId());
        if (parentBirthTick == null) {
            throw new IllegalStateException("Metric '" + metricId + "': organism "
                + org.getOrganismId() + " was born at tick " + org.getBirthTick() + " to parent "
                + org.getParentId() + ", which this recording does not hold.");
        }
        return parentBirthTick;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The card draws no series this table holds. Generation time is the distance from a birth to
     * the birth of the first child that reproduces in turn, which exists only across rows and over
     * the whole run, so the browser derives the bands and the share from the table itself, read
     * column by column and unfiltered. What this entry's own query returns is the tick range the
     * table covers, so that the card knows where its data begins and ends.
     */
    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Generation Time";
        entry.description = "How long a line needs for one step: from an organism's birth to its "
            + "first child that reproduces in turn. The longer it takes, the slower the population "
            + "can change. Right axis: the share of newborns that found such a line at all.";

        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }

        entry.generatedQuery = "SELECT MIN(tick) AS first_tick, MAX(tick) AS last_tick FROM {table}";

        entry.companions = List.of(new ManifestEntry.Companion(metricId,
            "SELECT tick, birth_tick, organism_id, parent_id, parent_birth_tick, generation, "
                + "genome_hash, parent_genome_hash, variation FROM {table} ORDER BY birth_tick",
            false, true));

        entry.visualization = VisualizationHint.chart("band-chart", "tick")
            .with("derivedY2", "generation-time");

        return entry;
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // The rows of one recording, held until the indexer has written them, and the lookup from
        // organism id to birth tick they are built with. Every organism the recording could report
        // is counted as a newborn, 200 bytes for one row's object array with its nine boxed
        // numbers, and 64 bytes for one entry of the lookup.
        long rowBytes = params.maxOrganisms() * (200L + 64L);
        return Collections.singletonList(new MemoryEstimate(
            "Plugin: " + metricId,
            rowBytes,
            String.format("%d max organisms × ~264 bytes (row + lookup entry)", params.maxOrganisms()),
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}
