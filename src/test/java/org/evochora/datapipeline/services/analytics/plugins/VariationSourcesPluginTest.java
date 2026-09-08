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
        "insertion",
        "label_insertion",
        "substitution",
        "multiple",
        "other");

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
                .addBirthMutations(event("insertion", 5)))
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
        for (String column : COUNT_COLUMNS) {
            assertThat(entry.generatedQuery).contains("COALESCE(SUM(" + column + "), 0)::BIGINT AS " + column);
        }
        assertThat(entry.generatedQuery).contains("bucket_size").contains("LEFT JOIN").contains("GROUP BY b.bucket_tick");
    }

    @Test
    void everyRecordedTickIsRead() {
        assertThat(plugin.getSamplingInterval()).isEqualTo(1);
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
