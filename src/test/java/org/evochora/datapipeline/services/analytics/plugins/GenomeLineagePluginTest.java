package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.analytics.IAnalyticsContext;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for GenomeLineagePlugin.
 * <p>
 * The run recorded here writes every 100th tick, so a recording at tick 1000 reports the organisms
 * born after tick 900.
 */
@Tag("unit")
class GenomeLineagePluginTest {

    private static final long RECORDING_INTERVAL = 100;

    private GenomeLineagePlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new GenomeLineagePlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "genome_lineage")));
        plugin.initialize(context());
    }

    @Test
    void schemaCarriesTheEdgeAndWhenItFirstAppeared() {
        ParquetSchema schema = plugin.getSchema();

        assertThat(schema.getColumnCount()).isEqualTo(4);
        assertThat(schema.getColumns().get(0).name()).isEqualTo("tick");
        assertThat(schema.getColumns().get(1).name()).isEqualTo("genome_hash");
        assertThat(schema.getColumns().get(2).name()).isEqualTo("parent_genome_hash");
        assertThat(schema.getColumns().get(3).name()).isEqualTo("first_birth_tick");
    }

    @Test
    void reportsAGenomeWithTheGenomeItAroseFrom() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(child(1, 950, 0xBBBB, 0xAAAA))
            .build();

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[0]).isEqualTo(1000L);
        assertThat(row[1]).isEqualTo(0xBBBBL);
        assertThat(row[2]).isEqualTo(0xAAAAL);
        assertThat(row[3]).isEqualTo(950L);
    }

    @Test
    void inheritingAGenomeUnchangedIsNoEdge() {
        // The common case by far: a child carries its parent's genome. That is the same genome,
        // not a descent between two - and written as an edge it would make the genome its own
        // ancestor, so it could never be a root of the tree.
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(child(1, 950, 0xAAAA, 0xAAAA))
            .build();

        assertThat(plugin.extractRows(tick)).isEmpty();
    }

    @Test
    void aMutatedChildIsAnEdgeNextToItsUnchangedSiblings() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(child(1, 950, 0xAAAA, 0xAAAA))
            .addOrganisms(child(2, 960, 0xBBBB, 0xAAAA))
            .addOrganisms(child(3, 970, 0xAAAA, 0xAAAA))
            .build();

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[1]).isEqualTo(0xBBBBL);
        assertThat(rows.get(0)[2]).isEqualTo(0xAAAAL);
    }

    @Test
    void aFoundingOrganismHasNoParentGenome() {
        // Distinguishable from a parent that carried no genome, which writes a 0
        TickData tick = TickData.newBuilder()
            .setTickNumber(0)
            .addOrganisms(founder(1, 0, 0xAAAA))
            .build();

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[1]).isEqualTo(0xAAAAL);
        assertThat(row[2]).isNull();
    }

    @Test
    void aParentWithoutAGenomeIsRecordedAsZeroRatherThanAsAbsent() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(child(1, 950, 0xBBBB, 0))
            .build();

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[2]).as("parent existed but carried no genome").isEqualTo(0L);
    }

    @Test
    void anOrganismWithoutAGenomeIsNoGenome() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(child(1, 950, 0, 0xAAAA))
            .build();

        assertThat(plugin.extractRows(tick)).isEmpty();
    }

    @Test
    void organismsBornBeforeTheWindowAreNotReportedAgain() {
        // Only tick 900 onwards belongs to the recording at 1000; 900 itself was reported there
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(child(1, 900, 0xAAAA, 0x1111))
            .addOrganisms(child(2, 901, 0xBBBB, 0x1111))
            .build();

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[1]).isEqualTo(0xBBBBL);
    }

    @Test
    void siblingsSharingAGenomeGiveOneRowWithTheEarliestBirth() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(child(1, 980, 0xBBBB, 0xAAAA))
            .addOrganisms(child(2, 950, 0xBBBB, 0xAAAA))
            .addOrganisms(child(3, 970, 0xBBBB, 0xAAAA))
            .build();

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[3]).isEqualTo(950L);
    }

    @Test
    void oneGenomeFromTwoParentsGivesBothEdges() {
        // The same mutation can arise from different genomes; deciding which is the ancestor is
        // not this plugin's business
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(child(1, 950, 0xCCCC, 0xAAAA))
            .addOrganisms(child(2, 960, 0xCCCC, 0xBBBB))
            .build();

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> row[2]).containsExactlyInAnyOrder(0xAAAAL, 0xBBBBL);
    }

    @Test
    void anOrganismThatDiedWithinTheWindowIsStillReported() {
        // Its carriers are gone from the next recording onwards, so this is the only chance
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(child(1, 950, 0xBBBB, 0xAAAA).toBuilder().setIsDead(true).build())
            .build();

        assertThat(plugin.extractRows(tick)).hasSize(1);
    }

    @Test
    void aRecordingWithoutNewOrganismsProducesNoRow() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(child(1, 500, 0xBBBB, 0xAAAA))
            .build();

        assertThat(plugin.extractRows(tick)).isEmpty();
    }

    @Test
    void theFirstRecordingReportsTheFoundingOrganisms() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(0)
            .addOrganisms(founder(1, 0, 0xAAAA))
            .addOrganisms(founder(2, 0, 0xBBBB))
            .build();

        assertThat(plugin.extractRows(tick)).hasSize(2);
    }

    @Test
    void theTableHasNoChartOfItsOwn() {
        // How many genomes are new per recording is the genome diversity chart's running total;
        // what this table is for is being read whole, as the tree the clade view groups by
        assertThat(plugin.getManifestEntry()).isNull();
        assertThat(plugin.getManifestEntries()).isEmpty();
    }

    private OrganismState founder(int id, long birthTick, long genomeHash) {
        return OrganismState.newBuilder()
            .setOrganismId(id)
            .setBirthTick(birthTick)
            .setGenomeHash(genomeHash)
            .build();
    }

    private OrganismState child(int id, long birthTick, long genomeHash, long parentGenomeHash) {
        return OrganismState.newBuilder()
            .setOrganismId(id)
            .setBirthTick(birthTick)
            .setGenomeHash(genomeHash)
            .setParentId(id - 1)
            .setParentGenomeHash(parentGenomeHash)
            .build();
    }

    private IAnalyticsContext context() {
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setResolvedConfigJson("{ \"samplingInterval\": " + RECORDING_INTERVAL + " }")
            .build();
        return new IAnalyticsContext() {
            @Override public SimulationMetadata getMetadata() { return metadata; }
            @Override public String getRunId() { return "test-run"; }
            @Override public OutputStream openArtifactStream(String m, String l, String f) throws IOException {
                throw new UnsupportedOperationException();
            }
            @Override public Path getTempDirectory() { throw new UnsupportedOperationException(); }
        };
    }
}
