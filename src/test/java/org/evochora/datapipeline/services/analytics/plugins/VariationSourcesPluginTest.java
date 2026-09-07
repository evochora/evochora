package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.TestMetadataHelper;
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

    private VariationSourcesPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new VariationSourcesPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "variation_sources")));
        plugin.initialize(context());
    }

    @Test
    void schemaCarriesTheRecordingTheSourceAndTheCount() {
        ParquetSchema schema = plugin.getSchema();

        assertThat(schema.getColumnCount()).isEqualTo(3);
        List<ParquetSchema.Column> columns = schema.getColumns();
        assertThat(columns.get(0).name()).isEqualTo("tick");
        assertThat(columns.get(0).type()).isEqualTo(ColumnType.BIGINT);
        assertThat(columns.get(1).name()).isEqualTo("source");
        assertThat(columns.get(1).type()).isEqualTo(ColumnType.VARCHAR);
        assertThat(columns.get(2).name()).isEqualTo("births");
        assertThat(columns.get(2).type()).isEqualTo(ColumnType.INTEGER);
    }

    @Test
    void aGenomeEqualToTheParentsIsCopiedUnchanged() {
        assertThat(sourcesOf(newborn(7).setGenomeHash(PARENT_GENOME)))
            .containsExactly(Map.entry("unchanged", 1));
    }

    @Test
    void aNewbornWithoutAGenomeIsBodiless() {
        assertThat(sourcesOf(newborn(7).setGenomeHash(0L)))
            .containsExactly(Map.entry("bodiless", 1));
    }

    @Test
    void aNewbornWithoutAGenomeIsBodilessEvenWhenTheParentHadNoneEither() {
        // Both hashes are 0 and therefore equal, but there is no genome that could have been
        // copied unchanged - what the birth produced is a child without a body
        TickData tick = TickData.newBuilder()
            .setTickNumber(RECORDING)
            .addOrganisms(newborn(7).setGenomeHash(0L).setParentGenomeHash(0L))
            .build();

        assertThat(plugin.extractRows(tick)).singleElement()
            .satisfies(row -> assertThat(row[1]).isEqualTo("bodiless"));
    }

    @Test
    void aChangedGenomeThatNoPluginClaimsIsTheCopyChannel() {
        assertThat(sourcesOf(newborn(7).setGenomeHash(0x1234L)))
            .containsExactly(Map.entry("no-event", 1));
    }

    @Test
    void aBirthWithOneEventIsNamedByItsKind() {
        assertThat(sourcesOf(newborn(7).setGenomeHash(0x1234L)
                .addBirthMutations(event("substitution", 1))))
            .containsExactly(Map.entry("substitution", 1));
    }

    @Test
    void aBirthWithTwoEventsJoinsTheirKindsInPluginOrder() {
        assertThat(sourcesOf(newborn(7).setGenomeHash(0x1234L)
                .addBirthMutations(event("duplication", 17))
                .addBirthMutations(event("substitution", 1))))
            .containsExactly(Map.entry("duplication+substitution", 1));
    }

    @Test
    void anEventWithoutCellsIsNoSourceOfItsOwn() {
        // The label mask changes every label by the same amount and no molecule of its own, so a
        // birth carrying only it stands where its genome puts it - here beside the copy channel
        assertThat(sourcesOf(newborn(7).setGenomeHash(0x1234L)
                .addBirthMutations(event("label-rewrite", 0))))
            .containsExactly(Map.entry("no-event", 1));
    }

    @Test
    void anEventWithoutCellsLeavesAnUnchangedGenomeUnchanged() {
        assertThat(sourcesOf(newborn(7).setGenomeHash(PARENT_GENOME)
                .addBirthMutations(event("label-rewrite", 0))))
            .containsExactly(Map.entry("unchanged", 1));
    }

    @Test
    void anEventWithoutCellsIsSkippedInAJoinOfKinds() {
        assertThat(sourcesOf(newborn(7).setGenomeHash(0x1234L)
                .addBirthMutations(event("duplication", 17))
                .addBirthMutations(event("label-rewrite", 0))
                .addBirthMutations(event("substitution", 1))))
            .containsExactly(Map.entry("duplication+substitution", 1));
    }

    @Test
    void birthsOfOneSourceAreCounted() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(RECORDING)
            .addOrganisms(newborn(7).setGenomeHash(PARENT_GENOME))
            .addOrganisms(newborn(8).setGenomeHash(PARENT_GENOME))
            .addOrganisms(newborn(9).setGenomeHash(0x1234L)
                .addBirthMutations(event("substitution", 1)))
            .build();

        assertThat(plugin.extractRows(tick))
            .extracting(row -> row[0], row -> row[1], row -> row[2])
            .containsExactlyInAnyOrder(
                tuple(RECORDING, "unchanged", 2),
                tuple(RECORDING, "substitution", 1));
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
        TickData tick = TickData.newBuilder()
            .setTickNumber(RECORDING)
            .addOrganisms(newborn(7).setGenomeHash(0x1234L)
                .addBirthMutations(event("substitution", 1))
                .setIsDead(true)
                .setDeathTick(RECORDING - 2))
            .build();

        assertThat(plugin.extractRows(tick)).singleElement()
            .satisfies(row -> assertThat(row[1]).isEqualTo("substitution"));
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
    void theChartStacksTheSourcesOfARecordingToShares() {
        ManifestEntry entry = plugin.getManifestEntry();

        assertThat(entry.id).isEqualTo("variation_sources");
        assertThat(entry.name).isEqualTo("Variation Sources");
        assertThat(entry.description).isNotEmpty();
        assertThat(entry.dataSources).containsOnlyKeys("lod0");
        assertThat(entry.visualization.type).isEqualTo("stacked-area-chart");
        assertThat(entry.visualization.config)
            .containsEntry("x", "tick")
            .containsEntry("groupBy", "source")
            .containsEntry("y", "births")
            .containsEntry("yAxisMode", "percent");
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
    void lodLevelsCannotBeConfigured() {
        assertThatThrownBy(() -> new VariationSourcesPlugin().configure(ConfigFactory.parseMap(
                Map.of("metricId", "m", "lodLevels", 5))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lodLevels");
    }

    @Test
    void everyRecordedTickIsRead() {
        assertThat(plugin.getSamplingInterval()).isEqualTo(1);
        assertThat(plugin.getLodLevels()).isEqualTo(1);
    }

    /**
     * The rows of a recording holding exactly this one newborn, as source and count.
     */
    private List<Map.Entry<String, Integer>> sourcesOf(OrganismState.Builder newborn) {
        TickData tick = TickData.newBuilder()
            .setTickNumber(RECORDING)
            .addOrganisms(newborn)
            .build();
        return plugin.extractRows(tick).stream()
            .map(row -> Map.entry((String) row[1], (Integer) row[2]))
            .toList();
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
