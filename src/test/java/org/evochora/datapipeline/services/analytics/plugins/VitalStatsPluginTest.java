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

/**
 * Unit tests for VitalStatsPlugin.
 * <p>
 * The plugin is stateless - it only extracts raw facts from each tick.
 * Birth/death calculations happen at query time via aggregated SQL with dynamic bucketing.
 */
@Tag("unit")
class VitalStatsPluginTest {

    private VitalStatsPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new VitalStatsPlugin();
        Config config = ConfigFactory.parseMap(Map.of("metricId", "vital_stats"));
        plugin.configure(config);
        plugin.initialize(null);
    }

    @Test
    void testExtractRows_ReturnsRawFacts() {
        // Given: A tick with some organisms
        TickData tick = createTick(100, 15, 10);
        
        // When: Extract rows
        List<Object[]> rows = plugin.extractRows(tick);
        
        // Then: Returns single row with raw facts
        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);
        assertThat(row[0]).isEqualTo(100L);  // tick
        assertThat(row[1]).isEqualTo(15L);   // total_born
        assertThat(row[2]).isEqualTo(10);    // alive_count
    }

    @Test
    void testExtractRows_IsStateless() {
        // Given: Two consecutive ticks
        TickData tick1 = createTick(100, 10, 10);
        TickData tick2 = createTick(101, 12, 11);
        
        // When: Extract rows from both
        List<Object[]> rows1 = plugin.extractRows(tick1);
        List<Object[]> rows2 = plugin.extractRows(tick2);
        
        // Then: Each returns independent raw facts (no delta calculation)
        assertThat(rows1.get(0)[1]).isEqualTo(10L);  // total_born at tick 100
        assertThat(rows2.get(0)[1]).isEqualTo(12L);  // total_born at tick 101
        
        // The plugin does NOT compute births/deaths - that happens at query time
        // No state is carried between calls
    }

    @Test
    void testManifest_ContainsAggregatedQuery() {
        // When: Get manifest
        ManifestEntry entry = plugin.getManifestEntry();
        
        // Then: Has required fields
        assertThat(entry.id).isEqualTo("vital_stats");
        assertThat(entry.name).isEqualTo("Birth & Death Rates");
        assertThat(entry.generatedQuery).isNotBlank();
        assertThat(entry.outputColumns).containsExactly("tick", "births", "deaths");
        
        // And: Visualization is configured
        assertThat(entry.visualization.type).isEqualTo("bar-chart");
    }

    @Test
    void testGeneratedQuery_HasDynamicBucketing() {
        // When: Get manifest
        ManifestEntry entry = plugin.getManifestEntry();
        String sql = entry.generatedQuery;
        
        // Then: SQL contains bucket calculation
        assertThat(sql).contains("bucket_size");
        assertThat(sql).contains("GREATEST(1,");  // minimum bucket size of 1
        assertThat(sql).contains("/ 100");         // ~100 buckets
        
        // And: SQL contains aggregation
        assertThat(sql).contains("GROUP BY");
        assertThat(sql).contains("SUM(births)");
        assertThat(sql).contains("SUM(deaths)");
        
        // And: SQL computes births/deaths via window functions
        assertThat(sql).contains("LAG(");
        assertThat(sql).contains("OVER (ORDER BY tick)");
        
        // And: Deaths are negated for mirrored display
        assertThat(sql).contains("-GREATEST(0,");
        
        // And: Has table placeholder
        assertThat(sql).contains("{table}");
    }

    @Test
    void testSchema_HasThreeColumns() {
        // When: Get schema
        var schema = plugin.getSchema();
        
        // Then: Has three columns
        assertThat(schema.getColumnCount()).isEqualTo(3);
        assertThat(schema.getColumns().get(0).name()).isEqualTo("tick");
        assertThat(schema.getColumns().get(1).name()).isEqualTo("total_born");
        assertThat(schema.getColumns().get(2).name()).isEqualTo("alive_count");
    }

    /**
     * Creates a TickData for testing.
     *
     * @param tickNum Tick number
     * @param totalCreated Total organisms ever created
     * @param aliveCount Current alive count
     * @return TickData instance
     */
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

