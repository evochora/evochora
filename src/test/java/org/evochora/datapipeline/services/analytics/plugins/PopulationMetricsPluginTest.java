package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.TestMetadataHelper;
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
        assertThat(schema.getColumnCount()).isEqualTo(4);

        List<ParquetSchema.Column> columns = schema.getColumns();
        assertThat(columns.get(0).name()).isEqualTo("tick");
        assertThat(columns.get(0).type()).isEqualTo(ColumnType.BIGINT);

        assertThat(columns.get(1).name()).isEqualTo("alive_count");
        assertThat(columns.get(1).type()).isEqualTo(ColumnType.INTEGER);

        assertThat(columns.get(2).name()).isEqualTo("avg_energy");
        assertThat(columns.get(2).type()).isEqualTo(ColumnType.DOUBLE);

        assertThat(columns.get(3).name()).isEqualTo("avg_entropy");
        assertThat(columns.get(3).type()).isEqualTo(ColumnType.DOUBLE);
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

        // avg_energy_pct: avg=500, 500/32767*100 ≈ 1.526%
        double expectedEnergyPct = 500.0 / MAX_ENERGY * 100.0;
        assertThat((double) row[2]).isCloseTo(expectedEnergyPct, within(0.001));

        // avg_entropy_pct: avg=100, 100/8191*100 ≈ 1.221%
        double expectedEntropyPct = 100.0 / MAX_ENTROPY * 100.0;
        assertThat((double) row[3]).isCloseTo(expectedEntropyPct, within(0.001));
    }

    @Test
    void testExtractRows_NoOrganisms_ReturnsZeroAverages() {
        // Setup: 0 organisms
        TickData tick = createTick(50, 5, 0, 0, 0);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);

        assertThat(row[0]).isEqualTo(50L);  // tick
        assertThat(row[1]).isEqualTo(0);    // alive_count
        assertThat(row[2]).isEqualTo(0.0);  // avg_energy_pct (no organisms)
        assertThat(row[3]).isEqualTo(0.0);  // avg_entropy_pct (no organisms)
    }

    @Test
    void testExtractRows_CalculatesPercentageAverages() {
        // Setup: 3 organisms with energies 100, 200, 300 -> avg = 200
        // and entropies 10, 20, 30 -> avg = 20
        TickData.Builder builder = TickData.newBuilder()
            .setTickNumber(1)
            .setTotalOrganismsCreated(3);

        builder.addOrganisms(OrganismState.newBuilder().setOrganismId(1).setEnergy(100).setEntropyRegister(10).build());
        builder.addOrganisms(OrganismState.newBuilder().setOrganismId(2).setEnergy(200).setEntropyRegister(20).build());
        builder.addOrganisms(OrganismState.newBuilder().setOrganismId(3).setEnergy(300).setEntropyRegister(30).build());

        List<Object[]> rows = plugin.extractRows(builder.build());

        assertThat(rows).hasSize(1);

        // avg_energy_pct: 200/32767*100
        double expectedEnergyPct = 200.0 / MAX_ENERGY * 100.0;
        assertThat((double) rows.get(0)[2]).isCloseTo(expectedEnergyPct, within(0.001));

        // avg_entropy_pct: 20/8191*100
        double expectedEntropyPct = 20.0 / MAX_ENTROPY * 100.0;
        assertThat((double) rows.get(0)[3]).isCloseTo(expectedEntropyPct, within(0.001));
    }

    @Test
    void testExtractRows_MaxValues_Returns100Percent() {
        // Organisms at maximum energy and entropy should yield 100%
        TickData tick = createTick(1, 1, 1, MAX_ENERGY, MAX_ENTROPY);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        assertThat((double) rows.get(0)[2]).isCloseTo(100.0, within(0.001));
        assertThat((double) rows.get(0)[3]).isCloseTo(100.0, within(0.001));
    }

    @Test
    void testManifestEntry_ContainsCorrectMetadata() {
        ManifestEntry entry = plugin.getManifestEntry();

        assertThat(entry.id).isEqualTo("population");
        assertThat(entry.name).isEqualTo("Population Overview");
        assertThat(entry.description).contains("alive organisms");

        // Data sources should reference lod0 with hierarchical glob pattern
        assertThat(entry.dataSources).containsKey("lod0");
        assertThat(entry.dataSources.get("lod0")).contains("population/lod0/**/*.parquet");

        // Visualization hints
        assertThat(entry.visualization.type).isEqualTo("line-chart");
        assertThat(entry.visualization.config.get("x")).isEqualTo("tick");

        // Suppress warning: a well-defined manifest will always have a List<String> here.
        @SuppressWarnings("unchecked")
        List<String> yAxis = (List<String>) entry.visualization.config.get("y");
        assertThat(yAxis).containsExactly("alive_count");

        @SuppressWarnings("unchecked")
        List<String> y2Axis = (List<String>) entry.visualization.config.get("y2");
        assertThat(y2Axis).containsExactly("avg_energy", "avg_entropy");
        assertThat(entry.visualization.config.get("y2Format")).isEqualTo("percent");
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
            public OutputStream openArtifactStream(String metricId, String lodLevel, String filename) throws IOException {
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
                .build());
        }
        return builder.build();
    }
}
