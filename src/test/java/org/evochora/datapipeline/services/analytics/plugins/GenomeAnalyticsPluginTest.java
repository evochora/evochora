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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for {@link GenomeAnalyticsPlugin}.
 * <p>
 * Tests both diversity metrics (Shannon index, genome counts, dominant share) and
 * population distribution (topN tracking, JSON output, Base62 labels).
 */
@Tag("unit")
class GenomeAnalyticsPluginTest {

    private GenomeAnalyticsPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new GenomeAnalyticsPlugin();
        Config config = ConfigFactory.parseMap(Map.of(
            "metricId", "genome",
            "topN", 3
        ));
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
        assertThat(schema.getColumnCount()).isEqualTo(6);

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

        assertThat(columns.get(5).name()).isEqualTo("genome_data");
        assertThat(columns.get(5).type()).isEqualTo(ColumnType.VARCHAR);
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
    void testExtractRows_NoOrganisms_ReturnsZerosAndEmptyJson() {
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
        assertThat(row[5]).isEqualTo("{}");
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

        JsonObject json = JsonParser.parseString((String) row[5]).getAsJsonObject();
        int total = json.entrySet().stream().mapToInt(e -> e.getValue().getAsInt()).sum();
        assertThat(total).isEqualTo(1);
    }

    @Test
    void testExtractRows_TotalGenomes_ReadsFromTickData() {
        // totalUniqueGenomes is now provided by the pipeline (tracked in Simulation),
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
    // Population JSON Tests
    // ========================================================================

    @Test
    void testExtractRows_SingleGenome_OneRowWithJson() {
        TickData tick = createTickWithGenomes(100, 123456L, 123456L, 123456L);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        JsonObject json = JsonParser.parseString((String) rows.get(0)[5]).getAsJsonObject();
        assertThat(json.entrySet()).hasSize(1);

        int count = json.entrySet().iterator().next().getValue().getAsInt();
        assertThat(count).isEqualTo(3);
    }

    @Test
    void testExtractRows_MultipleGenomes_AllInOneJsonRow() {
        TickData tick = createTickWithGenomes(100,
            111L, 111L, 111L, 111L, 111L,
            222L, 222L, 222L,
            333L);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        JsonObject json = JsonParser.parseString((String) rows.get(0)[5]).getAsJsonObject();
        assertThat(json.entrySet()).hasSize(3);

        int total = json.entrySet().stream().mapToInt(e -> e.getValue().getAsInt()).sum();
        assertThat(total).isEqualTo(9);
    }

    @Test
    void testExtractRows_MoreThanTopN_CreatesOtherCategory() {
        TickData tick = createTickWithGenomes(100,
            111L, 111L, 111L, 111L, 111L,
            222L, 222L, 222L, 222L,
            333L, 333L, 333L,
            444L, 444L,
            555L);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        JsonObject json = JsonParser.parseString((String) rows.get(0)[5]).getAsJsonObject();

        assertThat(json.entrySet()).hasSize(4); // 3 tracked + "other"
        assertThat(json.has("other")).isTrue();
        assertThat(json.get("other").getAsInt()).isEqualTo(3); // D(2) + E(1) = 3
    }

    @Test
    void testExtractRows_ExactlyTopN_NoOtherCategory() {
        TickData tick = createTickWithGenomes(100, 111L, 222L, 333L);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        JsonObject json = JsonParser.parseString((String) rows.get(0)[5]).getAsJsonObject();

        assertThat(json.entrySet()).hasSize(3);
        assertThat(json.has("other")).isFalse();
    }

    @Test
    void testExtractRows_GenomeLabel_Base62Format() {
        TickData tick = createTickWithGenomes(100, 123456789L);

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        JsonObject json = JsonParser.parseString((String) rows.get(0)[5]).getAsJsonObject();

        String label = json.keySet().iterator().next();
        assertThat(label).hasSize(6);
        assertThat(label).matches("[0-9a-zA-Z]{6}");
    }

    @Test
    void testExtractRows_GenomeTurnover_TracksActiveGenomes() {
        // Tick 1: genomes A(5), B(3), C(2) - all fit in topN=3
        TickData tick1 = createTickWithGenomes(100,
            111L, 111L, 111L, 111L, 111L,
            222L, 222L, 222L,
            333L, 333L);

        List<Object[]> rows1 = plugin.extractRows(tick1);
        JsonObject json1 = JsonParser.parseString((String) rows1.get(0)[5]).getAsJsonObject();
        assertThat(json1.has("other")).isFalse();

        // Simulate many ticks to build up large cumulative counts for A/B/C
        for (int i = 0; i < 100; i++) {
            plugin.extractRows(createTickWithGenomes(200 + i,
                111L, 111L, 111L, 111L, 111L,
                222L, 222L, 222L,
                333L, 333L));
        }

        // Tick N: genomes D(5), E(3), F(2) - complete turnover, A/B/C extinct
        TickData tickN = createTickWithGenomes(1000,
            444L, 444L, 444L, 444L, 444L,
            555L, 555L, 555L,
            666L, 666L);

        List<Object[]> rows2 = plugin.extractRows(tickN);
        JsonObject json2 = JsonParser.parseString((String) rows2.get(0)[5]).getAsJsonObject();

        // All 3 new genomes must be tracked, nothing in "other"
        assertThat(json2.has("other")).as("extinct genomes must not occupy top N slots").isFalse();
        assertThat(json2.entrySet()).hasSize(3);

        int total = json2.entrySet().stream().mapToInt(e -> e.getValue().getAsInt()).sum();
        assertThat(total).isEqualTo(10);
    }

    @Test
    void testExtractRows_ConsistentLabelsAcrossTicks() {
        TickData tick1 = createTickWithGenomes(100,
            111L, 111L, 111L, 111L, 111L);

        List<Object[]> rows1 = plugin.extractRows(tick1);
        JsonObject json1 = JsonParser.parseString((String) rows1.get(0)[5]).getAsJsonObject();
        String labelA = json1.keySet().iterator().next();

        TickData tick2 = createTickWithGenomes(200, 111L, 111L);

        List<Object[]> rows2 = plugin.extractRows(tick2);
        JsonObject json2 = JsonParser.parseString((String) rows2.get(0)[5]).getAsJsonObject();
        String labelA2 = json2.keySet().iterator().next();

        assertThat(labelA).isEqualTo(labelA2);
    }

    // ========================================================================
    // Manifest Tests
    // ========================================================================

    @Test
    void testGetManifestEntry_ReturnsNull() {
        assertThat(plugin.getManifestEntry()).isNull();
    }

    @Test
    void testGetManifestEntries_ReturnsTwoEntries() {
        List<ManifestEntry> entries = plugin.getManifestEntries();

        assertThat(entries).hasSize(2);
    }

    @Test
    void testGetManifestEntries_DiversityEntry() {
        List<ManifestEntry> entries = plugin.getManifestEntries();
        ManifestEntry diversity = entries.get(0);

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
    void testGetManifestEntries_PopulationEntry() {
        List<ManifestEntry> entries = plugin.getManifestEntries();
        ManifestEntry population = entries.get(1);

        assertThat(population.id).isEqualTo("genome_population");
        assertThat(population.storageMetricId).isEqualTo("genome");
        assertThat(population.name).isEqualTo("Genome Population");
        assertThat(population.description).contains("top 3");

        assertThat(population.dataSources).containsKey("lod0");
        assertThat(population.visualization.type).isEqualTo("stacked-area-chart");
        assertThat(population.visualization.config.get("x")).isEqualTo("tick");
        assertThat(population.visualization.config.get("jsonColumn")).isEqualTo("genome_data");
        assertThat(population.visualization.config.get("maxGroups")).isEqualTo(3);
        assertThat(population.visualization.config.get("yFormat")).isEqualTo("integer");
    }

    @Test
    void testConfigure_DefaultTopN() {
        GenomeAnalyticsPlugin defaultPlugin = new GenomeAnalyticsPlugin();
        Config config = ConfigFactory.parseMap(Map.of("metricId", "test"));
        defaultPlugin.configure(config);

        List<ManifestEntry> entries = defaultPlugin.getManifestEntries();
        ManifestEntry population = entries.get(1);
        assertThat(population.description).contains("top 10");
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
