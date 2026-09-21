package org.evochora.datapipeline.services.analytics.plugins;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

import com.typesafe.config.Config;

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
 * <p>
 * <strong>The second card.</strong> "Mutation Success" asks what a mutation was worth: how often a
 * birth that received a given kind founds a line that goes on, measured against the births no
 * mutation plugin touched, which stand at 1. A kind that stays far below the others is a cliff -
 * the mutation is made and the lines it makes end. None of that is in these rows: it is derived in
 * the browser from the births table, one row per birth, which the card reads as a companion under
 * the metric named by {@code birthsMetricId}. Without a births plugin configured under that name
 * the card has nothing to read and cannot be drawn.
 */
public class VariationSourcesPlugin extends AbstractAnalyticsPlugin {

    /**
     * The count columns, in the order they follow the tick in a row and stack in the chart: one per
     * class a birth is sorted into, in the order those classes are numbered in.
     */
    private static final List<String> COUNT_COLUMNS = BirthVariation.CLASSES;

    /**
     * The series of the mutation success card, in the order it draws them: the births no mutation
     * plugin touched, which the other series are measured against, and then the five kinds a
     * mutation plugin of this project reports.
     */
    /** The series the kinds are held against, which rests on no kind's births. */
    private static final String CONTROL_SERIES = "no_plugin_mutation";

    private static final List<String> SUCCESS_SERIES = List.of(
        "no_plugin_mutation",
        "duplication",
        "deletion",
        "instruction_insertion",
        "label_insertion",
        "substitution");

    /**
     * The colour of every class, the one place both cards take it from, so that a kind reads the
     * same on either of them. These are the colours the stacked bars carry by their position in
     * the frontend's palette; the second card draws a different selection in a different order and
     * would otherwise give the same kind another colour.
     */
    private static final Map<String, String> CLASS_COLORS = classColors();

    private static final ParquetSchema SCHEMA = buildSchema();

    /** Metric holding the single births the success of a mutation kind is counted over. */
    private String birthsMetricId = "births";

    /**
     * How many simulation ticks lie between two recordings, which is the window a state's birth
     * tick has to fall into for the state to be a newborn of this recording. Zero until a context
     * carrying metadata has been supplied.
     */
    private int recordingInterval;

    /**
     * Builds the colour of every class, in the order the classes are numbered in.
     *
     * @return the hex colour of each class of {@link BirthVariation#CLASSES}, in that order
     */
    private static Map<String, String> classColors() {
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("unchanged", "#4a9eff");
        colors.put("bodiless", "#a0e0a0");
        colors.put("no_event", "#ffb366");
        colors.put("duplication", "#dda0dd");
        colors.put("deletion", "#87ceeb");
        colors.put("instruction_insertion", "#ffd700");
        colors.put("label_insertion", "#ff6b6b");
        colors.put("substitution", "#98d8c8");
        colors.put("multiple", "#f08080");
        colors.put("other", "#c79ecf");
        return Collections.unmodifiableMap(colors);
    }

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
     *
     * @throws IllegalArgumentException if {@code birthsMetricId} is configured empty, which would
     *         leave the mutation success card looking for a table under no name and showing nothing
     */
    @Override
    public void configure(Config config) {
        super.configure(config);
        this.birthsMetricId = companionMetricId(config, "birthsMetricId",
            "the single births the success of a mutation kind is counted over", birthsMetricId);
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

        entry.generatedQuery = windowSumQuery();
        List<String> outputColumns = new java.util.ArrayList<>();
        outputColumns.add("tick");
        outputColumns.addAll(COUNT_COLUMNS);
        entry.outputColumns = outputColumns;

        entry.visualization = VisualizationHint.chart("stacked-bar-chart", "tick")
            .with("y", COUNT_COLUMNS)
            .with("yLabel", "Births")
            .with("yFormat", "integer")
            .with("colors", CLASS_COLORS);

        return entry;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The counts of this table carry the first card; the second one is drawn from the births table
     * alone and stands here because it asks the same question of the same classes.
     */
    @Override
    public List<ManifestEntry> getManifestEntries() {
        ManifestEntry sources = getManifestEntry();
        ManifestEntry success = mutationSuccessEntry();
        applyCommonConfig(sources);
        applyCommonConfig(success);
        return List.of(sources, success);
    }

    /**
     * Describes the card that measures what a mutation kind was worth.
     * <p>
     * The card draws no series this table holds. Whether a birth founded a line that goes on is a
     * question about single births and their descendants, which exists only across the rows of the
     * births table and over the whole run, so the browser derives the series from that table, read
     * column by column and unfiltered. The entry therefore names neither a query nor a level of
     * detail of its own: reading this plugin's table for the card would cost a second pass over
     * every file of it and give the card nothing it draws.
     * <p>
     * {@code ratioScale} asks for the axis a ratio is read on: a half and a double are the same
     * step away from one, and a window whose few births leave a wide interval would otherwise
     * stretch the axis until every later difference is a flat line. The scale stays linear close
     * to zero, so a kind that founded no line at all keeps its place on it.
     * <p>
     * The companion carries the five columns the derivation reads and leaves the genome hashes
     * where they are: a hash uses all 64 bits, which a JavaScript number cannot hold, and a query
     * that sorts a result carrying one fails in the browser's DuckDB. A derivation that needs a
     * hash asks for it as text, the way the lineage table hands its hashes over. The rows arrive
     * in no particular order, since the derivation reads them as a set and not as a sequence.
     * {@code birthsMetric} names that table for the derivation, and {@code variationClasses} lets
     * the browser resolve a class name to the index the table's {@code variation} column holds, so
     * that the order of the classes is stated once.
     *
     * @return the manifest entry of the mutation success card
     */
    private ManifestEntry mutationSuccessEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = "mutation_success";
        entry.storageMetricId = metricId;
        entry.name = "Mutation Success";
        entry.description = "How often a birth founds a line that goes on, by the mutation it "
            + "received, against the births no mutation plugin touched (= 1).";

        entry.companions = List.of(new ManifestEntry.Companion(birthsMetricId,
            "SELECT birth_tick, parent_birth_tick, organism_id, parent_id, variation FROM {table}",
            false, true));

        entry.visualization = VisualizationHint.chart("band-chart", "tick")
            .with("derived", "mutation-success")
            .with("tooYoung", "tooYoung")
            .with("birthsMetric", birthsMetricId)
            .with("variationClasses", BirthVariation.CLASSES)
            .with("groups", successGroups())
            .with("yFormat", "decimal")
            .with("yLabel", "Success against no plugin mutation")
            .with("ratioScale", true)
            .with("bandLabel", "95% CI")
            .with("reference", 1)
            .with("referenceLabel", "No plugin mutation (= 1)");

        return entry;
    }

    /**
     * The colour of every kind the mutation success card draws. The series the others are measured
     * against gets none: the chart styles its reference line itself.
     *
     * @return the hex colour of each kind, in the order the card draws them
     */
    /**
     * Describes the band of every kind: the range its value could as well be at the births behind
     * it, with the value in the middle. The colours are the chart's own, as on every other card.
     *
     * @return one band group per kind, in the order the kinds are listed
     */
    private static List<Map<String, Object>> successGroups() {
        List<Map<String, Object>> groups = new java.util.ArrayList<>();
        for (String series : SUCCESS_SERIES) {
            if (CONTROL_SERIES.equals(series)) {
                continue;
            }
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("name", series);
            group.put("y", List.of(series + "_low", series, series + "_high"));
            groups.add(group);
        }
        return groups;
    }

    /**
     * Builds the query the browser runs over the loaded rows: the births of one window, added up,
     * one row per window.
     * <p>
     * The rows of a level do not already say this: a level writes what it holds when a batch ends
     * too, so its rows fall on ticks closer together than its window is wide. How many windows the
     * card draws it fills in for {@code {buckets}}; how wide one of them is follows from the ticks
     * the loaded rows actually cover, which is the only thing that cannot be out of date.
     *
     * @return the SQL with {@code {table}} standing for the loaded rows
     */
    private static String windowSumQuery() {
        String sums = COUNT_COLUMNS.stream()
            .map(name -> "COALESCE(SUM(" + name + "), 0)::BIGINT AS " + name)
            .collect(java.util.stream.Collectors.joining(",\n                "));
        return """
            WITH params AS (
                SELECT GREATEST(1, (MAX(tick) - MIN(tick)) / {buckets})::BIGINT AS bucket_size
                FROM {table}
            )
            SELECT
                (FLOOR(tick / (SELECT bucket_size FROM params))
                    * (SELECT bucket_size FROM params))::BIGINT AS tick,
                %s
            FROM {table}
            GROUP BY 1
            ORDER BY tick
            """.formatted(sums);
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // The births of a recording are counted into an array of ten numbers and leave as one row
        // of the same width, whatever the population does, and nothing is kept between recordings.
        return Collections.emptyList();
    }
}
