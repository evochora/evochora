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
 * Unit tests for {@link GenomeDiversityPlugin}.
 * <p>
 * The four numbers describe the spread of the living population at one moment, so the tests pin
 * them against populations whose diversity is known by construction.
 */
@Tag("unit")
class GenomeDiversityPluginTest {

    private GenomeDiversityPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new GenomeDiversityPlugin();
        Config config = ConfigFactory.parseMap(Map.of("metricId", "genome"));
        plugin.configure(config);
        plugin.initialize(null);
    }

    // ========================================================================
    // Schema Tests
    // ========================================================================

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

    // ========================================================================
    // Diversity Metric Tests
    // ========================================================================

    @Test
    void testExtractRows_SingleGenome_ShannonIndexZero() {
        TickData tick = createTickWithGenomes(100, 123456L, 123456L, 123456L);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);

        assertThat(row[0]).isEqualTo(100L);
        assertThat((Double) row[1]).isCloseTo(0.0, within(0.001)); // shannon_index
        assertThat(row[2]).isEqualTo(1); // total_genomes
        assertThat(row[3]).isEqualTo(1); // active_genomes
        assertThat((Double) row[4]).isCloseTo(1.0, within(0.001)); // dominant_share
    }

    @Test
    void testExtractRows_TwoEqualGenomes_ShannonIndexLn2() {
        TickData tick = createTickWithGenomes(100, 111L, 111L, 222L, 222L);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);

        assertThat((Double) row[1]).isCloseTo(Math.log(2), within(0.001));
        assertThat(row[2]).isEqualTo(2);
        assertThat(row[3]).isEqualTo(2);
        assertThat((Double) row[4]).isCloseTo(0.5, within(0.001));
    }

    @Test
    void testExtractRows_ThreeEqualGenomes_ShannonIndexLn3() {
        TickData tick = createTickWithGenomes(100, 111L, 222L, 333L);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);

        assertThat((Double) row[1]).isCloseTo(Math.log(3), within(0.001));
        assertThat(row[2]).isEqualTo(3);
        assertThat(row[3]).isEqualTo(3);
    }

    @Test
    void testExtractRows_DominantGenome_LowShannonIndex() {
        TickData tick = createTickWithGenomes(100,
            111L, 111L, 111L, 111L, 111L, 111L, 111L, 111L,
            222L, 333L);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);

        assertThat((Double) row[1]).isLessThan(1.0);
        assertThat(row[2]).isEqualTo(3);
        assertThat(row[3]).isEqualTo(3);
        assertThat((Double) row[4]).isCloseTo(0.8, within(0.001));
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
        assertThat(row[1]).isEqualTo(0.0);
        assertThat(row[2]).isEqualTo(0);
        assertThat(row[3]).isEqualTo(0);
        assertThat(row[4]).isEqualTo(0.0);
    }

    @Test
    void testExtractRows_OrganismsWithoutGenomeHash_Ignored() {
        TickData.Builder builder = TickData.newBuilder().setTickNumber(100)
            .setTotalUniqueGenomes(1);
        builder.addOrganisms(OrganismState.newBuilder().setOrganismId(1).setGenomeHash(0L).build());
        builder.addOrganisms(OrganismState.newBuilder().setOrganismId(2).setGenomeHash(111L).build());

        List<Object[]> rows = plugin.extractRows(builder.build());

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);

        assertThat(row[2]).isEqualTo(1); // total_genomes
        assertThat(row[3]).isEqualTo(1); // active_genomes
        assertThat((Double) row[4]).isCloseTo(1.0, within(0.001)); // the one genome holds all of it
    }

    @Test
    void testExtractRows_DeadOrganisms_Ignored() {
        // The dead appear once in the recording after their death and are no longer population
        TickData tick = TickData.newBuilder()
            .setTickNumber(100)
            .setTotalUniqueGenomes(2)
            .addOrganisms(OrganismState.newBuilder().setOrganismId(1).setGenomeHash(111L).build())
            .addOrganisms(OrganismState.newBuilder().setOrganismId(2).setGenomeHash(222L)
                .setIsDead(true).build())
            .build();

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[3]).isEqualTo(1);
        assertThat((Double) row[4]).isCloseTo(1.0, within(0.001));
    }

    @Test
    void testExtractRows_TotalGenomes_ReadsFromTickData() {
        // totalUniqueGenomes is provided by the pipeline (tracked in Simulation),
        // not computed cumulatively by the plugin.
        TickData tick = TickData.newBuilder()
            .setTickNumber(100)
            .setTotalUniqueGenomes(5)
            .addOrganisms(OrganismState.newBuilder().setOrganismId(1).setGenomeHash(111L).build())
            .addOrganisms(OrganismState.newBuilder().setOrganismId(2).setGenomeHash(222L).build())
            .build();

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);

        assertThat(row[2]).isEqualTo(5); // total_genomes: reads from TickData field
        assertThat(row[3]).isEqualTo(2); // active_genomes: computed from organisms in tick
    }

    // ========================================================================
    // Manifest Tests
    // ========================================================================

    @Test
    void testGetManifestEntry_DescribesTheDiversityChart() {
        ManifestEntry diversity = plugin.getManifestEntry();

        assertThat(diversity.id).isEqualTo("genome_diversity");
        assertThat(diversity.storageMetricId).isEqualTo("genome");
        assertThat(diversity.name).isEqualTo("Genome Diversity");
        assertThat(diversity.description).contains("Shannon");

        assertThat(diversity.dataSources).containsKey("lod0");
        assertThat(diversity.visualization.type).isEqualTo("line-chart");
        assertThat(diversity.visualization.config.get("x")).isEqualTo("tick");
        assertThat(diversity.visualization.config.get("y")).isEqualTo(List.of("shannon_index", "dominant_share"));
        assertThat(diversity.visualization.config.get("yFormat")).isEqualTo("decimal");
        assertThat(diversity.visualization.config.get("y2")).isEqualTo(List.of("total_genomes", "active_genomes"));
        assertThat(diversity.visualization.config.get("y2Format")).isEqualTo("integer");
    }

    @Test
    void testGetManifestEntries_HoldsTheOneChart() {
        assertThat(plugin.getManifestEntries()).hasSize(1);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private TickData createTickWithGenomes(long tickNum, Long... genomeHashes) {
        TickData.Builder builder = TickData.newBuilder()
            .setTickNumber(tickNum);

        java.util.Set<Long> uniqueHashes = new java.util.HashSet<>();
        int id = 1;
        for (Long hash : genomeHashes) {
            builder.addOrganisms(OrganismState.newBuilder()
                .setOrganismId(id++)
                .setGenomeHash(hash)
                .build());
            if (hash != 0L) {
                uniqueHashes.add(hash);
            }
        }

        builder.setTotalUniqueGenomes(uniqueHashes.size());
        return builder.build();
    }
}
