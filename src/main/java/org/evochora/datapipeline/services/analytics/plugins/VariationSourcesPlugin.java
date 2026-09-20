package org.evochora.datapipeline.services.analytics.plugins;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.evochora.datapipeline.api.analytics.AbstractAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.Aggregation;
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
 * Counts a recording's births by what made each newborn's genome what it is, one row per recording.
 * <p>
 * <strong>Metrics:</strong>
 * <ul>
 *   <li>{@code tick} - the recording</li>
 *   <li>{@code unchanged} - genome equal to the parent's, copied without a difference</li>
 *   <li>{@code bodiless} - newborn without a genome at all</li>
 *   <li>{@code no_event} - genome differs and no plugin claims it: a defective copy, or cells
 *       another organism overwrote</li>
 *   <li>{@code duplication}, {@code deletion}, {@code instruction_insertion},
 *       {@code label_insertion}, {@code substitution} - the birth carries events of exactly this
 *       one kind</li>
 *   <li>{@code multiple} - the birth carries events of two or more kinds, so two or more plugins
 *       changed this genome</li>
 *   <li>{@code other} - the birth carries events of exactly one kind, and that kind is none of the
 *       five above: a mutation plugin from outside this project</li>
 * </ul>
 * <p>
 * <strong>How a birth is sorted.</strong> {@link BirthVariation} decides, and the columns follow
 * its classes in its order. Every birth counts in exactly one column, so the counts of a row add
 * up to the births of that recording.
 * <p>
 * Which kinds met at a birth counted under {@code multiple} is not in these rows;
 * {@code mutation_summary} holds every single event and answers that.
 * <p>
 * <strong>What it is for.</strong> The copy channel is invisible in every other curve: nothing
 * persisted says that a genome changed without a mutation plugin doing it, and it can only be
 * found as the remainder the plugins do not explain. Here it is a band of its own, with its
 * episodes, next to the bands of the mutation kinds.
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
 * <strong>What a coarser level holds.</strong> The counts are declared
 * {@link Aggregation#SUM}, so a coarser level adds the recordings of its window into one row
 * instead of keeping one of them: every level holds all the births of the run, and only the width
 * of a row's window grows. A window that reaches the end of a batch leaves its part as a row of
 * its own, so the rows of a range are added rather than one of them picked - which is what the
 * chart's query does anyway when it sums the loaded rows into time buckets, so that a bar carries
 * the births of a window and not of one recording.
 * <p>
 * A recording without births produces no row: a row of zeros would read as a recording whose
 * births came from nowhere rather than as one that had none.
 */
public class VariationSourcesPlugin extends AbstractAnalyticsPlugin {

    /**
     * The count columns, in the order they follow the tick in a row and stack in the chart: one per
     * class a birth is sorted into, in the order those classes are numbered in.
     */
    private static final List<String> COUNT_COLUMNS = BirthVariation.CLASSES;

    /** How many time buckets the chart's query cuts the loaded ticks into. */
    private static final int TARGET_BUCKETS = 50;

    private static final ParquetSchema SCHEMA = buildSchema();

    /**
     * How many simulation ticks lie between two recordings, which is the window a state's birth
     * tick has to fall into for the state to be a newborn of this recording. Zero until a context
     * carrying metadata has been supplied.
     */
    private int recordingInterval;

    private static ParquetSchema buildSchema() {
        ParquetSchema.Builder builder = ParquetSchema.builder().column("tick", ColumnType.BIGINT);
        for (String column : COUNT_COLUMNS) {
            // Births are events between two recordings, not a state at one of them: a coarser
            // level has to add the recordings of its window, or it would show a tenth of them
            builder.column(column, ColumnType.INTEGER, Aggregation.SUM);
        }
        return builder.build();
    }

    @Override
    protected Fixed fixedSamplingInterval() {
        return new Fixed(1, "a birth is reported as a newborn in exactly one recording, so a "
            + "skipped recording loses its births for good - births are events, not a state that "
            + "can be sampled");
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
     * Counts the births this recording reports into the column each belongs to. Returns no row
     * when nobody was born.
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

        int[] births = new int[COUNT_COLUMNS.size()];
        int total = 0;
        long previousRecording = tick.getTickNumber() - recordingInterval;
        for (OrganismState org : tick.getOrganismsList()) {
            if (!org.hasParentId() || org.getBirthTick() <= previousRecording) {
                continue;
            }
            births[BirthVariation.classify(org)]++;
            total++;
        }

        if (total == 0) {
            return Collections.emptyList();
        }

        Object[] row = new Object[births.length + 1];
        row[0] = tick.getTickNumber();
        for (int i = 0; i < births.length; i++) {
            row[i + 1] = births[i];
        }
        return Collections.singletonList(row);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The bars carry the births themselves, not their shares. Recording is sparse enough that a
     * bar stands on a handful of births, where a share turns single births into a jumping band of
     * halves and thirds; the height of the bar says how much the bar is worth, and an episode of
     * the copy channel is a block of its own whatever the population does around it.
     */
    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Variation Sources";
        entry.description = "Births per time window, by what changed the genome at birth.";

        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }

        entry.generatedQuery = bucketSumQuery();
        List<String> outputColumns = new java.util.ArrayList<>();
        outputColumns.add("tick");
        outputColumns.addAll(COUNT_COLUMNS);
        entry.outputColumns = outputColumns;

        entry.visualization = VisualizationHint.chart("stacked-bar-chart", "tick")
            .with("y", COUNT_COLUMNS)
            .with("yLabel", "Births")
            .with("yFormat", "integer");

        return entry;
    }

    /**
     * Builds the query the browser runs over the loaded rows: the ticks are cut into
     * {@link #TARGET_BUCKETS} buckets of equal width and the counts of every recording in a bucket
     * are added up, so a bar stands for the births of a window. Every bucket gets a row, a bucket
     * without a recording in it one of zeros, so that the bars stand at equal width across the
     * whole range.
     *
     * @return the SQL with {@code {table}} standing for the loaded rows
     */
    private static String bucketSumQuery() {
        String sums = COUNT_COLUMNS.stream()
            .map(name -> "COALESCE(SUM(" + name + "), 0)::BIGINT AS " + name)
            .collect(java.util.stream.Collectors.joining(",\n                "));
        return """
            WITH params AS (
                SELECT MIN(tick) AS first_tick,
                       GREATEST(1, (MAX(tick) - MIN(tick)) / %d)::BIGINT AS bucket_size
                FROM {table}
            ),
            buckets AS (
                SELECT (first_tick + n * bucket_size)::BIGINT AS bucket_tick
                FROM params, range(0, %d + 1) AS r(n)
                WHERE first_tick + n * bucket_size <= (SELECT MAX(tick) FROM {table})
            )
            SELECT
                b.bucket_tick AS tick,
                %s
            FROM buckets b
            LEFT JOIN {table} t
              ON t.tick >= b.bucket_tick AND t.tick < b.bucket_tick + (SELECT bucket_size FROM params)
            GROUP BY b.bucket_tick
            ORDER BY tick
            """.formatted(TARGET_BUCKETS, TARGET_BUCKETS, sums);
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // The births of a recording are counted into an array of ten numbers and leave as one row
        // of the same width, whatever the population does, and nothing is kept between recordings.
        return Collections.emptyList();
    }
}
