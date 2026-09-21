package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

@Tag("unit")
class AgeDistributionPluginTest {

    private AgeDistributionPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new AgeDistributionPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "age")));
        plugin.initialize(null);
    }

    @Test
    void testExtractRows_EmptyPopulation_ReturnsZeros() {
        TickData tick = TickData.newBuilder().setTickNumber(100).build();
        List<Object[]> rows = plugin.extractRows(tick);
        
        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);
        
        assertThat(row[0]).isEqualTo(100L);
        // All percentiles should be 0
        for (int i = 1; i <= 7; i++) {
            assertThat(row[i]).isEqualTo(0);
        }
    }

    @Test
    void testExtractRows_SingleOrganism_AllPercentilesAreSame() {
        // One organism, age 50
        TickData tick = createTick(100, 50); 
        
        List<Object[]> rows = plugin.extractRows(tick);
        Object[] row = rows.get(0);
        
        // All percentiles (min to max) should be 50
        for (int i = 1; i <= 7; i++) {
            assertThat(row[i]).isEqualTo(50);
        }
    }

    @Test
    void theWindowKeepsOneRecordingRatherThanAveragingItsPercentiles() throws java.sql.SQLException {
        // Three recordings fall into the one window the card asks for, a young population and two
        // old ones: the window keeps the earliest recording rather than averaging them
        String query = plugin.getManifestEntry().generatedQuery
            .replace("{table}", "ages")
            .replace("{buckets}", "1");
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:duckdb:");
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ages (tick BIGINT, p0 INTEGER, p10 INTEGER, "
                + "p25 INTEGER, p50 INTEGER, p75 INTEGER, p90 INTEGER, p100 INTEGER)");
            statement.execute("INSERT INTO ages VALUES (0, 0, 1, 2, 10, 20, 30, 40)");
            statement.execute("INSERT INTO ages VALUES (100, 0, 5, 50, 500, 900, 950, 999)");
            statement.execute("INSERT INTO ages VALUES (900, 0, 5, 50, 500, 900, 950, 999)");
            try (java.sql.ResultSet rows = statement.executeQuery(query)) {
                assertThat(rows.next()).isTrue();
                // The window holds all three recordings; the medians are 10, 500 and 500, and
                // their mean, 337, is a number no moment of the run ever had
                assertThat(rows.getLong("tick")).isZero();
                assertThat(rows.getInt("p50")).isEqualTo(10);
                assertThat(rows.getInt("p100")).isEqualTo(40);
                // The card asked for one window and gets one
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void theBandHoldsTheSpreadAndTheOldestOrganismItsOwnScale() {
        ManifestEntry entry = plugin.getManifestEntry();

        // The youngest is always a newborn, and how far the oldest reaches grows with the size of
        // the population: neither belongs in the band the spread is read in
        assertThat(entry.visualization.config)
            .containsEntry("y", List.of("p10", "p25", "p50", "p75", "p90"))
            .containsEntry("y2", List.of("p100"));
    }

    @Test
    void testExtractRows_LinearDistribution() {
        // Ages: 0, 25, 50, 75, 100
        TickData tick = createTick(200, 0, 25, 50, 75, 100);
        
        List<Object[]> rows = plugin.extractRows(tick);
        Object[] row = rows.get(0);
        
        // Expected (approximate with nearest-rank):
        // p0   (index 0) -> 0
        // p10  (index 0) -> 0
        // p25  (index 1) -> 25
        // p50  (index 2) -> 50
        // p75  (index 3) -> 75
        // p90  (index 4) -> 100
        // p100 (index 4) -> 100
        
        assertThat(row[1]).isEqualTo(0);   // p0
        assertThat(row[4]).isEqualTo(50);  // p50
        assertThat(row[7]).isEqualTo(100); // p100
    }

    private TickData createTick(long currentTick, int... ages) {
        TickData.Builder builder = TickData.newBuilder().setTickNumber(currentTick);
        for (int i = 0; i < ages.length; i++) {
            // Birth tick = Current - Age
            long birth = currentTick - ages[i];
            builder.addOrganisms(OrganismState.newBuilder()
                .setOrganismId(i)
                .setBirthTick(birth)
                .build());
        }
        return builder.build();
    }
}

