package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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

