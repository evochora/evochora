package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.resources.storage.PublishedOutputStream;
import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.analytics.Aggregation;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.IAnalyticsContext;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for PopulationMetricsPlugin.
 * <p>
 * Tests the simplified plugin API: schema definition and row extraction.
 * DuckDB/Parquet generation is tested at the indexer level.
 * <p>
 * Test metadata uses max-energy=32767 and max-entropy=8191 from TestMetadataHelper.
 */
@Tag("unit")
class PopulationMetricsPluginTest {

    /** Max energy from TestMetadataHelper defaults. */
    private static final int MAX_ENERGY = 32767;

    /** Max entropy from TestMetadataHelper defaults. */
    private static final int MAX_ENTROPY = 8191;

    /** Arbitrary non-zero genome hash marking an organism as carrying a genome. */
    private static final long GENOME_HASH = 0x1234ABCDL;

    /** Row positions of the percentile columns, which follow tick, alive_count and bodied_count. */
    private static final int ENERGY_P10 = 3;
    private static final int ENERGY_P50 = 5;
    private static final int ENERGY_P90 = 7;
    private static final int ENTROPY_P10 = 8;
    private static final int ENTROPY_P50 = 10;
    private static final int ENTROPY_P90 = 12;

    private PopulationMetricsPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new PopulationMetricsPlugin();
        Config config = ConfigFactory.parseMap(Map.of("metricId", "population"));
        plugin.configure(config);
        plugin.initialize(createTestContext());
    }

    @Test
    void testGetSchema_ReturnsCorrectColumns() {
        ParquetSchema schema = plugin.getSchema();

        assertThat(schema).isNotNull();
        assertThat(schema.getColumnCount()).isEqualTo(13);

        List<ParquetSchema.Column> columns = schema.getColumns();
        assertThat(columns.get(0).name()).isEqualTo("tick");
        assertThat(columns.get(0).type()).isEqualTo(ColumnType.BIGINT);

        assertThat(columns.get(1).name()).isEqualTo("alive_count");
        assertThat(columns.get(1).type()).isEqualTo(ColumnType.INTEGER);

        assertThat(columns.get(2).name()).isEqualTo("bodied_count");
        assertThat(columns.get(2).type()).isEqualTo(ColumnType.INTEGER);

        assertThat(columns.subList(3, 13))
            .extracting(ParquetSchema.Column::name)
            .containsExactly(
                "energy_p10", "energy_p25", "energy_p50", "energy_p75", "energy_p90",
                "entropy_p10", "entropy_p25", "entropy_p50", "entropy_p75", "entropy_p90");

        // A percentile is a state at a recording, not a count of events, so it is sampled
        assertThat(columns.subList(3, 13))
            .allMatch(column -> column.type() == ColumnType.DOUBLE)
            .allMatch(column -> column.aggregation() == Aggregation.SAMPLE);
    }

    @Test
    void testExtractRows_SingleTick_ReturnsCorrectPercentages() {
        // Setup: 2 organisms, each with 500 energy and 100 entropy
        TickData tick = createTick(100, 10, 2, 500, 100);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);

        assertThat(row[0]).isEqualTo(100L);  // tick
        assertThat(row[1]).isEqualTo(2);     // alive_count
        assertThat(row[2]).isEqualTo(2);     // bodied_count (all test organisms carry a genome)

        // A uniform population: every percentile sits on the one value, 500/32767*100 ≈ 1.526%
        double expectedEnergyPct = 500.0 / MAX_ENERGY * 100.0;
        for (int i = ENERGY_P10; i <= ENERGY_P90; i++) {
            assertThat((double) row[i]).isCloseTo(expectedEnergyPct, within(0.001));
        }

        // and 100/8191*100 ≈ 1.221% for entropy
        double expectedEntropyPct = 100.0 / MAX_ENTROPY * 100.0;
        for (int i = ENTROPY_P10; i <= ENTROPY_P90; i++) {
            assertThat((double) row[i]).isCloseTo(expectedEntropyPct, within(0.001));
        }
    }

    @Test
    void testExtractRows_NoOrganisms_LeavesPercentilesNull() {
        // Setup: 0 organisms
        TickData tick = createTick(50, 5, 0, 0, 0);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);

        assertThat(row[0]).isEqualTo(50L);  // tick
        assertThat(row[1]).isEqualTo(0);    // alive_count
        assertThat(row[2]).isEqualTo(0);    // bodied_count

        // Nobody alive: no value to report, and a 0 would read as "energy 0"
        for (int i = ENERGY_P10; i <= ENTROPY_P90; i++) {
            assertThat(row[i]).isNull();
        }
    }

    @Test
    void testExtractRows_SpreadPopulation_ReportsPercentiles() {
        // Eleven organisms with energies 0, 100, ..., 1000 and entropies 0, 10, ..., 100.
        // With 11 values the percentile index is round(10 * P / 100), so p10 is the 2nd value,
        // p25 the 4th (index 3, rounded up from 2.5), p50 the 6th, p75 the 9th, p90 the 10th.
        TickData.Builder builder = TickData.newBuilder()
            .setTickNumber(1)
            .setTotalOrganismsCreated(11);
        for (int i = 0; i <= 10; i++) {
            builder.addOrganisms(OrganismState.newBuilder()
                .setOrganismId(i).setEnergy(i * 100).setEntropyRegister(i * 10).build());
        }

        Object[] row = plugin.extractRows(builder.build()).get(0);

        assertEnergyPercentiles(row, 100, 300, 500, 800, 900);
        assertEntropyPercentiles(row, 10, 30, 50, 80, 90);
    }

    @Test
    void testExtractRows_DeadOrganisms_DoNotEnterThePercentiles() {
        // Three living organisms at 100, 200, 300 energy and one dead one far above them
        TickData tick = TickData.newBuilder()
            .setTickNumber(2)
            .setTotalOrganismsCreated(4)
            .addOrganisms(OrganismState.newBuilder().setOrganismId(1).setEnergy(100).setEntropyRegister(10).build())
            .addOrganisms(OrganismState.newBuilder().setOrganismId(2).setEnergy(200).setEntropyRegister(20).build())
            .addOrganisms(OrganismState.newBuilder().setOrganismId(3).setEnergy(300).setEntropyRegister(30).build())
            .addOrganisms(OrganismState.newBuilder().setOrganismId(4).setEnergy(MAX_ENERGY)
                .setEntropyRegister(MAX_ENTROPY).setIsDead(true).build())
            .build();

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[1]).isEqualTo(3);  // alive_count
        // With three values the percentile index is round(2 * P / 100): p10 the 1st value,
        // p25 and p50 the 2nd, p75 and p90 the 3rd - the dead organism reaches none of them
        assertEnergyPercentiles(row, 100, 200, 200, 300, 300);
        assertEntropyPercentiles(row, 10, 20, 20, 30, 30);
    }

    @Test
    void testExtractRows_MaxValues_Returns100Percent() {
        // Organisms at maximum energy and entropy should yield 100%
        TickData tick = createTick(1, 1, 1, MAX_ENERGY, MAX_ENTROPY);

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat((double) row[ENERGY_P50]).isCloseTo(100.0, within(0.001));
        assertThat((double) row[ENTROPY_P50]).isCloseTo(100.0, within(0.001));
    }

    @Test
    void testExtractRows_OrganismsWithoutGenome_CountAsAliveButNotAsBodied() {
        // Three living organisms, none of them carrying genome molecules
        TickData.Builder builder = TickData.newBuilder()
            .setTickNumber(7)
            .setTotalOrganismsCreated(3);
        for (int i = 0; i < 3; i++) {
            builder.addOrganisms(OrganismState.newBuilder().setOrganismId(i).setGenomeHash(0L).build());
        }

        Object[] row = plugin.extractRows(builder.build()).get(0);

        assertThat(row[1]).isEqualTo(3);  // alive_count
        assertThat(row[2]).isEqualTo(0);  // bodied_count
    }

    @Test
    void testExtractRows_DeadOrganismWithGenome_CountsNeitherAliveNorBodied() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(8)
            .setTotalOrganismsCreated(2)
            .addOrganisms(OrganismState.newBuilder().setOrganismId(1).setGenomeHash(GENOME_HASH).build())
            .addOrganisms(OrganismState.newBuilder().setOrganismId(2).setGenomeHash(GENOME_HASH).setIsDead(true).build())
            .build();

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[1]).isEqualTo(1);  // alive_count
        assertThat(row[2]).isEqualTo(1);  // bodied_count
    }

    @Test
    void testExtractRows_MixedPopulation_GapIsOrganismsWithoutGenome() {
        // Five living organisms, two of them without genome molecules
        TickData.Builder builder = TickData.newBuilder()
            .setTickNumber(9)
            .setTotalOrganismsCreated(5);
        for (int i = 0; i < 3; i++) {
            builder.addOrganisms(OrganismState.newBuilder().setOrganismId(i).setGenomeHash(GENOME_HASH + i).build());
        }
        for (int i = 3; i < 5; i++) {
            builder.addOrganisms(OrganismState.newBuilder().setOrganismId(i).setGenomeHash(0L).build());
        }

        Object[] row = plugin.extractRows(builder.build()).get(0);

        assertThat(row[1]).isEqualTo(5);  // alive_count
        assertThat(row[2]).isEqualTo(3);  // bodied_count
        assertThat((int) row[1] - (int) row[2]).isEqualTo(2);
    }

    @Test
    void testManifestEntry_ContainsCorrectMetadata() {
        ManifestEntry entry = plugin.getManifestEntry();

        assertThat(entry.id).isEqualTo("population");
        assertThat(entry.name).isEqualTo("Population Overview");
        assertThat(entry.description).contains("Living organisms");

        // Data sources should reference lod0 with hierarchical glob pattern
        assertThat(entry.dataSources).containsKey("lod0");
        assertThat(entry.dataSources.get("lod0")).contains("population/lod0/**/*.parquet");

        // Visualization hints
        assertThat(entry.visualization.type).isEqualTo("band-chart");
        assertThat(entry.visualization.config.get("x")).isEqualTo("tick");

        // Suppress warning: a well-defined manifest will always have the band groups here.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups =
            (List<Map<String, Object>>) entry.visualization.config.get("groups");
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0)).containsEntry("name", "Energy").containsEntry("color", "#4a9eff");
        assertThat(groups.get(0).get("y")).isEqualTo(
            List.of("energy_p10", "energy_p25", "energy_p50", "energy_p75", "energy_p90"));
        assertThat(groups.get(1)).containsEntry("name", "Entropy").containsEntry("color", "#ffb366");
        assertThat(groups.get(1).get("y")).isEqualTo(
            List.of("entropy_p10", "entropy_p25", "entropy_p50", "entropy_p75", "entropy_p90"));

        assertThat(entry.visualization.config.get("yFormat")).isEqualTo("percent");
        assertThat(entry.visualization.config.get("yLabel")).isEqualTo("% of maximum");

        // The count moves to the right axis, drawn solid. The organisms whose body holds no genome
        // leave the population within a few ticks, so their count lies on the count of the living
        // and is left to the card that counts births by what they carried
        @SuppressWarnings("unchecked")
        List<String> y2Axis = (List<String>) entry.visualization.config.get("y2");
        assertThat(y2Axis).containsExactly("alive_count");
        assertThat(entry.visualization.config.get("y2Format")).isEqualTo("integer");
        assertThat(entry.visualization.config.get("y2Label")).isEqualTo("Organisms");
        assertThat(entry.visualization.config.get("y2Solid")).isEqualTo(true);
        assertThat(entry.visualization.config.get("y2Colors")).isEqualTo(List.of("#e0e0e0"));
    }

    @Test
    void testConfigure_ReadsSamplingInterval() {
        PopulationMetricsPlugin pluginWithSampling = new PopulationMetricsPlugin();
        Config config = ConfigFactory.parseMap(Map.of(
            "metricId", "pop",
            "samplingInterval", 10
        ));
        pluginWithSampling.configure(config);

        assertThat(pluginWithSampling.getSamplingInterval()).isEqualTo(10);
    }

    @Test
    void testConfigure_DefaultSamplingInterval() {
        assertThat(plugin.getSamplingInterval()).isEqualTo(1);
    }

    /** Asserts the five energy percentile columns against the raw energies they are taken from. */
    private void assertEnergyPercentiles(Object[] row, int... expectedEnergies) {
        assertPercentiles(row, ENERGY_P10, MAX_ENERGY, expectedEnergies);
    }

    /** Asserts the five entropy percentile columns against the raw entropies they are taken from. */
    private void assertEntropyPercentiles(Object[] row, int... expectedEntropies) {
        assertPercentiles(row, ENTROPY_P10, MAX_ENTROPY, expectedEntropies);
    }

    private void assertPercentiles(Object[] row, int firstColumn, int maximum, int... expectedValues) {
        for (int i = 0; i < expectedValues.length; i++) {
            assertThat((double) row[firstColumn + i])
                .as("percentile at row position %d", firstColumn + i)
                .isCloseTo((double) expectedValues[i] / maximum * 100.0, within(0.001));
        }
    }

    private IAnalyticsContext createTestContext() {
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setResolvedConfigJson(TestMetadataHelper.builder().build())
            .build();

        return new IAnalyticsContext() {
            @Override
            public SimulationMetadata getMetadata() {
                return metadata;
            }

            @Override
            public String getRunId() {
                return "test-run";
            }

            @Override
            public PublishedOutputStream openArtifactStream(String metricId, String lodLevel, String filename) throws IOException {
                throw new UnsupportedOperationException();
            }

            @Override
            public Path getTempDirectory() {
                throw new UnsupportedOperationException();
            }
        };
    }

    private TickData createTick(long tickNum, long totalCreated, int aliveCount, int energyPerOrganism, int entropyPerOrganism) {
        TickData.Builder builder = TickData.newBuilder()
                .setTickNumber(tickNum)
                .setTotalOrganismsCreated(totalCreated);

        for (int i = 0; i < aliveCount; i++) {
            builder.addOrganisms(OrganismState.newBuilder()
                .setOrganismId(i)
                .setEnergy(energyPerOrganism)
                .setEntropyRegister(entropyPerOrganism)
                .setGenomeHash(GENOME_HASH)
                .build());
        }
        return builder.build();
    }
}
