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
 * The beginning of every life of a run: who came into it when, from whom, with which genome and
 * with which variation, one row per organism. Together with the deaths table it is the life table
 * of the run.
 * <p>
 * <strong>What it is for.</strong> Every other table counts births; this one keeps them. A question
 * about a line rather than about a population - how long a generation takes, which births carried a
 * genome on and which ended with their carrier, what a founder's descendants did - is a question
 * about single births and their parents, and it can only be asked where the births still stand one
 * by one. The table stays small: a run writes one row per organism that ever lived, not one per
 * organism and recording.
 * <p>
 * <strong>Which states get a row.</strong> A state that was born after the previous recording.
 * Recordings lie on a fixed grid that a pause or a resume does not shift, so the rule needs no
 * memory of earlier ticks: the first recording of a run sees the founders, and the first recording
 * after a resume sees exactly the births since the checkpoint. Newborns that died before the
 * recording are in it as dead states and get their row like the others - a birth happened either
 * way.
 * <p>
 * <strong>Founders.</strong> The organisms a run starts with have no parent. Their row says when
 * and with which genome they entered the run and leaves everything about a parent empty, the
 * variation included: nothing was copied, so nothing could vary. Without these rows the organisms
 * every line begins with would be the only ones the life table does not know.
 * <p>
 * The parent of a newborn is in the same recording, alive or as a dead state: it was alive
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

    private static final ParquetSchema SCHEMA = ParquetSchema.builder(
            "Every organism of the run, at the beginning of its life: the newborn, its parent, their "
            + "genomes and how the newborn's genome came about. One row per organism. An ancestor, "
            + "one of the organisms a run starts with, has a row whose parent_id, "
            + "parent_birth_tick, parent_genome_hash and variation are empty. Joined with the "
            + "deaths table by organism_id it gives each organism's whole life. The table has one "
            + "level of detail.")
        .column("tick", ColumnType.BIGINT,
            "The recorded tick that reported the birth, the first one after it; for an ancestor "
            + "the first recorded tick of the run.")
        .column("birth_tick", ColumnType.BIGINT,
            "Tick at which the newborn was born; for an ancestor the tick the run started at.")
        .column("organism_id", ColumnType.INTEGER, "Id of the newborn.")
        .column("parent_id", ColumnType.INTEGER,
            "Id of the organism the newborn was replicated from. Empty for an ancestor.")
        .column("parent_birth_tick", ColumnType.BIGINT,
            "Tick at which that parent was born. birth_tick minus this value is the age of the "
            + "parent at this birth. Empty for an ancestor.")
        .column("generation", ColumnType.INTEGER,
            "Replications between an ancestor and the newborn: 0 for an ancestor, 1 for its child.")
        .column("genome_hash", ColumnType.BIGINT,
            "Hash of the newborn's genome at its birth, a signed 64-bit number, 0 for a newborn "
            + "without a genome. Equal hashes are equal genomes; the column joins to genome_hash in "
            + "the tables of the genomes.")
        .column("parent_genome_hash", ColumnType.BIGINT,
            "Hash of the parent's genome at this birth, 0 for a parent without a genome. Empty for "
            + "an ancestor.")
        .column("variation", ColumnType.INTEGER,
            "How the newborn's genome came about, as a number. " + BirthVariation.numberedClasses()
            + " Empty for an ancestor.")
        .build();

    /**
     * How many simulation ticks lie between two recordings, which is the window a state's birth
     * tick has to fall into for the state to get its row from this recording. Zero until a context
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
     * Writes one row per organism born since the previous recording, a founder included. Returns no
     * row when nobody was born, which for a run past its early growth is the common case.
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
            if (org.getBirthTick() <= previousRecording) {
                continue;
            }
            if (rows == null) {
                rows = new ArrayList<>();
            }
            boolean founder = !org.hasParentId();
            if (!founder && birthTicks == null) {
                birthTicks = birthTicksById(tick);
            }
            rows.add(new Object[] {
                tick.getTickNumber(),
                org.getBirthTick(),
                org.getOrganismId(),
                founder ? null : org.getParentId(),
                founder ? null : parentBirthTick(org, birthTicks),
                org.getGeneration(),
                org.getGenomeHash(),
                org.hasParentGenomeHash() ? org.getParentGenomeHash() : null,
                founder ? null : BirthVariation.classify(org)
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
     * column by column and over all its ticks. The entry therefore names neither a query nor a level of
     * detail of its own: there is nothing of this table for the card to read twice, and a level
     * would offer a choice that changes nothing it draws.
     * <p>
     * The companion carries the five columns the derivation reads and leaves the genome hashes
     * where they are: a hash uses all 64 bits, which a JavaScript number cannot hold, and a query
     * that sorts a result carrying one fails in the browser's DuckDB. A derivation that needs a
     * hash asks for it as text, the way the lineage table hands its hashes over. The rows arrive
     * in no particular order, since the derivation reads them as a set and not as a sequence.
     * The founders' rows are left out: the card is about replications, and a founder was not
     * replicated.
     * The columns named under {@code y} and {@code y2} are the ones the derivation produces, not
     * columns of this table; {@code variationClasses} lets the browser resolve a class name to the
     * index the {@code variation} column holds, so that the order of the classes is stated once.
     */
    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Generation Time";
        entry.description = "From an organism's birth to its first child that reproduces in turn. "
            + "Right axis: the share of newborns that found such a line.";

        entry.companions = List.of(new ManifestEntry.Companion(metricId,
            "SELECT birth_tick, parent_birth_tick, organism_id, parent_id, variation FROM {table} "
                + "WHERE parent_id IS NOT NULL",
            false, true));

        entry.visualization = VisualizationHint.chart("band-chart", "tick")
            .with("derived", "generation-time")
            .with("tooYoung", "tooYoung")
            .with("birthsMetric", metricId)
            .with("variationClasses", BirthVariation.CLASSES)
            .with("y", List.of("p10", "p25", "p50", "p75", "p90"))
            .with("yFormat", "integer")
            .with("yLabel", "Ticks")
            .with("y2", List.of("newborns_that_found_a_line"))
            .with("y2Format", "percent")
            .with("y2Label", "Share of newborns that found a line");

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
