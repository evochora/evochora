package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for GenomePopulationPlugin.
 * <p>
 * The table is the population side of the clade view, so the tests pin what counts as a carrier
 * and that no genome is dropped in favour of another.
 */
@Tag("unit")
class GenomePopulationPluginTest {

    private GenomePopulationPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new GenomePopulationPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "genome_population")));
        plugin.initialize(null);
    }

    @Test
    void schemaCarriesTheGenomeAndItsCarriers() {
        ParquetSchema schema = plugin.getSchema();

        assertThat(schema.getColumnCount()).isEqualTo(3);
        assertThat(schema.getColumns().get(0).name()).isEqualTo("tick");
        assertThat(schema.getColumns().get(0).type()).isEqualTo(ColumnType.BIGINT);
        assertThat(schema.getColumns().get(1).name()).isEqualTo("genome_hash");
        assertThat(schema.getColumns().get(1).type()).isEqualTo(ColumnType.BIGINT);
        assertThat(schema.getColumns().get(2).name()).isEqualTo("count");
        assertThat(schema.getColumns().get(2).type()).isEqualTo(ColumnType.INTEGER);
    }

    @Test
    void everyLivingGenomeGetsARow() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(alive(1, 0xAAAA))
            .addOrganisms(alive(2, 0xAAAA))
            .addOrganisms(alive(3, 0xBBBB))
            .build();

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row[0]).isEqualTo(1000L));
        assertThat(rows).extracting(row -> row[1]).containsExactlyInAnyOrder(0xAAAAL, 0xBBBBL);
        assertThat(rows).extracting(row -> row[2]).containsExactlyInAnyOrder(2, 1);
    }

    @Test
    void noGenomeIsDroppedInFavourOfAnother() {
        // A ranking would keep the large ones; a clade is only complete with all of its members
        TickData.Builder builder = TickData.newBuilder().setTickNumber(1000);
        for (int i = 0; i < 200; i++) {
            builder.addOrganisms(alive(i, 1000 + i));
        }

        assertThat(plugin.extractRows(builder.build())).hasSize(200);
    }

    @Test
    void theDeadAreNoLongerPopulation() {
        // They appear once in the recording after their death and must not be counted there
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(alive(1, 0xAAAA))
            .addOrganisms(alive(2, 0xAAAA).toBuilder().setIsDead(true).build())
            .build();

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[2]).isEqualTo(1);
    }

    @Test
    void anOrganismWithoutAGenomeIsNoCarrier() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(alive(1, 0))
            .addOrganisms(alive(2, 0xAAAA))
            .build();

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[1]).isEqualTo(0xAAAAL);
    }

    @Test
    void anExtinctPopulationProducesNoRow() {
        // A zero row would claim a genome is alive with no carriers
        TickData tick = TickData.newBuilder().setTickNumber(1000).build();

        assertThat(plugin.extractRows(tick)).isEmpty();
    }

    @Test
    void countsDoNotCarryOverBetweenRecordings() {
        TickData first = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(alive(1, 0xAAAA))
            .addOrganisms(alive(2, 0xAAAA))
            .build();
        TickData second = TickData.newBuilder()
            .setTickNumber(2000)
            .addOrganisms(alive(1, 0xAAAA))
            .build();

        plugin.extractRows(first);
        List<Object[]> rows = plugin.extractRows(second);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[2]).isEqualTo(1);
    }

    @Test
    void theChartReadsTheLineageAlongsideTheCounts() {
        ManifestEntry entry = plugin.getManifestEntry();

        assertThat(entry.id).isEqualTo("genome_clades");
        assertThat(entry.storageMetricId).isEqualTo("genome_population");
        assertThat(entry.visualization.type).isEqualTo("clade-area-chart");
        assertThat(entry.companionMetricId).isEqualTo("genome_lineage");
        assertThat(entry.companionQuery).contains("parent_genome_hash");
    }

    @Test
    void theLineageIsNotCondensedByTheQuery() {
        // Grouping by genome hash is a hash aggregation over an unsorted column, which the
        // browser's DuckDB build fails at beyond a few thousand rows. The chart picks the
        // earliest edge of a genome itself, so the query has nothing to condense.
        assertThat(plugin.getManifestEntry().companionQuery)
            .doesNotContainIgnoringCase("group by")
            .doesNotContainIgnoringCase("min(");
    }

    @Test
    void genomeHashesLeaveAsTextInBothQueries() {
        // 64 bits do not survive a JavaScript number: two genomes would silently become one
        ManifestEntry entry = plugin.getManifestEntry();

        assertThat(entry.generatedQuery).contains("genome_hash::VARCHAR");
        assertThat(entry.companionQuery)
            .contains("genome_hash::VARCHAR")
            .contains("parent_genome_hash::VARCHAR");
    }

    @Test
    void theLineageMetricCanBeNamedInConfiguration() {
        GenomePopulationPlugin configured = new GenomePopulationPlugin();
        configured.configure(ConfigFactory.parseMap(Map.of(
            "metricId", "genome_population",
            "lineageMetricId", "other_lineage")));

        assertThat(configured.getManifestEntry().companionMetricId).isEqualTo("other_lineage");
    }

    @Test
    void memoryIsBoundedByTheOrganismLimit() {
        SimulationParameters params = new SimulationParameters(
            new int[]{100, 100}, 10_000L, 10_000L, 1000, 1, 10, 50, 50, 0.1);

        List<MemoryEstimate> estimates = plugin.estimateWorstCaseMemory(params);

        assertThat(estimates).hasSize(1);
        assertThat(estimates.get(0).estimatedBytes()).isEqualTo(1000L * 24);
    }

    private OrganismState alive(int id, long genomeHash) {
        return OrganismState.newBuilder()
            .setOrganismId(id)
            .setGenomeHash(genomeHash)
            .build();
    }
}
