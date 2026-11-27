package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * The plugin is stateless - it only extracts raw facts from each tick. The births and the deaths
 * of a window are computed at query time, which is run here in DuckDB.
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
        assertThat(rows.get(0)).containsExactly(100L, 15L, 2, 1, 0);
    }

    @Test
    void countsTheDeathsOfARecordingByCause() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(1)
            .addOrganisms(dead(0, 10))
            .addOrganisms(dead(-5, MAX_ENTROPY + 1))
            .addOrganisms(dead(50, MAX_ENTROPY + 1))
            .build();

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[3]).as("energy").isEqualTo(2);
        assertThat(row[4]).as("entropy").isEqualTo(1);
    }

    @Test
    void aDeathThatFitsNoCauseIsRefused() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(1)
            .addOrganisms(dead(50, MAX_ENTROPY))
            .build();

        assertThatThrownBy(() -> plugin.extractRows(tick))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("neither");
    }

    @Test
    void theCauseColumnsAreSummedOverALevelsWindow() {
        assertThat(plugin.getSchema().summedColumnIndexes()).containsExactly(3, 4);
    }

    @Test
    void withoutAContextTheCauseOfADeathCannotBeRead() {
        VitalStatsPlugin uninitialized = new VitalStatsPlugin();
        uninitialized.configure(ConfigFactory.parseMap(Map.of("metricId", "vital_stats")));
        uninitialized.initialize(null);

        assertThatThrownBy(() -> uninitialized.extractRows(TickData.newBuilder().build()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("without an analytics context");
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
            "deaths_energy", "deaths_entropy");
        assertThat(entry.visualization.type).isEqualTo("stacked-bar-chart");
        assertThat(entry.visualization.config)
            .containsEntry("y", List.of("births", "deaths_energy", "deaths_entropy"))
            .containsEntry("hideEmpty", true)
            .containsKeys("labels", "colors");
    }

    @Test
    void everyDeathKeepsItsCause() throws SQLException {
        // Two energy deaths and one entropy death in the second row, one entropy death in the third
        List<Map<String, Number>> result = runQuery(List.of(
            row(0, 10, 10, 0, 0),
            row(1, 12, 9, 2, 1),
            row(2, 12, 8, 0, 1)));

        Map<String, Number> total = sum(result);
        assertThat(total.get("births").longValue()).isEqualTo(2);
        assertThat(total.get("deaths_energy").longValue()).isEqualTo(-2);
        assertThat(total.get("deaths_entropy").longValue()).isEqualTo(-2);
    }

    @Test
    void theRowsOfOneWindowAreAddedUp() throws SQLException {
        // A coarser level writes a row per window and another wherever a batch ended inside one,
        // each holding the deaths since the row before it: one window of the card over three rows
        List<Map<String, Number>> result = runQuery(List.of(
            row(0, 10, 10, 0, 0),
            row(400, 13, 9, 3, 1),
            row(1000, 15, 6, 4, 1)), 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("births").longValue()).isEqualTo(5);
        assertThat(result.get(0).get("deaths_energy").longValue()).isEqualTo(-7);
        assertThat(result.get(0).get("deaths_entropy").longValue()).isEqualTo(-2);
    }

    @Test
    void everyWholeWindowHoldsTheSameNumberOfRowsOfItsLevel() throws SQLException {
        // A level with a row every 10 ticks, one death in each, over a stretch of 1000 ticks that
        // the card cuts into 7 windows. A seventh of the stretch is 14.3 rows wide: windows of
        // that width would hold 14 and 15 rows in turn, and the card would draw bars rising and
        // falling. Rounded up to 15 rows, every whole window holds 15
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement()) {
            List<Object[]> rows = new ArrayList<>();
            for (int tick = 0; tick <= 1000; tick += 10) {
                rows.add(row(tick, tick, 0, 1, 0));
            }
            createFacts(statement, rows);
            List<Long> deaths = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery(queryOver(7, 10))) {
                while (rs.next()) {
                    deaths.add(-rs.getLong("deaths_energy"));
                }
            }
            // 101 rows: six whole windows of 15, and the 11 that are left in the last one
            assertThat(deaths).containsExactly(15L, 15L, 15L, 15L, 15L, 15L, 11L);
        }
    }

    @Test
    void aWindowWithoutARowCarriesNothing() throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement()) {
            createFacts(statement, List.of(row(0, 10, 10, 0, 0), row(3000, 12, 9, 2, 1)));
            try (ResultSet rs = statement.executeQuery(queryOver(3))) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.next()).isTrue();
                rs.getLong("births");
                assertThat(rs.wasNull()).as("births of the empty window").isTrue();
                rs.getLong("deaths_energy");
                assertThat(rs.wasNull()).as("deaths of the empty window").isTrue();
            }
        }
    }

    /**
     * Runs the manifest's query in DuckDB against the given rows, standing in for the Parquet
     * files the browser reads, with one window per row.
     */
    private List<Map<String, Number>> runQuery(List<Object[]> rows) throws SQLException {
        return runQuery(rows, rows.size());
    }

    /**
     * Runs the manifest's query in DuckDB against the given rows, cut into the given number of
     * windows.
     */
    private List<Map<String, Number>> runQuery(List<Object[]> rows, int windows) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement()) {
            createFacts(statement, rows);
            List<Map<String, Number>> result = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery(queryOver(windows))) {
                while (rs.next()) {
                    result.add(Map.of(
                        "births", rs.getLong("births"),
                        "deaths_energy", rs.getLong("deaths_energy"),
                        "deaths_entropy", rs.getLong("deaths_entropy")));
                }
            }
            return result;
        }
    }

    private static void createFacts(Statement statement, List<Object[]> rows) throws SQLException {
        statement.execute("CREATE TABLE facts (tick BIGINT, total_born BIGINT, alive_count INTEGER, "
            + "deaths_energy INTEGER, deaths_entropy INTEGER)");
        for (Object[] r : rows) {
            statement.execute("INSERT INTO facts VALUES (%s, %s, %s, %s, %s)"
                .formatted(r[0], r[1], r[2], r[3], r[4]));
        }
    }

    /**
     * The manifest's query as the card fills it in: the stretch it draws and its windows, over a
     * level whose ticks lie one apart, which leaves the width of a window as the stretch gives it.
     */
    private String queryOver(int windows) {
        return queryOver(windows, 1);
    }

    /**
     * The manifest's query as the card fills it in: the stretch it draws, its windows, and how
     * far apart the ticks of the loaded level lie.
     */
    private String queryOver(int windows, int interval) {
        return plugin.getManifestEntry().generatedQuery
            .replace("{table}", "facts")
            .replace("{buckets}", String.valueOf(windows))
            .replace("{interval}", String.valueOf(interval))
            .replace("{from}", "SELECT MIN(tick) FROM facts")
            .replace("{to}", "SELECT MAX(tick) FROM facts");
    }

    private static Map<String, Number> sum(List<Map<String, Number>> rows) {
        long births = 0;
        long energy = 0;
        long entropy = 0;
        for (Map<String, Number> r : rows) {
            births += r.get("births").longValue();
            energy += r.get("deaths_energy").longValue();
            entropy += r.get("deaths_entropy").longValue();
        }
        return Map.of("births", births, "deaths_energy", energy, "deaths_entropy", entropy);
    }

    private static Object[] row(long tick, long totalBorn, int alive, int energy, int entropy) {
        return new Object[] {tick, totalBorn, alive, energy, entropy};
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
