package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.analytics.IAnalyticsContext;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.storage.PublishedOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for VitalStatsPlugin.
 * <p>
 * The plugin is stateless - it only extracts raw facts from each tick. Births, deaths and the
 * split of the deaths by cause are computed at query time, which is run here in DuckDB.
 * <p>
 * Test metadata uses max-entropy=8191 from TestMetadataHelper.
 */
@Tag("unit")
class VitalStatsPluginTest {

    /** Max entropy from TestMetadataHelper defaults. */
    private static final int MAX_ENTROPY = 8191;

    private VitalStatsPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new VitalStatsPlugin();
        Config config = ConfigFactory.parseMap(Map.of("metricId", "vital_stats"));
        plugin.configure(config);
        plugin.initialize(createTestContext());
    }

    @Test
    void extractsRawFactsFromOneRecording() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(100)
            .setTotalOrganismsCreated(15)
            .addOrganisms(alive())
            .addOrganisms(alive())
            .addOrganisms(dead(0, 10))
            .build();

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsExactly(100L, 15L, 2, 1, 0, 0);
    }

    @Test
    void readsTheCauseOfDeathInTheOrderTheRuntimeChecksIt() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(1)
            .addOrganisms(dead(0, 10))                   // energy
            .addOrganisms(dead(-5, MAX_ENTROPY + 1))     // both limits broken: energy is checked first
            .addOrganisms(dead(50, MAX_ENTROPY + 1))     // entropy
            .addOrganisms(dead(50, MAX_ENTROPY))         // reaching the limit is not fatal
            .build();

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[3]).as("energy").isEqualTo(2);
        assertThat(row[4]).as("entropy").isEqualTo(1);
        assertThat(row[5]).as("other").isEqualTo(1);
    }

    @Test
    void anEntropyDeathCountsAsOtherWithoutRunConfiguration() {
        VitalStatsPlugin withoutRun = new VitalStatsPlugin();
        withoutRun.configure(ConfigFactory.parseMap(Map.of("metricId", "vital_stats")));
        withoutRun.initialize(null);

        Object[] row = withoutRun.extractRows(TickData.newBuilder()
            .setTickNumber(1)
            .addOrganisms(dead(50, MAX_ENTROPY + 1))
            .build()).get(0);

        assertThat(row[4]).isEqualTo(0);
        assertThat(row[5]).isEqualTo(1);
    }

    @Test
    void theSamplingIntervalCannotBeConfigured() {
        VitalStatsPlugin configured = new VitalStatsPlugin();

        assertThatThrownBy(() -> configured.configure(ConfigFactory.parseMap(
                Map.of("metricId", "vital_stats", "samplingInterval", 10))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("samplingInterval");
    }

    @Test
    void theManifestDescribesAStackedChartOfBirthsAndDeathsByCause() {
        ManifestEntry entry = plugin.getManifestEntry();

        assertThat(entry.id).isEqualTo("vital_stats");
        assertThat(entry.name).isEqualTo("Birth & Death Rates");
        assertThat(entry.outputColumns).containsExactly("tick", "births",
            "deaths_energy", "deaths_entropy", "deaths_other", "deaths_unclassified");
        assertThat(entry.visualization.type).isEqualTo("stacked-bar-chart");
        assertThat(entry.visualization.config)
            .containsEntry("y", List.of("births",
                "deaths_energy", "deaths_entropy", "deaths_other", "deaths_unclassified"))
            .containsEntry("hideEmpty", true)
            .containsKeys("labels", "colors");
    }

    @Test
    void onTheFinestLevelEveryDeathKeepsItsCause() throws SQLException {
        // Every recording is a row: two energy deaths and one entropy death in the second, one
        // other death in the third
        List<Map<String, Number>> result = runQuery(List.of(
            row(0, 10, 10, 0, 0, 0),
            row(1, 12, 9, 2, 1, 0),
            row(2, 12, 8, 0, 0, 1)));

        Map<String, Number> total = sum(result);
        assertThat(total.get("births").longValue()).isEqualTo(2);
        assertThat(total.get("deaths_energy").doubleValue()).isCloseTo(-2, within(1e-9));
        assertThat(total.get("deaths_entropy").doubleValue()).isCloseTo(-1, within(1e-9));
        assertThat(total.get("deaths_other").doubleValue()).isCloseTo(-1, within(1e-9));
        assertThat(total.get("deaths_unclassified").doubleValue()).isZero();
    }

    @Test
    void onACoarseLevelTheExactDeathsAreSplitByTheCountedCauses() throws SQLException {
        // Only every other recording was read: the running total says six died, the rows read
        // counted one energy and one entropy death
        List<Map<String, Number>> result = runQuery(List.of(
            row(0, 10, 10, 0, 0, 0),
            row(1000, 10, 4, 1, 1, 0)));

        Map<String, Number> total = sum(result);
        assertThat(total.get("deaths_energy").doubleValue()).isCloseTo(-3, within(1e-9));
        assertThat(total.get("deaths_entropy").doubleValue()).isCloseTo(-3, within(1e-9));
        assertThat(total.get("deaths_unclassified").doubleValue()).isZero();
    }

    @Test
    void deathsWithoutACountedCauseStayUnclassified() throws SQLException {
        List<Map<String, Number>> result = runQuery(List.of(
            row(0, 10, 10, 0, 0, 0),
            row(1000, 10, 7, 0, 0, 0)));

        Map<String, Number> total = sum(result);
        assertThat(total.get("deaths_unclassified").doubleValue()).isCloseTo(-3, within(1e-9));
        assertThat(total.get("deaths_energy").doubleValue()).isZero();
    }

    @Test
    void rowsWithoutCauseColumnsShowTheirDeathsAsUnclassified() throws SQLException {
        // Files written before the causes were recorded lack the columns; merged with newer ones
        // they arrive as NULL
        List<Map<String, Number>> result = runQuery(List.of(
            row(0, 10, 10, null, null, null),
            row(1000, 10, 7, null, null, null),
            row(2000, 12, 8, 1, 0, 0)));

        assertThat(result.get(1).get("deaths_unclassified").doubleValue()).isCloseTo(-3, within(1e-9));
        assertThat(result.get(2).get("deaths_energy").doubleValue()).isCloseTo(-1, within(1e-9));
        assertThat(result.get(2).get("births").longValue()).isEqualTo(2);
    }

    /**
     * Runs the manifest's query in DuckDB against the given rows, standing in for the Parquet
     * files the browser reads.
     */
    private List<Map<String, Number>> runQuery(List<Object[]> rows) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE facts (tick BIGINT, total_born BIGINT, alive_count INTEGER, "
                + "deaths_energy INTEGER, deaths_entropy INTEGER, deaths_other INTEGER)");
            for (Object[] r : rows) {
                statement.execute("INSERT INTO facts VALUES (%s, %s, %s, %s, %s, %s)"
                    .formatted(r[0], r[1], r[2], r[3], r[4], r[5]));
            }
            // The card fills in how wide one window is; here every recording is its own window,
            // so that the rows the query returns are the rows the test wrote
            String query = plugin.getManifestEntry().generatedQuery
                .replace("{table}", "facts")
                .replace("{tickInterval}", "1");
            List<Map<String, Number>> result = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery(query)) {
                while (rs.next()) {
                    result.add(Map.of(
                        "births", rs.getLong("births"),
                        "deaths_energy", rs.getDouble("deaths_energy"),
                        "deaths_entropy", rs.getDouble("deaths_entropy"),
                        "deaths_other", rs.getDouble("deaths_other"),
                        "deaths_unclassified", rs.getDouble("deaths_unclassified")));
                }
            }
            return result;
        }
    }

    private static Map<String, Number> sum(List<Map<String, Number>> rows) {
        long births = 0;
        double energy = 0;
        double entropy = 0;
        double other = 0;
        double unclassified = 0;
        for (Map<String, Number> r : rows) {
            births += r.get("births").longValue();
            energy += r.get("deaths_energy").doubleValue();
            entropy += r.get("deaths_entropy").doubleValue();
            other += r.get("deaths_other").doubleValue();
            unclassified += r.get("deaths_unclassified").doubleValue();
        }
        return Map.of("births", births, "deaths_energy", energy, "deaths_entropy", entropy,
            "deaths_other", other, "deaths_unclassified", unclassified);
    }

    private static Object[] row(long tick, long totalBorn, int alive, Integer energy, Integer entropy, Integer other) {
        return new Object[] {tick, totalBorn, alive, energy, entropy, other};
    }

    private static OrganismState alive() {
        return OrganismState.newBuilder().setEnergy(100).build();
    }

    private static OrganismState dead(int energy, int entropy) {
        return OrganismState.newBuilder()
            .setIsDead(true)
            .setEnergy(energy)
            .setEntropyRegister(entropy)
            .build();
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
            public PublishedOutputStream openArtifactStream(String metricId, String lodLevel, String filename)
                    throws IOException {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.nio.file.Path getTempDirectory() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
