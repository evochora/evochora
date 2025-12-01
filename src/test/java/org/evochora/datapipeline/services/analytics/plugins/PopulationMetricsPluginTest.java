package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.contracts.OrganismState;
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
 */
@Tag("unit")
class PopulationMetricsPluginTest {

    private PopulationMetricsPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new PopulationMetricsPlugin();
        Config config = ConfigFactory.parseMap(Map.of("metricId", "population"));
        plugin.configure(config);
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
        
        assertThat(columns.get(2).name()).isEqualTo("total_dead");
        assertThat(columns.get(2).type()).isEqualTo(ColumnType.BIGINT);
        
        assertThat(columns.get(3).name()).isEqualTo("avg_energy");
        assertThat(columns.get(3).type()).isEqualTo(ColumnType.DOUBLE);
    }

    @Test
    void testExtractRows_SingleTick_ReturnsCorrectValues() {
        // Setup: 2 organisms, 10 total created, each with 500 energy
        TickData tick = createTick(100, 10, 2, 500);
        
        List<Object[]> rows = plugin.extractRows(tick);
        
        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);
        
        assertThat(row[0]).isEqualTo(100L);  // tick
        assertThat(row[1]).isEqualTo(2);     // alive_count
        assertThat(row[2]).isEqualTo(8L);    // total_dead (10 - 2)
        assertThat(row[3]).isEqualTo(500.0); // avg_energy
    }

    @Test
    void testExtractRows_NoOrganisms_ReturnsZeroAvgEnergy() {
        // Setup: 0 organisms, 5 total created
        TickData tick = createTick(50, 5, 0, 0);
        
        List<Object[]> rows = plugin.extractRows(tick);
        
        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);
        
        assertThat(row[0]).isEqualTo(50L);  // tick
        assertThat(row[1]).isEqualTo(0);    // alive_count
        assertThat(row[2]).isEqualTo(5L);   // total_dead (all dead)
        assertThat(row[3]).isEqualTo(0.0);  // avg_energy (no organisms)
    }
    
    @Test
    void testExtractRows_CalculatesAverageEnergy() {
        // Setup: 3 organisms with energies 100, 200, 300 -> avg = 200
        TickData.Builder builder = TickData.newBuilder()
            .setTickNumber(1)
            .setTotalOrganismsCreated(3);
        
        builder.addOrganisms(OrganismState.newBuilder().setOrganismId(1).setEnergy(100).build());
        builder.addOrganisms(OrganismState.newBuilder().setOrganismId(2).setEnergy(200).build());
        builder.addOrganisms(OrganismState.newBuilder().setOrganismId(3).setEnergy(300).build());
        
        List<Object[]> rows = plugin.extractRows(builder.build());
        
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[3]).isEqualTo(200.0); // (100+200+300)/3
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

    private TickData createTick(long tickNum, long totalCreated, int aliveCount, int energyPerOrganism) {
        TickData.Builder builder = TickData.newBuilder()
                .setTickNumber(tickNum)
                .setTotalOrganismsCreated(totalCreated);
        
        for (int i = 0; i < aliveCount; i++) {
            builder.addOrganisms(OrganismState.newBuilder()
                .setOrganismId(i)
                .setEnergy(energyPerOrganism)
                .build());
        }
        return builder.build();
    }
}
