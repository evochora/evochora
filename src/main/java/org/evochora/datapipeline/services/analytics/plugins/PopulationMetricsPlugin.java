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
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.utils.MetadataConfigHelper;

import com.typesafe.config.Config;

/**
 * Holds how many organisms are alive at a recording and how their energy and entropy are spread
 * among them.
 * <p>
 * <strong>Metrics:</strong>
 * <ul>
 *   <li>{@code tick} - Simulation tick number</li>
 *   <li>{@code alive_count} - Number of living organisms</li>
 *   <li>{@code bodied_count} - Number of living organisms carrying a genome (genome hash != 0)</li>
 *   <li>{@code energy_p10} … {@code energy_p90} - Energy of the living organisms at the 10th, 25th,
 *       50th, 75th and 90th percentile, as % of maxEnergy (0-100)</li>
 *   <li>{@code entropy_p10} … {@code entropy_p90} - Entropy of the living organisms at the same
 *       percentiles, as % of maxEntropy (0-100)</li>
 * </ul>
 * <p>
 * The spread is reported rather than an average because an average hides the shape of the
 * population: the same mean stands for a population where every organism sits at that value and
 * for one split between the starving and the full. A median with the bands around it shows both -
 * where the middle organism is, and how far the population reaches on either side of it.
 * <p>
 * The percentiles cover the living organisms only, the same set {@code alive_count} counts. When
 * nobody is alive they are {@code null} rather than 0, because a 0 would read as "energy 0" instead
 * of "nobody left to measure".
 * <p>
 * The difference {@code alive_count - bodied_count} is the number of living organisms whose body
 * contains no genome molecules. Such organisms are counted as alive but carry no heritable code,
 * so they contribute to population and birth counts without contributing to any genome-based
 * metric.
 * <p>
 * The card draws {@code alive_count} alone. A body without genome molecules holds no instruction
 * to execute, so such an organism does nothing from its birth on and leaves the population again
 * within a few ticks, while a recording lies thousands of ticks from the next: the two counts fall
 * on each other, and a second line on top of the first reads as a fault rather than as a
 * measurement. How many organisms are born that way is a question about births, which the
 * variation sources card answers. The column stays in the table, where a run whose organisms keep
 * such a body longer can be read.
 * <p>
 * Maximum values for normalization are read from simulation metadata
 * ({@code runtime.organism.max-energy} and {@code runtime.organism.max-entropy}).
 * Both are normalized to 0-100% so they are directly comparable on the same Y-axis.
 */
public class PopulationMetricsPlugin extends AbstractAnalyticsPlugin {

    /** The percentiles reported for energy and for entropy, in column order. */
    private static final int[] PERCENTILES = { 10, 25, 50, 75, 90 };

    private static final List<String> ENERGY_COLUMNS =
        List.of("energy_p10", "energy_p25", "energy_p50", "energy_p75", "energy_p90");

    private static final List<String> ENTROPY_COLUMNS =
        List.of("entropy_p10", "entropy_p25", "entropy_p50", "entropy_p75", "entropy_p90");

    private static final ParquetSchema SCHEMA = buildSchema();

    private static ParquetSchema buildSchema() {
        ParquetSchema.Builder builder = ParquetSchema.builder()
            .column("tick", ColumnType.BIGINT)
            .column("alive_count", ColumnType.INTEGER)
            .column("bodied_count", ColumnType.INTEGER);
        for (String column : ENERGY_COLUMNS) {
            builder.column(column, ColumnType.DOUBLE);
        }
        for (String column : ENTROPY_COLUMNS) {
            builder.column(column, ColumnType.DOUBLE);
        }
        return builder.build();
    }

    /** Maximum energy per organism, used for percentage normalization. */
    private int maxEnergy;

    /** Maximum entropy per organism, used for percentage normalization. */
    private int maxEntropy;

    @Override
    public void initialize(IAnalyticsContext context) {
        super.initialize(context);
        if (context != null && context.getMetadata() != null && !context.getMetadata().getResolvedConfigJson().isEmpty()) {
            Config resolvedConfig = MetadataConfigHelper.getResolvedConfig(context.getMetadata());
            this.maxEnergy = resolvedConfig.getInt("runtime.organism.max-energy");
            this.maxEntropy = resolvedConfig.getInt("runtime.organism.max-entropy");
        }
    }

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    @Override
    public List<Object[]> extractRows(TickData tick) {
        // Collect the values of the living organisms, the set alive_count counts
        int bodied = 0;
        List<Integer> energies = new ArrayList<>(tick.getOrganismsCount());
        List<Integer> entropies = new ArrayList<>(tick.getOrganismsCount());

        for (OrganismState org : tick.getOrganismsList()) {
            if (org.getIsDead()) continue;
            if (org.getGenomeHash() != 0L) bodied++;
            energies.add(org.getEnergy());
            entropies.add(org.getEntropyRegister());
        }

        int alive = energies.size();
        Collections.sort(energies);
        Collections.sort(entropies);

        // With nobody alive the percentile columns stay null: a 0 would read as a measured value
        Object[] row = new Object[3 + ENERGY_COLUMNS.size() + ENTROPY_COLUMNS.size()];
        row[0] = tick.getTickNumber();  // tick (BIGINT)
        row[1] = alive;                 // alive_count (INTEGER)
        row[2] = bodied;                // bodied_count (INTEGER)
        if (alive > 0) {
            for (int i = 0; i < PERCENTILES.length; i++) {
                row[3 + i] = percentageOfMaximum(energies, PERCENTILES[i], maxEnergy);
                row[3 + PERCENTILES.length + i] = percentageOfMaximum(entropies, PERCENTILES[i], maxEntropy);
            }
        }

        // Return single row for this tick
        return Collections.singletonList(row);
    }

    /**
     * Reads a percentile from sorted values and expresses it as a percentage of a maximum.
     *
     * @param sortedValues the values of the living organisms, in ascending order
     * @param percentile   the percentile to read
     * @param maximum      the value that stands for 100%
     * @return the percentile as % of the maximum (0-100)
     */
    private static double percentageOfMaximum(List<Integer> sortedValues, int percentile, int maximum) {
        return (double) Percentiles.of(sortedValues, percentile) / maximum * 100.0;
    }

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Population Overview";
        entry.description = "Living organisms, and how their energy and entropy are spread among them: "
            + "median and percentile bands, as a share of the maximum.";

        // Generate dataSources for all configured LOD levels
        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }

        entry.visualization = VisualizationHint.chart("band-chart", "tick")
            .with("groups", List.of(
                band("Energy", "#4a9eff", ENERGY_COLUMNS),
                band("Entropy", "#ffb366", ENTROPY_COLUMNS)))
            .with("yFormat", "percent")
            .with("yLabel", "% of maximum")
            .with("y2", List.of("alive_count"))
            .with("y2Format", "integer")
            .with("y2Label", "Organisms")
            .with("y2Solid", true)
            .with("y2Colors", List.of("#e0e0e0"));

        return entry;
    }

    /**
     * Describes one group of bands: a name for the legend, the colour the bands are drawn in, and
     * the percentile columns they are drawn between.
     *
     * @param name    the name the legend shows
     * @param color   the colour of the bands
     * @param columns the percentile columns in ascending order, the median among them
     * @return the group, its keys in the order the manifest JSON carries them
     */
    private static Map<String, Object> band(String name, String color, List<String> columns) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("name", name);
        group.put("color", color);
        group.put("y", columns);
        return group;
    }
}
