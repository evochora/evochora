package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

@Tag("unit")
class BirthDeathMetricsPluginTest {

    private BirthDeathMetricsPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new BirthDeathMetricsPlugin();
        Config config = ConfigFactory.parseMap(Map.of("metricId", "vital_rates"));
        plugin.configure(config);
        plugin.initialize(null);
    }

    @Test
    void testExtractRows_FirstTick_ReturnsZeros() {
        // First tick: no history, so deltas are 0
        TickData tick = createTick(100, 10, 5);
        
        List<Object[]> rows = plugin.extractRows(tick);
        
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[1]).isEqualTo(0); // births
        assertThat(rows.get(0)[2]).isEqualTo(0); // deaths
    }

    @Test
    void testExtractRows_CalculatesBirthsAndDeaths() {
        // Step 1: Initial state (10 born total, 5 alive)
        plugin.extractRows(createTick(100, 10, 5));
        
        // Step 2: Next tick (12 born total, 6 alive)
        // Analysis:
        // - Total born increased from 10 to 12 => 2 new births
        // - Alive went from 5 to 6
        // - Expected Deaths: (PrevAlive + Births) - CurrAlive = (5 + 2) - 6 = 1 death
        TickData tick2 = createTick(101, 12, 6);
        List<Object[]> rows = plugin.extractRows(tick2);
        
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo(101L);
        assertThat(rows.get(0)[1]).isEqualTo(2); // births
        assertThat(rows.get(0)[2]).isEqualTo(1); // deaths
    }
    
    @Test
    void testExtractRows_StablePopulation() {
        // Step 1: 10 born, 10 alive
        plugin.extractRows(createTick(100, 10, 10));
        
        // Step 2: 10 born, 10 alive (nothing happened)
        List<Object[]> rows = plugin.extractRows(createTick(101, 10, 10));
        
        assertThat(rows.get(0)[1]).isEqualTo(0);
        assertThat(rows.get(0)[2]).isEqualTo(0);
    }

    @Test
    void testManifest() {
        ManifestEntry entry = plugin.getManifestEntry();
        assertThat(entry.id).isEqualTo("vital_rates");
        assertThat(entry.visualization.type).isEqualTo("bar-chart");
        assertThat(entry.visualization.config.get("style")).isEqualTo("mirrored");
    }

    private TickData createTick(long tickNum, long totalCreated, int aliveCount) {
        TickData.Builder builder = TickData.newBuilder()
                .setTickNumber(tickNum)
                .setTotalOrganismsCreated(totalCreated);
        
        for (int i = 0; i < aliveCount; i++) {
            builder.addOrganisms(OrganismState.newBuilder().setOrganismId(i).build());
        }
        return builder.build();
    }
}

