package org.evochora.datapipeline.services.analytics.plugins;

import java.util.ArrayList;
import java.util.Collections;
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

import com.typesafe.config.Config;

/**
 * The deaths of a run: who died when and of what, one row per death.
 * <p>
 * <strong>What it is for.</strong> Together with the births table it is the life table of the run.
 * A birth row says where an organism came from, a death row when and why it ended, and the two are
 * joined by the organism. An organism with a birth row and no death row was alive at the last
 * recording the tables hold. Everything an organism carries since its birth - its parent, its
 * genome, its generation - stands in its birth row and is not repeated here.
 * <p>
 * <strong>Which states are deaths.</strong> The simulation writes a dead organism into the first
 * recording after its death and removes it afterwards, so every death is reported exactly once and
 * the rule needs no memory of earlier ticks. The moment of death is the tick the simulation wrote
 * down when it happened, which lies before the recording that reports it.
 * <p>
 * <strong>Causes.</strong> {@link DeathCause} reads the cause from the dead organism's state.
 * <p>
 * <strong>Why it must see every recording.</strong> A plugin that skips the recording a death is
 * reported in does not see that death later - it never sees it.
 * <p>
 * <strong>Why there is only one level of detail.</strong> A coarser level selects rows, and a
 * selection from a table of individual deaths is not a coarser picture of the run's deaths but a
 * different set of them: the organisms it leaves out would read as still alive.
 */
public class DeathsPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder(
            "Every death of the run. One row per organism that died. Joined with the births table "
            + "by organism_id it gives each organism's whole life; an organism without a row here "
            + "was alive at the last recorded tick.")
        .column("tick", ColumnType.BIGINT, "The recorded tick that reported the death.")
        .column("organism_id", ColumnType.INTEGER,
            "The organism that died, the organism_id of its row in the births table.")
        .column("death_tick", ColumnType.BIGINT,
            "The tick the organism died at, which lies before the recorded tick that reported it.")
        .column("cause", ColumnType.INTEGER,
            "What the organism died of, as a number. " + DeathCause.numberedCauses())
        .build();

    /** Reads the cause of a death; knows no entropy limit until a run has been supplied. */
    private DeathCause deathCause = DeathCause.forRun(null);

    /** Metric holding the births the card reads every lifetime's beginning from. */
    private String birthsMetricId = "births";

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code birthsMetricId} is configured empty, which would
     *         leave the card looking for a table under no name and showing nothing
     */
    @Override
    public void configure(Config config) {
        super.configure(config);
        this.birthsMetricId = companionMetricId(config, "birthsMetricId",
            "the births the card reads every lifetime's beginning from", birthsMetricId);
    }

    @Override
    protected Fixed fixedSamplingInterval() {
        return new Fixed(1, "a dead organism is reported in exactly one recording and removed "
            + "afterwards, so a skipped recording loses its deaths for good - deaths are events, "
            + "not a state that can be sampled");
    }

    @Override
    protected Fixed fixedLodLevels() {
        return new Fixed(1, "a level of detail selects rows, and a selection from a table of "
            + "individual deaths is not a coarser picture of them but a different set of them");
    }

    /**
     * {@inheritDoc}
     * <p>
     * Reads the run's entropy limit, which a death by entropy is recognized by.
     */
    @Override
    public void initialize(IAnalyticsContext context) {
        super.initialize(context);
        this.deathCause = DeathCause.forRun(context);
    }

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Writes one row per organism this recording reports as dead. Returns no row when nobody died.
     *
     * @throws IllegalStateException if an organism is reported dead without a time of death, since
     *         leaving it out would make it read as alive, or if its state fits no cause of death
     */
    @Override
    public List<Object[]> extractRows(TickData tick) {
        List<Object[]> rows = null;
        for (OrganismState org : tick.getOrganismsList()) {
            if (!org.getIsDead()) {
                continue;
            }
            if (!org.hasDeathTick()) {
                throw new IllegalStateException("Metric '" + metricId + "': organism "
                    + org.getOrganismId() + " is reported dead at tick " + tick.getTickNumber()
                    + " without a time of death");
            }
            if (rows == null) {
                rows = new ArrayList<>();
            }
            rows.add(new Object[] {
                tick.getTickNumber(),
                org.getOrganismId(),
                org.getDeathTick(),
                deathCause.of(org)
            });
        }
        return rows == null ? Collections.emptyList() : rows;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The card draws no series this table holds. A lifetime is the distance from a birth to a
     * death, which stand in two tables, and its percentiles belong to all the deaths of a window
     * and not to those of one recording - percentiles cannot be added up into a coarser level the
     * way counts can. So the browser derives the bands from the two tables themselves, read column
     * by column and over all their ticks, and the entry names neither a query nor a level of
     * detail.
     * <p>
     * The rows arrive in no particular order, since the derivation reads them as a set. The columns
     * named under {@code y} and {@code y2} are the ones the derivation produces, not columns of
     * this table.
     */
    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Death Lifetimes";
        entry.description = "Lifetimes of the organisms that died, as percentiles, with the number "
            + "of deaths behind them. Percentiles collapsing onto one value mean many organisms "
            + "dying at exactly the same age.";

        entry.companions = List.of(
            new ManifestEntry.Companion(metricId,
                "SELECT organism_id, death_tick FROM {table}", false, true),
            new ManifestEntry.Companion(birthsMetricId,
                "SELECT organism_id, birth_tick FROM {table}", false, true));

        entry.visualization = VisualizationHint.chart("band-chart", "tick")
            .with("derived", "death-lifetimes")
            .with("deathsMetric", metricId)
            .with("birthsMetric", birthsMetricId)
            .with("y", List.of(
                "death_lifetime_p10", "death_lifetime_p25", "death_lifetime_p50",
                "death_lifetime_p75", "death_lifetime_p90"))
            .with("yLabel", "Lifetime at death")
            .with("yFormat", "integer")
            .with("y2", List.of("deaths"))
            .with("y2Label", "Deaths")
            .with("y2Format", "integer");

        return entry;
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // The rows of one recording, held until the indexer has written them. Every organism the
        // recording could report is counted as dead, 120 bytes for one row's object array with its
        // four boxed numbers.
        long rowBytes = params.maxOrganisms() * 120L;
        return Collections.singletonList(new MemoryEstimate(
            "Plugin: " + metricId,
            rowBytes,
            String.format("%d max organisms × ~120 bytes/row", params.maxOrganisms()),
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}
