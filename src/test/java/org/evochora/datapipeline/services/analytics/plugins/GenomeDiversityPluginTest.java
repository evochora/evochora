package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
 * Unit tests for GenomeDiversityPlugin.
 */
@Tag("unit")
class GenomeDiversityPluginTest {

    private GenomeDiversityPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new GenomeDiversityPlugin();
        Config config = ConfigFactory.parseMap(Map.of("metricId", "genome_diversity"));
        plugin.configure(config);
        plugin.initialize(null); // Initialize state
    }

    @Test
    void testGetSchema_ReturnsCorrectColumns() {
        ParquetSchema schema = plugin.getSchema();

        assertThat(schema).isNotNull();
        assertThat(schema.getColumnCount()).isEqualTo(5);

        List<ParquetSchema.Column> columns = schema.getColumns();
        assertThat(columns.get(0).name()).isEqualTo("tick");
        assertThat(columns.get(0).type()).isEqualTo(ColumnType.BIGINT);

        assertThat(columns.get(1).name()).isEqualTo("shannon_index");
        assertThat(columns.get(1).type()).isEqualTo(ColumnType.DOUBLE);

        assertThat(columns.get(2).name()).isEqualTo("total_genomes");
        assertThat(columns.get(2).type()).isEqualTo(ColumnType.INTEGER);

        assertThat(columns.get(3).name()).isEqualTo("active_genomes");
        assertThat(columns.get(3).type()).isEqualTo(ColumnType.INTEGER);

        assertThat(columns.get(4).name()).isEqualTo("dominant_share");
        assertThat(columns.get(4).type()).isEqualTo(ColumnType.DOUBLE);
    }

    @Test
    void testExtractRows_SingleGenome_ShannonIndexZero() {
        // All organisms have the same genome -> H = 0
        TickData tick = createTickWithGenomes(100, 123456L, 123456L, 123456L);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);

        assertThat(row[0]).isEqualTo(100L); // tick
        assertThat((Double) row[1]).isCloseTo(0.0, within(0.001)); // shannon_index
        assertThat(row[2]).isEqualTo(1); // total_genomes
        assertThat(row[3]).isEqualTo(1); // active_genomes
        assertThat((Double) row[4]).isCloseTo(1.0, within(0.001)); // dominant_share
    }

    @Test
    void testExtractRows_TwoEqualGenomes_ShannonIndexLn2() {
        // 50/50 split between two genomes -> H = ln(2) ≈ 0.693
        TickData tick = createTickWithGenomes(100, 111L, 111L, 222L, 222L);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);

        assertThat((Double) row[1]).isCloseTo(Math.log(2), within(0.001)); // shannon_index = ln(2)
        assertThat(row[2]).isEqualTo(2); // total_genomes
        assertThat(row[3]).isEqualTo(2); // active_genomes
        assertThat((Double) row[4]).isCloseTo(0.5, within(0.001)); // dominant_share
    }

    @Test
    void testExtractRows_ThreeEqualGenomes_ShannonIndexLn3() {
        // Equal split among three genomes -> H = ln(3) ≈ 1.099
        TickData tick = createTickWithGenomes(100, 111L, 222L, 333L);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);

        assertThat((Double) row[1]).isCloseTo(Math.log(3), within(0.001)); // shannon_index = ln(3)
        assertThat(row[2]).isEqualTo(3); // total_genomes
        assertThat(row[3]).isEqualTo(3); // active_genomes
    }

    @Test
    void testExtractRows_DominantGenome_LowShannonIndex() {
        // 8 organisms with genome A, 1 each for B and C -> low Shannon index
        TickData tick = createTickWithGenomes(100,
            111L, 111L, 111L, 111L, 111L, 111L, 111L, 111L, // 8x genome A
            222L, 333L); // 1x each B and C

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);

        // H should be low because of dominance
        assertThat((Double) row[1]).isLessThan(1.0);
        assertThat(row[2]).isEqualTo(3); // total_genomes
        assertThat(row[3]).isEqualTo(3); // active_genomes
        assertThat((Double) row[4]).isCloseTo(0.8, within(0.001)); // dominant_share: 8/10
    }

    @Test
    void testExtractRows_NoOrganisms_ReturnsZeros() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(50)
            .build();

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);

        assertThat(row[0]).isEqualTo(50L);
        assertThat(row[1]).isEqualTo(0.0); // shannon_index
        assertThat(row[2]).isEqualTo(0); // total_genomes
        assertThat(row[3]).isEqualTo(0); // active_genomes
        assertThat(row[4]).isEqualTo(0.0); // dominant_share
    }

    @Test
    void testExtractRows_OrganismsWithoutGenomeHash_Ignored() {
        // Organisms with genomeHash = 0 should be ignored
        TickData.Builder builder = TickData.newBuilder().setTickNumber(100);
        builder.addOrganisms(OrganismState.newBuilder().setOrganismId(1).setGenomeHash(0L).build());
        builder.addOrganisms(OrganismState.newBuilder().setOrganismId(2).setGenomeHash(111L).build());

        List<Object[]> rows = plugin.extractRows(builder.build());

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);

        assertThat(row[2]).isEqualTo(1); // total_genomes (only one valid)
        assertThat(row[3]).isEqualTo(1); // active_genomes
    }

    @Test
    void testExtractRows_TotalGenomes_CumulativeAcrossTicks() {
        // First tick: genomes A and B
        TickData tick1 = createTickWithGenomes(100, 111L, 222L);
        plugin.extractRows(tick1);

        // Second tick: genomes B and C (A is gone, C is new)
        TickData tick2 = createTickWithGenomes(200, 222L, 333L);
        List<Object[]> rows = plugin.extractRows(tick2);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);

        assertThat(row[2]).isEqualTo(3); // total_genomes: A, B, C (cumulative)
        assertThat(row[3]).isEqualTo(2); // active_genomes: B, C (current tick)
    }

    @Test
    void testManifestEntry_ContainsCorrectMetadata() {
        ManifestEntry entry = plugin.getManifestEntry();

        assertThat(entry.id).isEqualTo("genome_diversity");
        assertThat(entry.name).isEqualTo("Genome Diversity");
        assertThat(entry.description).contains("Shannon");

        assertThat(entry.dataSources).containsKey("lod0");
        assertThat(entry.visualization.type).isEqualTo("line-chart");
        assertThat(entry.visualization.config.get("x")).isEqualTo("tick");
    }

    private TickData createTickWithGenomes(long tickNum, Long... genomeHashes) {
        TickData.Builder builder = TickData.newBuilder()
            .setTickNumber(tickNum);

        int id = 1;
        for (Long hash : genomeHashes) {
            builder.addOrganisms(OrganismState.newBuilder()
                .setOrganismId(id++)
                .setGenomeHash(hash)
                .build());
        }
        return builder.build();
    }
}
