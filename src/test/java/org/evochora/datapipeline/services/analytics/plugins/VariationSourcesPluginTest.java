package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.analytics.Aggregation;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.IAnalyticsContext;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.contracts.MutationEvent;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.storage.PublishedOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for {@link VariationSourcesPlugin}.
 * <p>
 * The run records every tenth tick, so a state of the recording at tick 1000 is a newborn of it if
 * it was born after tick 990.
 */
@Tag("unit")
class VariationSourcesPluginTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;
    private static final int RECORDING_INTERVAL = 10;
    private static final long RECORDING = 1000L;
    private static final long PARENT_GENOME = 0x5678L;

    /** The count columns a row carries after the tick, in the order they are expected in. */
    private static final List<String> COUNT_COLUMNS = List.of(
        "unchanged",
        "bodiless",
        "no_event",
        "duplication",
        "deletion",
        "instruction_insertion",
        "label_insertion",
        "substitution",
        "multiple",
        "other");

    /** The kinds a mutation plugin of this project reports, which the second card holds apart. */
    private static final List<String> MUTATION_KINDS = List.of(
        "duplication",
        "deletion",
        "instruction_insertion",
        "label_insertion",
        "substitution");

    private VariationSourcesPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new VariationSourcesPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "variation_sources")));
        plugin.initialize(context());
    }

    @Test
    void schemaCarriesTheRecordingAndOneCountPerSource() {
        ParquetSchema schema = plugin.getSchema();

        List<ParquetSchema.Column> columns = schema.getColumns();
        assertThat(schema.getColumnCount()).isEqualTo(COUNT_COLUMNS.size() + 1);
        assertThat(columns.get(0).name()).isEqualTo("tick");
        assertThat(columns.get(0).type()).isEqualTo(ColumnType.BIGINT);
        assertThat(columns.subList(1, columns.size()))
            .extracting(ParquetSchema.Column::name)
            .containsExactlyElementsOf(COUNT_COLUMNS);
        assertThat(columns.subList(1, columns.size()))
            .extracting(ParquetSchema.Column::type)
            .containsOnly(ColumnType.INTEGER);
    }

    @Test
    void theCountsAreSummedIntoACoarserLevelAndTheRecordingIsSampled() {
        // Births happen between two recordings, so a coarser level has to add the recordings of
        // its window; the tick names the row and is taken from the recording it stands on
        List<ParquetSchema.Column> columns = plugin.getSchema().getColumns();

        assertThat(columns.get(0).aggregation()).isEqualTo(Aggregation.SAMPLE);
        assertThat(columns.subList(1, columns.size()))
            .extracting(ParquetSchema.Column::aggregation)
            .containsOnly(Aggregation.SUM);
    }

    @Test
    void aRowCarriesTheRecordingItCounts() {
        List<Object[]> rows = plugin.extractRows(recordingOf(newborn(7).setGenomeHash(0x1234L)));

        assertThat(rows).singleElement()
            .satisfies(row -> assertThat(row[0]).isEqualTo(RECORDING));
    }

    @Test
    void aGenomeEqualToTheParentsIsCopiedUnchanged() {
        assertThat(birthsOf(newborn(7).setGenomeHash(PARENT_GENOME)))
            .containsExactly(Map.entry("unchanged", 1));
    }

    @Test
    void aNewbornWithoutAGenomeIsBodiless() {
        assertThat(birthsOf(newborn(7).setGenomeHash(0L)))
            .containsExactly(Map.entry("bodiless", 1));
    }

    @Test
    void aNewbornWithoutAGenomeIsBodilessEvenWhenTheParentHadNoneEither() {
        // Both hashes are 0 and therefore equal, but there is no genome that could have been
        // copied unchanged - what the birth produced is a child without a body
        assertThat(birthsOf(newborn(7).setGenomeHash(0L).setParentGenomeHash(0L)))
            .containsExactly(Map.entry("bodiless", 1));
    }

    @Test
    void aChangedGenomeThatNoPluginClaimsIsTheCopyChannel() {
        assertThat(birthsOf(newborn(7).setGenomeHash(0x1234L)))
            .containsExactly(Map.entry("no_event", 1));
    }

    @Test
    void aBirthWithOneEventCountsUnderThatKind() {
        assertThat(birthsOf(newborn(7).setGenomeHash(0x1234L)
                .addBirthMutations(event("substitution", 1))))
            .containsExactly(Map.entry("substitution", 1));
    }

    @Test
    void aKindSpelledWithADashHasItsColumnSpelledWithAnUnderscore() {
        assertThat(birthsOf(newborn(7).setGenomeHash(0x1234L)
                .addBirthMutations(event("label-insertion", 4))))
            .containsExactly(Map.entry("label_insertion", 1));
    }

    @Test
    void twoEventsOfOneKindAreStillThatKind() {
        assertThat(birthsOf(newborn(7).setGenomeHash(0x1234L)
                .addBirthMutations(event("duplication", 17))
                .addBirthMutations(event("duplication", 9))))
            .containsExactly(Map.entry("duplication", 1));
    }

    @Test
    void aBirthWithTwoKindsCountsUnderMultiple() {
        assertThat(birthsOf(newborn(7).setGenomeHash(0x1234L)
                .addBirthMutations(event("duplication", 17))
                .addBirthMutations(event("substitution", 1))))
            .containsExactly(Map.entry("multiple", 1));
    }

    @Test
    void aKindWithoutAColumnOfItsOwnCountsUnderOther() {
        // A mutation plugin from outside this project reports a kind these columns do not name,
        // and a band of its own is what keeps it from vanishing into one of them
        assertThat(birthsOf(newborn(7).setGenomeHash(0x1234L)
                .addBirthMutations(event("transposition", 3))))
            .containsExactly(Map.entry("other", 1));
    }

    @Test
    void anEventWithoutCellsIsNoSourceOfItsOwn() {
        // The label mask changes every label by the same amount and no molecule of its own, so a
        // birth carrying only it stands where its genome puts it - here beside the copy channel
        assertThat(birthsOf(newborn(7).setGenomeHash(0x1234L)
                .addBirthMutations(event("label-rewrite", 0))))
            .containsExactly(Map.entry("no_event", 1));
    }

    @Test
    void anEventWithoutCellsLeavesAnUnchangedGenomeUnchanged() {
        assertThat(birthsOf(newborn(7).setGenomeHash(PARENT_GENOME)
                .addBirthMutations(event("label-rewrite", 0))))
            .containsExactly(Map.entry("unchanged", 1));
    }

    @Test
    void anEventWithoutCellsIsNoSecondKind() {
        assertThat(birthsOf(newborn(7).setGenomeHash(0x1234L)
                .addBirthMutations(event("duplication", 17))
                .addBirthMutations(event("label-rewrite", 0))))
            .containsExactly(Map.entry("duplication", 1));
    }

    @Test
    void theBirthsOfARecordingAreSummedIntoOneRow() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(RECORDING)
            .addOrganisms(newborn(7).setGenomeHash(PARENT_GENOME))
            .addOrganisms(newborn(8).setGenomeHash(PARENT_GENOME))
            .addOrganisms(newborn(9).setGenomeHash(0x1234L)
                .addBirthMutations(event("substitution", 1)))
            .addOrganisms(newborn(10).setGenomeHash(0x9999L)
                .addBirthMutations(event("deletion", 2))
                .addBirthMutations(event("instruction-insertion", 5)))
            .build();

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        assertThat(countsOf(rows.get(0))).containsExactly(
            Map.entry("unchanged", 2),
            Map.entry("substitution", 1),
            Map.entry("multiple", 1));
    }

    @Test
    void aFounderIsNoBirth() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(RECORDING)
            .addOrganisms(OrganismState.newBuilder()
                .setOrganismId(1)
                .setBirthTick(RECORDING - 1)
                .setGenomeHash(0x1234L))
            .build();

        assertThat(plugin.extractRows(tick)).isEmpty();
    }

    @Test
    void aStateBornBeforeThePreviousRecordingIsNoNewbornOfThisOne() {
        // Born exactly at the previous recording, where it was already counted
        TickData tick = TickData.newBuilder()
            .setTickNumber(RECORDING)
            .addOrganisms(newborn(7).setBirthTick(RECORDING - RECORDING_INTERVAL)
                .setGenomeHash(0x1234L))
            .build();

        assertThat(plugin.extractRows(tick)).isEmpty();
    }

    @Test
    void aNewbornThatDiedBeforeTheRecordingStillCounts() {
        assertThat(birthsOf(newborn(7).setGenomeHash(0x1234L)
                .addBirthMutations(event("substitution", 1))
                .setIsDead(true)
                .setDeathTick(RECORDING - 2)))
            .containsExactly(Map.entry("substitution", 1));
    }

    @Test
    void aRecordingWithoutBirthsProducesNoRow() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(RECORDING)
            .addOrganisms(newborn(7).setBirthTick(500L).setGenomeHash(0x1234L))
            .build();

        assertThat(plugin.extractRows(tick)).isEmpty();
    }

    @Test
    void withoutAContextTheWindowOfARecordingIsUnknown() {
        VariationSourcesPlugin uninitialized = new VariationSourcesPlugin();
        uninitialized.configure(ConfigFactory.parseMap(Map.of("metricId", "variation_sources")));
        uninitialized.initialize(null);

        assertThatThrownBy(() -> uninitialized.extractRows(TickData.newBuilder().build()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("recording interval");
    }

    @Test
    void theChartStacksTheBirthsOfARecordingAsCounts() {
        ManifestEntry entry = plugin.getManifestEntry();

        assertThat(entry.id).isEqualTo("variation_sources");
        assertThat(entry.name).isEqualTo("Variation Sources");
        assertThat(entry.description)
            .isEqualTo("Births per time window, by what changed the genome at birth.");
        assertThat(entry.dataSources).containsOnlyKeys("lod0");
        assertThat(entry.visualization.type).isEqualTo("stacked-bar-chart");
        assertThat(entry.visualization.config)
            .containsEntry("x", "tick")
            .containsEntry("y", COUNT_COLUMNS)
            .containsEntry("yLabel", "Births")
            .containsEntry("yFormat", "integer")
            // Bars carrying births, not shares of them
            .doesNotContainKey("yAxisMode");
    }

    @Test
    void samplingIntervalCannotBeConfigured() {
        // The value follows from what the metric is, so a configuration file stating it would be
        // a second place to hold it - and the place that wins when the two disagree
        assertThatThrownBy(() -> new VariationSourcesPlugin().configure(ConfigFactory.parseMap(
                Map.of("metricId", "m", "samplingInterval", 10))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("samplingInterval")
            .hasMessageContaining("loses its births for good");
    }

    @Test
    void levelsOfDetailAreConfiguredLikeForTheOtherMetrics() {
        // The counts are summed over a level's window rather than sampled from it, so a coarser
        // level holds all the births of the run and the metric needs no level of its own
        VariationSourcesPlugin configured = new VariationSourcesPlugin();
        configured.configure(ConfigFactory.parseMap(
            Map.of("metricId", "variation_sources", "lodLevels", 3, "lodFactor", 10)));

        assertThat(configured.getLodLevels()).isEqualTo(3);
        assertThat(configured.getLodFactor()).isEqualTo(10);
        assertThat(configured.getManifestEntry().dataSources)
            .containsOnlyKeys("lod0", "lod1", "lod2");
    }

    @Test
    void theChartSumsTheRecordingsOverTimeBuckets() {
        ManifestEntry entry = plugin.getManifestEntry();

        assertThat(entry.outputColumns).startsWith("tick").containsAll(COUNT_COLUMNS);
        // A bar covers one window of the stretch the card draws, so that choosing a coarser
        // resolution widens the bars rather than shortening the stretch
        for (String column : COUNT_COLUMNS) {
            assertThat(entry.generatedQuery).contains("SUM(rows." + column + ")");
        }
        assertThat(entry.generatedQuery)
            .contains("{buckets}").contains("{from}").contains("{to}")
            .contains("GROUP BY windows.window_tick");
    }

    @Test
    void theQueryDrawsAsManyWindowsAsTheCardAsksAndKeepsEveryBirth() throws java.sql.SQLException {
        // The card says which stretch it draws, and the browser may not drop a row of counts to fit
        // it: the windows have to start where the stretch starts and end with it, however the width
        // of one divides the ticks between
        String query = plugin.getManifestEntry().generatedQuery.replace("{table}", "sources")
            .replace("{from}", "260000").replace("{to}", "27790000");
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:duckdb:");
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sources (tick BIGINT, " + COUNT_COLUMNS.stream()
                .map(column -> column + " BIGINT").collect(java.util.stream.Collectors.joining(", "))
                + ")");
            for (long tick = 260_000; tick <= 27_790_000; tick += 10_000) {
                statement.execute("INSERT INTO sources VALUES (" + tick + ", "
                    + "1, 0, 0, 0, 0, 0, 0, 0, 0, 0)");
            }
            for (int windows : new int[] {85, 64, 42, 21, 10, 2, 1}) {
                try (java.sql.ResultSet rows = statement.executeQuery(
                        "SELECT COUNT(*) AS windows, SUM(unchanged) AS births FROM ("
                        + query.replace("{buckets}", String.valueOf(windows)) + ")")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt("windows")).isEqualTo(windows);
                    assertThat(rows.getLong("births")).isEqualTo(2754);
                }
            }
        }
    }

    @Test
    void theWindowsSpanTheStretchTheCardDrawsAndCountNothingOutsideIt()
            throws java.sql.SQLException {
        // Every card of the analyzer shows the same stretch. A coarse level writes its newest row
        // further back, so a query that took the stretch from its own rows would end earlier than
        // the card beside it; and a file is fetched whole where it reaches into the stretch, so a
        // row outside it must not be counted into the window at the edge
        String query = plugin.getManifestEntry().generatedQuery.replace("{table}", "sources")
            .replace("{from}", "1000").replace("{to}", "11000").replace("{buckets}", "10");
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:duckdb:");
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sources (tick BIGINT, " + COUNT_COLUMNS.stream()
                .map(column -> column + " BIGINT").collect(java.util.stream.Collectors.joining(", "))
                + ")");
            // Rows of a level that ends halfway through the stretch, one before it and one past it
            for (long tick : new long[] {0, 1000, 3000, 5000, 20000}) {
                statement.execute("INSERT INTO sources VALUES (" + tick
                    + ", 1, 0, 0, 0, 0, 0, 0, 0, 0, 0)");
            }
            try (java.sql.ResultSet rows = statement.executeQuery(
                    "SELECT MIN(tick) AS first_window, MAX(tick) AS last_window, "
                    + "SUM(unchanged) AS births FROM (" + query + ")")) {
                assertThat(rows.next()).isTrue();
                // The windows are 1000 ticks wide and start where the stretch does, whatever the
                // rows hold; the last one is the tenth
                assertThat(rows.getLong("first_window")).isEqualTo(1000L);
                assertThat(rows.getLong("last_window")).isEqualTo(10000L);
                // The recordings at 0 and at 20000 lie outside the stretch and are not counted
                assertThat(rows.getLong("births")).isEqualTo(3L);
            }
        }
    }

    @Test
    void everyRecordedTickIsRead() {
        assertThat(plugin.getSamplingInterval()).isEqualTo(1);
    }

    @Test
    void theTableCarriesTwoCards() {
        assertThat(plugin.getManifestEntries())
            .extracting(entry -> entry.id)
            .containsExactly("variation_sources", "mutation_success");
    }

    @Test
    void theSecondCardReadsNothingOfThisMetricsOwnFiles() {
        ManifestEntry success = mutationSuccess(plugin);

        assertThat(success.storageMetricId).isEqualTo("variation_sources");
        assertThat(success.name).isEqualTo("Mutation Success");
        // The card draws only what it derives from the births table, so it reads nothing of this
        // plugin's own files - naming them would cost a pass over every one of them for nothing
        assertThat(success.dataSources).isNull();
        assertThat(success.generatedQuery).isNull();
    }

    @Test
    void theSecondCardDerivesItsSeriesFromTheBirthsReadColumnWise() {
        ManifestEntry success = mutationSuccess(plugin);

        assertThat(success.visualization.type).isEqualTo("band-chart");
        assertThat(success.visualization.config)
            .containsEntry("derived", "mutation-success")
            // The derivation reads the births under this name and resolves their classes through
            // the list given here
            .containsEntry("birthsMetric", "births")
            .containsEntry("variationClasses", COUNT_COLUMNS)
            // A ratio is read on a scale where a half and a double are the same step away from one
            .containsEntry("ratioScale", true)
            // The value is held against the births no plugin touched, which is one by definition
            .containsEntry("reference", 1);
        // One band per kind: the range the value could as well be, with the value in the middle
        assertThat(bandsOf(success).keySet()).containsExactlyElementsOf(MUTATION_KINDS);
        for (String kind : MUTATION_KINDS) {
            assertThat(bandsOf(success).get(kind))
                .isEqualTo(List.of(kind + "_low", kind, kind + "_high"));
        }
        assertThat(success.companions).singleElement().satisfies(companion -> {
            assertThat(companion.metricId()).isEqualTo("births");
            assertThat(companion.columnar()).isTrue();
            assertThat(companion.followsLevel()).isFalse();
            // Only what the derivation reads: a genome hash needs all 64 bits, and a query that
            // sorts a result carrying one fails in the browser's DuckDB
            assertThat(companion.query())
                .contains("parent_birth_tick")
                .contains("variation")
                .doesNotContain("genome_hash")
                .doesNotContain("ORDER BY");
        });
    }

    @Test
    void theBirthsTableCanBeConfiguredUnderAnotherName() {
        VariationSourcesPlugin renamed = new VariationSourcesPlugin();
        renamed.configure(ConfigFactory.parseMap(Map.of(
            "metricId", "variation_sources", "birthsMetricId", "life_table")));
        renamed.initialize(context());

        assertThat(mutationSuccess(renamed).companions).singleElement()
            .satisfies(companion -> assertThat(companion.metricId()).isEqualTo("life_table"));
    }

    @Test
    void aBirthsTableWithoutANameIsRefused() {
        // The card would look for a table under no name and show nothing
        assertThatThrownBy(() -> new VariationSourcesPlugin().configure(ConfigFactory.parseMap(
                Map.of("metricId", "variation_sources", "birthsMetricId", "  "))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("birthsMetricId");
    }

    @Test
    void theCountsSayThatTheyMayNotBeDropped() {
        // The common part of an entry, this among it, is filled in where the plugin hands its
        // entries over
        ManifestEntry entry = plugin.getManifestEntries().get(0);

        // The browser thins rows to fit a card; a count dropped with its row would make the card
        // say that fewer were born than were, so the entry names the columns that are counts
        assertThat(entry.summedColumns).containsExactlyElementsOf(COUNT_COLUMNS);
    }

    @Test
    void theStackedBarsNameTheirColoursAndTheBandsLeaveThemToTheChart() {
        List<ManifestEntry> entries = plugin.getManifestEntries();

        // The stacked bars colour every class, since a class has to keep its colour across the
        // levels whatever is in the window; the card of bands takes the chart's palette by
        // position, as every other card of the analyzer does
        assertThat(colorsOf(entries.get(0))).containsOnlyKeys(COUNT_COLUMNS);
        assertThat(groupsOf(entries.get(1))).allSatisfy(group ->
            assertThat(group).doesNotContainKey("color"));
    }

    @Test
    void theClassesKeepTheColoursOfTheChartsPalette() {
        // The palette the stacked bar chart hands out by series position, written down so that both
        // cards can name the same colour for the same kind
        Map<String, String> colors = colorsOf(plugin.getManifestEntry());

        assertThat(colors.values()).containsExactly(
            "#4a9eff", "#a0e0a0", "#ffb366", "#dda0dd", "#87ceeb",
            "#ffd700", "#ff6b6b", "#98d8c8", "#f08080", "#c79ecf");
    }

    /**
     * The colours one card gives its series, by series key.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> colorsOf(ManifestEntry entry) {
        return (Map<String, String>) entry.visualization.config.get("colors");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> groupsOf(ManifestEntry entry) {
        List<Map<String, Object>> groups =
            (List<Map<String, Object>>) entry.visualization.config.get("groups");
        return groups == null ? List.of() : groups;
    }

    /**
     * The keys of every band group of a card, by the group's name.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> bandsOf(ManifestEntry entry) {
        Map<String, List<String>> bands = new java.util.LinkedHashMap<>();
        for (Map<String, Object> group : groupsOf(entry)) {
            bands.put((String) group.get("name"), (List<String>) group.get("y"));
        }
        return bands;
    }

    /**
     * The second of the plugin's cards, the one derived from the births table.
     */
    private static ManifestEntry mutationSuccess(VariationSourcesPlugin plugin) {
        List<ManifestEntry> entries = plugin.getManifestEntries();
        assertThat(entries).hasSize(2);
        return entries.get(1);
    }

    /**
     * The counts of a recording holding exactly these newborns, zeros left out.
     */
    private Map<String, Integer> birthsOf(OrganismState.Builder newborn) {
        List<Object[]> rows = plugin.extractRows(recordingOf(newborn));
        assertThat(rows).hasSize(1);
        return countsOf(rows.get(0));
    }

    /**
     * The count columns of one row that carry a birth, by column name and in column order.
     */
    private static Map<String, Integer> countsOf(Object[] row) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < COUNT_COLUMNS.size(); i++) {
            int births = (Integer) row[i + 1];
            if (births > 0) {
                counts.put(COUNT_COLUMNS.get(i), births);
            }
        }
        return counts;
    }

    /**
     * The recording at {@link #RECORDING} holding exactly this one state.
     */
    private static TickData recordingOf(OrganismState.Builder organism) {
        return TickData.newBuilder()
            .setTickNumber(RECORDING)
            .addOrganisms(organism)
            .build();
    }

    /**
     * A state born within the window of the recording at {@link #RECORDING}, with a parent whose
     * genome is {@link #PARENT_GENOME}.
     */
    private static OrganismState.Builder newborn(int id) {
        return OrganismState.newBuilder()
            .setOrganismId(id)
            .setBirthTick(RECORDING - 2)
            .setParentId(1)
            .setParentGenomeHash(PARENT_GENOME);
    }

    /**
     * An event of the given kind over the given number of cells, whose values are what the plugin
     * that reported it wrote; only the kind and whether there are cells matter here.
     */
    private static MutationEvent event(String kind, int cellCount) {
        MutationEvent.Builder builder = MutationEvent.newBuilder()
            .setPluginClass("org.example." + kind)
            .setKind(kind);
        for (int i = 0; i < cellCount; i++) {
            builder.addCells(i).addOldValues(0).addNewValues(i + 1);
        }
        return builder.build();
    }

    private IAnalyticsContext context() {
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setResolvedConfigJson(TestMetadataHelper.createResolvedConfigJson(
                WIDTH, HEIGHT, true, RECORDING_INTERVAL))
            .build();
        return new IAnalyticsContext() {
            @Override public SimulationMetadata getMetadata() { return metadata; }
            @Override public String getRunId() { return "test-run"; }
            @Override public PublishedOutputStream openArtifactStream(String m, String l, String f)
                    throws IOException {
                throw new UnsupportedOperationException();
            }
            @Override public Path getTempDirectory() { throw new UnsupportedOperationException(); }
        };
    }
}
