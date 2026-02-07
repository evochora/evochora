package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
 * Unit tests for GenomePopulationPlugin.
 */
@Tag("unit")
class GenomePopulationPluginTest {

    private GenomePopulationPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new GenomePopulationPlugin();
        Config config = ConfigFactory.parseMap(Map.of(
            "metricId", "genome_population",
            "topN", 3 // Use small topN for testing
        ));
        plugin.configure(config);
        plugin.initialize(null); // Initialize state
    }

    @Test
    void testGetSchema_ReturnsCorrectColumns() {
        ParquetSchema schema = plugin.getSchema();

        assertThat(schema).isNotNull();
        assertThat(schema.getColumnCount()).isEqualTo(3);

        List<ParquetSchema.Column> columns = schema.getColumns();
        assertThat(columns.get(0).name()).isEqualTo("tick");
        assertThat(columns.get(0).type()).isEqualTo(ColumnType.BIGINT);

        assertThat(columns.get(1).name()).isEqualTo("genome_label");
        assertThat(columns.get(1).type()).isEqualTo(ColumnType.VARCHAR);

        assertThat(columns.get(2).name()).isEqualTo("count");
        assertThat(columns.get(2).type()).isEqualTo(ColumnType.INTEGER);
    }

    @Test
    void testExtractRows_SingleGenome_OneRow() {
        TickData tick = createTickWithGenomes(100, 123456L, 123456L, 123456L);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);

        assertThat(row[0]).isEqualTo(100L); // tick
        assertThat(row[1]).isNotNull(); // genome_label (Base62)
        assertThat(((String) row[1]).length()).isEqualTo(6); // 6 chars
        assertThat(row[2]).isEqualTo(3); // count
    }

    @Test
    void testExtractRows_MultipleGenomes_OneRowPerGenome() {
        TickData tick = createTickWithGenomes(100,
            111L, 111L, 111L, 111L, 111L, // 5x A
            222L, 222L, 222L,             // 3x B
            333L);                         // 1x C

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(3);

        // Collect labels and counts
        Map<String, Integer> labelCounts = rows.stream()
            .collect(Collectors.toMap(
                r -> (String) r[1],
                r -> (Integer) r[2]
            ));

        // Should have 3 different labels with counts 5, 3, 1
        assertThat(labelCounts.values()).containsExactlyInAnyOrder(5, 3, 1);
    }

    @Test
    void testExtractRows_MoreThanTopN_CreatesOtherCategory() {
        // 5 different genomes, but topN = 3, so 2 should be aggregated as "other"
        TickData tick = createTickWithGenomes(100,
            111L, 111L, 111L, 111L, 111L, // 5x A
            222L, 222L, 222L, 222L,       // 4x B
            333L, 333L, 333L,             // 3x C
            444L, 444L,                   // 2x D
            555L);                         // 1x E

        List<Object[]> rows = plugin.extractRows(tick);

        // 3 tracked genomes + 1 "other"
        assertThat(rows).hasSize(4);

        // Find the "other" row
        Object[] otherRow = rows.stream()
            .filter(r -> "other".equals(r[1]))
            .findFirst()
            .orElseThrow();

        assertThat(otherRow[2]).isEqualTo(3); // D(2) + E(1) = 3
    }

    @Test
    void testExtractRows_NoOrganisms_EmptyList() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(50)
            .build();

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).isEmpty();
    }

    @Test
    void testExtractRows_OrganismsWithoutGenomeHash_Ignored() {
        TickData.Builder builder = TickData.newBuilder().setTickNumber(100);
        builder.addOrganisms(OrganismState.newBuilder().setOrganismId(1).setGenomeHash(0L).build());
        builder.addOrganisms(OrganismState.newBuilder().setOrganismId(2).setGenomeHash(111L).build());

        List<Object[]> rows = plugin.extractRows(builder.build());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[2]).isEqualTo(1); // Only the valid genome counted
    }

    @Test
    void testExtractRows_GenomeLabel_Base62Format() {
        TickData tick = createTickWithGenomes(100, 123456789L);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        String label = (String) rows.get(0)[1];

        // Should be 6 characters, alphanumeric
        assertThat(label).hasSize(6);
        assertThat(label).matches("[0-9a-zA-Z]{6}");
    }

    @Test
    void testExtractRows_ExactlyTopN_NoOtherCategory() {
        // Exactly 3 genomes = topN, no "other" needed
        TickData tick = createTickWithGenomes(100, 111L, 222L, 333L);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(3);
        // No "other" row
        assertThat(rows.stream().noneMatch(r -> "other".equals(r[1]))).isTrue();
    }

    @Test
    void testExtractRows_ConsistentLabelsAcrossTicks() {
        // First tick: A dominates
        TickData tick1 = createTickWithGenomes(100,
            111L, 111L, 111L, 111L, 111L); // 5x A

        List<Object[]> rows1 = plugin.extractRows(tick1);
        String labelA = (String) rows1.get(0)[1];

        // Second tick: A still present
        TickData tick2 = createTickWithGenomes(200,
            111L, 111L); // 2x A

        List<Object[]> rows2 = plugin.extractRows(tick2);
        String labelA2 = (String) rows2.get(0)[1];

        // Same genome should have same label
        assertThat(labelA).isEqualTo(labelA2);
    }

    @Test
    void testManifestEntry_ContainsCorrectMetadata() {
        ManifestEntry entry = plugin.getManifestEntry();

        assertThat(entry.id).isEqualTo("genome_population");
        assertThat(entry.name).isEqualTo("Genome Population");
        assertThat(entry.description).contains("top 3");

        assertThat(entry.dataSources).containsKey("lod0");
        assertThat(entry.visualization.type).isEqualTo("stacked-area-chart");
        assertThat(entry.visualization.config.get("x")).isEqualTo("tick");
        assertThat(entry.visualization.config.get("y")).isEqualTo("count");
        assertThat(entry.visualization.config.get("groupBy")).isEqualTo("genome_label");
    }

    @Test
    void testConfigure_DefaultTopN() {
        GenomePopulationPlugin defaultPlugin = new GenomePopulationPlugin();
        Config config = ConfigFactory.parseMap(Map.of("metricId", "test"));
        defaultPlugin.configure(config);

        ManifestEntry entry = defaultPlugin.getManifestEntry();
        assertThat(entry.description).contains("top 10");
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
