package org.evochora.datapipeline.services.analytics.plugins;

import java.util.Arrays;
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
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;

/**
 * Reports how long the organisms that died recently had lived.
 * <p>
 * <strong>Metrics:</strong>
 * <ul>
 *   <li>{@code tick} - Simulation tick number</li>
 *   <li>{@code death_count} - Organisms in this recording that had died</li>
 *   <li>{@code death_lifetime_p10}, {@code death_lifetime_p50}, {@code death_lifetime_p90} -
 *       percentiles of their lifetimes, in ticks</li>
 * </ul>
 * <p>
 * A lifetime is {@code death_tick - birth_tick}. Both are written by the simulation at the moment
 * they happen, so the lifetime is exact no matter how coarsely the run records - unlike the moment
 * of observation, which is a recording and therefore later than the death.
 * <p>
 * <strong>What it is for.</strong> An organism damaged in the right place can fork children that
 * are not viable and die after a fixed number of ticks. Such an episode dominates the birth and
 * death counts while the population barely moves, and it is invisible in every aggregate curve.
 * It shows here as the three percentiles collapsing onto one constant value: many deaths, all of
 * exactly the same age. A population dying of ordinary causes spreads them apart.
 * <p>
 * <strong>Why this plugin must see every recording.</strong> The simulation removes a dead
 * organism from its list right after the recording in which it first appears as dead, so each
 * death is reported exactly once and is then gone. A plugin that skips that recording does not see
 * the death later - it never sees it. Deaths are events, not a state that can be sampled, which is
 * why this metric is configured to read every recording while the other metrics follow the tuning
 * profile.
 * <p>
 * Ticks without deaths produce no row: the metric would have nothing to say about them, and a zero
 * would read as a lifetime rather than as an absence.
 */
public class DeathLifetimesPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("death_count", ColumnType.INTEGER)
        .column("death_lifetime_p10", ColumnType.BIGINT)
        .column("death_lifetime_p50", ColumnType.BIGINT)
        .column("death_lifetime_p90", ColumnType.BIGINT)
        .build();

    /** Reused across ticks to keep the sampling path free of per-tick allocation. */
    private long[] lifetimes = new long[64];

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Collects the lifetimes of the organisms this recording reports as dead and reduces them to
     * three percentiles. Returns no row when nobody died.
     *
     * @throws IllegalStateException if an organism is reported dead without a time of death, since
     *         a lifetime cannot be derived from it and leaving it out would understate the count
     */
    @Override
    public List<Object[]> extractRows(TickData tick) {
        int count = 0;
        for (OrganismState org : tick.getOrganismsList()) {
            if (!org.getIsDead()) {
                continue;
            }
            if (!org.hasDeathTick()) {
                throw new IllegalStateException("Metric '" + metricId + "': organism "
                    + org.getOrganismId() + " is reported dead at tick " + tick.getTickNumber()
                    + " without a time of death");
            }
            if (count == lifetimes.length) {
                lifetimes = Arrays.copyOf(lifetimes, lifetimes.length * 2);
            }
            lifetimes[count++] = org.getDeathTick() - org.getBirthTick();
        }

        if (count == 0) {
            return Collections.emptyList();
        }

        Arrays.sort(lifetimes, 0, count);
        return Collections.singletonList(new Object[] {
            tick.getTickNumber(),
            count,
            percentile(count, 10),
            percentile(count, 50),
            percentile(count, 90)
        });
    }

    /**
     * Nearest-rank percentile of the lifetimes collected for this tick.
     * <p>
     * No interpolation: a lifetime is a number of ticks that some organism actually lived, and a
     * value between two of them would not be.
     *
     * @param count      how many lifetimes were collected
     * @param percentage the percentile to take, 1 to 100
     * @return the lifetime at that rank
     */
    private long percentile(int count, int percentage) {
        int rank = (int) Math.ceil(count * percentage / 100.0);
        return lifetimes[Math.min(count, Math.max(1, rank)) - 1];
    }

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Death Lifetimes";
        entry.description = "Lifetimes of recently died organisms, as percentiles, with the number "
            + "of deaths behind them. Percentiles collapsing onto one value mean many organisms "
            + "dying at exactly the same age.";

        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }

        entry.visualization = new VisualizationHint();
        entry.visualization.type = "line-chart";
        entry.visualization.config = new HashMap<>();
        entry.visualization.config.put("x", "tick");
        entry.visualization.config.put("y",
            List.of("death_lifetime_p10", "death_lifetime_p50", "death_lifetime_p90"));
        entry.visualization.config.put("yFormat", "integer");
        entry.visualization.config.put("y2", List.of("death_count"));
        entry.visualization.config.put("y2Format", "integer");

        return entry;
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // One long per organism that could die within a single recording, worst case all of them
        long bufferBytes = params.maxOrganisms() * 8L;
        return Collections.singletonList(new MemoryEstimate(
            "Plugin: " + metricId,
            bufferBytes,
            String.format("%d max organisms × 8 bytes/lifetime", params.maxOrganisms()),
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}
