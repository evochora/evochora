package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Unit tests for {@link BirthsPlugin}.
 * <p>
 * The run records every tenth tick, so a state of the recording at tick 1000 is a newborn of it if
 * it was born after tick 990. Every recording built here holds the parent of its newborns, which is
 * where their {@code parent_birth_tick} comes from.
 */
@Tag("unit")
class BirthsPluginTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;
    private static final int RECORDING_INTERVAL = 10;
    private static final long RECORDING = 1000L;
    private static final int PARENT_ID = 1;
    private static final long PARENT_BIRTH_TICK = 700L;
    private static final long PARENT_GENOME = 0x5678L;
    private static final int GENERATION = 4;

    /** Where the variation stands in a row. */
    private static final int VARIATION = 8;

    /** The classes a birth is sorted into, in the order the variation column numbers them. */
    private static final List<String> VARIATION_CLASSES = List.of(
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

    private BirthsPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new BirthsPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "births")));
        plugin.initialize(context());
    }

    @Test
    void schemaNamesTheNineValuesOfABirthInTheOrderARowCarriesThem() {
        ParquetSchema schema = plugin.getSchema();

        assertThat(schema.getColumns())
            .extracting(ParquetSchema.Column::name)
            .containsExactly("tick", "birth_tick", "organism_id", "parent_id", "parent_birth_tick",
                "generation", "genome_hash", "parent_genome_hash", "variation");
        assertThat(schema.getColumns())
            .extracting(ParquetSchema.Column::type)
            .containsExactly(ColumnType.BIGINT, ColumnType.BIGINT, ColumnType.INTEGER,
                ColumnType.INTEGER, ColumnType.BIGINT, ColumnType.INTEGER, ColumnType.BIGINT,
                ColumnType.BIGINT, ColumnType.INTEGER);
    }

    @Test
    void aRowCarriesEveryValueOfOneBirth() {
        List<Object[]> rows = plugin.extractRows(recordingOf(newborn(7)
            .setGenomeHash(0x1234L)
            .addBirthMutations(event("substitution", 1))));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsExactly(
            RECORDING,
            RECORDING - 2,
            7,
            PARENT_ID,
            PARENT_BIRTH_TICK,
            GENERATION,
            0x1234L,
            PARENT_GENOME,
            VARIATION_CLASSES.indexOf("substitution"));
    }

    @Test
    void everyNewbornOfTheRecordingGetsItsOwnRow() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(RECORDING)
            .addOrganisms(parent())
            .addOrganisms(newborn(7).setGenomeHash(PARENT_GENOME))
            .addOrganisms(newborn(8).setGenomeHash(0x1234L))
            .build();

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)[2]).isEqualTo(7);
        assertThat(rows.get(1)[2]).isEqualTo(8);
    }

    @Test
    void theVariationNamesWhatMadeTheGenomeWhatItIs() {
        assertThat(variationOf(newborn(7).setGenomeHash(PARENT_GENOME)))
            .isEqualTo("unchanged");
        assertThat(variationOf(newborn(7).setGenomeHash(0L)))
            .isEqualTo("bodiless");
        assertThat(variationOf(newborn(7).setGenomeHash(0x1234L)))
            .isEqualTo("no_event");
        assertThat(variationOf(newborn(7).setGenomeHash(0x1234L)
                .addBirthMutations(event("label-insertion", 4))))
            .isEqualTo("label_insertion");
        assertThat(variationOf(newborn(7).setGenomeHash(0x1234L)
                .addBirthMutations(event("duplication", 17))
                .addBirthMutations(event("substitution", 1))))
            .isEqualTo("multiple");
    }

    @Test
    void aFounderIsNoBirth() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(RECORDING)
            .addOrganisms(parent())
            .build();

        assertThat(plugin.extractRows(tick)).isEmpty();
    }

    @Test
    void aStateBornBeforeThePreviousRecordingIsNoNewbornOfThisOne() {
        // Born exactly at the previous recording, where it already got its row
        TickData tick = recordingOf(newborn(7)
            .setBirthTick(RECORDING - RECORDING_INTERVAL)
            .setGenomeHash(0x1234L));

        assertThat(plugin.extractRows(tick)).isEmpty();
    }

    @Test
    void aNewbornThatDiedBeforeTheRecordingStillGetsItsRow() {
        // A birth happened either way, and the row is the only place it is ever written
        TickData tick = recordingOf(newborn(7)
            .setGenomeHash(0x1234L)
            .setIsDead(true)
            .setDeathTick(RECORDING - 2));

        assertThat(plugin.extractRows(tick))
            .singleElement()
            .satisfies(row -> assertThat(row[1]).isEqualTo(RECORDING - 2));
    }

    @Test
    void withoutAContextTheWindowOfARecordingIsUnknown() {
        BirthsPlugin uninitialized = new BirthsPlugin();
        uninitialized.configure(ConfigFactory.parseMap(Map.of("metricId", "births")));
        uninitialized.initialize(null);

        assertThatThrownBy(() -> uninitialized.extractRows(TickData.newBuilder().build()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("recording interval");
    }

    @Test
    void theCardDerivesItsSeriesFromTheWholeTableReadColumnWise() {
        ManifestEntry entry = plugin.getManifestEntry();

        assertThat(entry.id).isEqualTo("births");
        assertThat(entry.name).isEqualTo("Generation Time");
        assertThat(entry.visualization.type).isEqualTo("band-chart");
        // Everything drawn is derived, so the card reads none of its own files and offers no level
        assertThat(entry.dataSources).isNull();
        assertThat(entry.generatedQuery).isNull();
        // The columns the card draws are the ones the derivation produces, over the births table it
        // names, whose classes it resolves through the list given here
        assertThat(entry.visualization.config)
            .containsEntry("derived", "generation-time")
            .containsEntry("birthsMetric", "births")
            .containsEntry("variationClasses", VARIATION_CLASSES)
            .containsEntry("y", List.of("p10", "p25", "p50", "p75", "p90"))
            .containsEntry("yFormat", "integer")
            .containsEntry("yLabel", "Ticks")
            .containsEntry("y2", List.of("newborns_that_found_a_line"))
            .containsEntry("y2Format", "percent")
            .containsEntry("y2Label", "Share of newborns that found a line");
        assertThat(entry.companions).singleElement().satisfies(companion -> {
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

    /**
     * The name of the class the one newborn of a recording is sorted into.
     */
    private String variationOf(OrganismState.Builder newborn) {
        List<Object[]> rows = plugin.extractRows(recordingOf(newborn));
        assertThat(rows).hasSize(1);
        return VARIATION_CLASSES.get((Integer) rows.get(0)[VARIATION]);
    }

    /**
     * The recording at {@link #RECORDING} holding the parent and this one newborn.
     */
    private static TickData recordingOf(OrganismState.Builder newborn) {
        return TickData.newBuilder()
            .setTickNumber(RECORDING)
            .addOrganisms(parent())
            .addOrganisms(newborn)
            .build();
    }

    /**
     * The parent every newborn here is replicated from, a founder itself and still alive.
     */
    private static OrganismState.Builder parent() {
        return OrganismState.newBuilder()
            .setOrganismId(PARENT_ID)
            .setBirthTick(PARENT_BIRTH_TICK)
            .setGenomeHash(PARENT_GENOME);
    }

    /**
     * A state born within the window of the recording at {@link #RECORDING}, with
     * {@link #parent()} as its parent.
     */
    private static OrganismState.Builder newborn(int id) {
        return OrganismState.newBuilder()
            .setOrganismId(id)
            .setBirthTick(RECORDING - 2)
            .setParentId(PARENT_ID)
            .setParentGenomeHash(PARENT_GENOME)
            .setGeneration(GENERATION);
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
