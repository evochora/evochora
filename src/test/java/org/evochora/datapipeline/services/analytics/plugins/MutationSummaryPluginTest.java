package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.IAnalyticsContext;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.contracts.MutationEvent;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.storage.PublishedOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for {@link MutationSummaryPlugin}.
 * <p>
 * The world is 64 by 64 and wraps, so a flat index is {@code first * 64 + second} in the row-major
 * numbering the cells are persisted by, and a position on the far side of the origin is reported as
 * a small negative offset rather than as nearly a world away.
 */
@Tag("unit")
class MutationSummaryPluginTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;

    private MutationSummaryPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new MutationSummaryPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "mutation_summary")));
        plugin.initialize(context(true));
    }

    @Test
    void schemaCarriesTheBirthTheEventAndWhereItWrote() {
        ParquetSchema schema = plugin.getSchema();

        assertThat(schema.getColumnCount()).isEqualTo(12);
        List<ParquetSchema.Column> columns = schema.getColumns();
        assertThat(columns.get(0).name()).isEqualTo("tick");
        assertThat(columns.get(0).type()).isEqualTo(ColumnType.BIGINT);
        assertThat(columns.get(1).name()).isEqualTo("birth_tick");
        assertThat(columns.get(1).type()).isEqualTo(ColumnType.BIGINT);
        assertThat(columns.get(2).name()).isEqualTo("organism_id");
        assertThat(columns.get(2).type()).isEqualTo(ColumnType.INTEGER);
        assertThat(columns.get(3).name()).isEqualTo("genome_hash");
        assertThat(columns.get(3).type()).isEqualTo(ColumnType.BIGINT);
        assertThat(columns.get(4).name()).isEqualTo("parent_genome_hash");
        assertThat(columns.get(5).name()).isEqualTo("event_index");
        assertThat(columns.get(5).type()).isEqualTo(ColumnType.INTEGER);
        assertThat(columns.get(6).name()).isEqualTo("plugin_class");
        assertThat(columns.get(6).type()).isEqualTo(ColumnType.VARCHAR);
        assertThat(columns.get(7).name()).isEqualTo("kind");
        assertThat(columns.get(8).name()).isEqualTo("cell_count");
        assertThat(columns.get(8).type()).isEqualTo(ColumnType.INTEGER);
        assertThat(columns.get(9).name()).isEqualTo("position");
        assertThat(columns.get(9).type()).isEqualTo(ColumnType.VARCHAR);
        assertThat(columns.get(10).name()).isEqualTo("dv");
        assertThat(columns.get(10).type()).isEqualTo(ColumnType.VARCHAR);
        assertThat(columns.get(11).name()).isEqualTo("params");
        assertThat(columns.get(11).type()).isEqualTo(ColumnType.VARCHAR);
    }

    @Test
    void oneRowPerEventWhateverItWrote() {
        MutationEvent duplication = MutationEvent.newBuilder()
            .setPluginClass("org.example.Duplication")
            .setKind("duplication")
            .addCells(flat(13, 4)).addOldValues(0).addNewValues(11)
            .addCells(flat(14, 4)).addOldValues(0).addNewValues(12)
            .addCells(flat(15, 4)).addOldValues(0).addNewValues(13)
            .addParams(64L)
            .addDv(1).addDv(0)
            .build();
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(newborn(7, 990, 0, 0).toBuilder()
                .addBirthMutations(duplication)
                .build())
            .build();

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);
        assertThat(row[0]).isEqualTo(1000L);
        assertThat(row[1]).isEqualTo(990L);
        assertThat(row[2]).isEqualTo(7);
        assertThat(row[5]).as("the event's ordinal within this birth").isEqualTo(0);
        assertThat(row[6]).isEqualTo("org.example.Duplication");
        assertThat(row[7]).isEqualTo("duplication");
        assertThat(row[8]).isEqualTo(3);
        assertThat(row[9]).isEqualTo("[13,4]");
        assertThat(row[10]).isEqualTo("[1,0]");
        assertThat(row[11]).isEqualTo("[64]");
    }

    @Test
    void theAncestryOfTheNewbornIsOnEveryRow() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(newborn(7, 990, 0, 0).toBuilder()
                .setGenomeHash(0x1234L)
                .setParentGenomeHash(0x5678L)
                .addBirthMutations(substitution(flat(1, 0)))
                .build())
            .build();

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[3]).isEqualTo(0x1234L);
        assertThat(row[4]).isEqualTo(0x5678L);
    }

    @Test
    void aFounderHasNoParentGenomeOnItsRows() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(newborn(7, 990, 0, 0).toBuilder()
                .addBirthMutations(substitution(flat(1, 0)))
                .build())
            .build();

        assertThat(plugin.extractRows(tick).get(0)[4]).isNull();
    }

    @Test
    void thePositionIsTheSmallestOfTheEventsCells() {
        // Written in the order the plugin walked them, which is not the order they sort in
        MutationEvent walked = MutationEvent.newBuilder()
            .setPluginClass("org.example.Duplication")
            .setKind("duplication")
            .addCells(flat(15, 4)).addOldValues(0).addNewValues(11)
            .addCells(flat(13, 4)).addOldValues(0).addNewValues(12)
            .addCells(flat(14, 4)).addOldValues(0).addNewValues(13)
            .build();
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(newborn(7, 990, 0, 0).toBuilder().addBirthMutations(walked).build())
            .build();

        assertThat(plugin.extractRows(tick).get(0)[9]).isEqualTo("[13,4]");
    }

    @Test
    void thePositionsAreComparedComponentByComponent() {
        // The second component decides only where the first is equal
        MutationEvent event = MutationEvent.newBuilder()
            .setPluginClass("org.example.Duplication")
            .setKind("duplication")
            .addCells(flat(13, 9)).addOldValues(0).addNewValues(11)
            .addCells(flat(12, 20)).addOldValues(0).addNewValues(12)
            .addCells(flat(12, 8)).addOldValues(0).addNewValues(13)
            .build();
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(newborn(7, 990, 0, 0).toBuilder().addBirthMutations(event).build())
            .build();

        assertThat(plugin.extractRows(tick).get(0)[9]).isEqualTo("[12,8]");
    }

    @Test
    void aPositionIsAnOffsetFromTheOrganismsOwnOrigin() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(newborn(7, 990, 10, 20).toBuilder()
                .addBirthMutations(substitution(flat(23, 24)))
                .build())
            .build();

        assertThat(plugin.extractRows(tick).get(0)[9]).isEqualTo("[13,4]");
    }

    @Test
    void aPositionBehindTheOriginTakesTheShortWayAroundTheWorld() {
        // Three cells before the origin in x, which the long way round would report as 61
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(newborn(7, 990, 2, 0).toBuilder()
                .addBirthMutations(substitution(flat(63, 0)))
                .build())
            .build();

        assertThat(plugin.extractRows(tick).get(0)[9]).isEqualTo("[-3,0]");
    }

    @Test
    void anEventWithoutCellsKeepsItsRowAndNamesNoPlace() {
        // A label rewrite reports its mask and changes no molecule of its own
        MutationEvent mask = MutationEvent.newBuilder()
            .setPluginClass("org.example.LabelRewrite")
            .setKind("label-rewrite")
            .addParams(0x2AL)
            .addDv(1).addDv(0)
            .build();
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(newborn(7, 990, 0, 0).toBuilder().addBirthMutations(mask).build())
            .build();

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[7]).isEqualTo("label-rewrite");
        assertThat(rows.get(0)[8]).isEqualTo(0);
        assertThat(rows.get(0)[9]).isEqualTo("");
        assertThat(rows.get(0)[11]).isEqualTo("[42]");
    }

    @Test
    void anEventWithoutParametersWritesAnEmptyList() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(newborn(7, 990, 0, 0).toBuilder()
                .addBirthMutations(substitution(flat(1, 0)))
                .build())
            .build();

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[10]).isEqualTo("[1,0]");
        assertThat(row[11]).isEqualTo("[]");
    }

    @Test
    void theEventsOfOneBirthAreNumberedInPluginOrder() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(newborn(7, 990, 0, 0).toBuilder()
                .addBirthMutations(substitution(flat(1, 0)))
                .addBirthMutations(substitution(flat(2, 0)))
                .build())
            .build();

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)[5]).isEqualTo(0);
        assertThat(rows.get(1)[5]).isEqualTo(1);
    }

    @Test
    void aRecordingWithoutEventsProducesNoRow() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(1000)
            .addOrganisms(newborn(7, 990, 0, 0))
            .build();

        assertThat(plugin.extractRows(tick)).isEmpty();
    }

    @Test
    void theTableHasNoChartOfItsOwn() {
        assertThat(plugin.getManifestEntry()).isNull();
        assertThat(plugin.getManifestEntries()).isEmpty();
    }

    @Test
    void samplingIntervalCannotBeConfigured() {
        // The value follows from what the metric is, so a configuration file stating it would be
        // a second place to hold it - and the place that wins when the two disagree
        assertThatThrownBy(() -> new MutationSummaryPlugin().configure(ConfigFactory.parseMap(
                Map.of("metricId", "m", "samplingInterval", 10))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("samplingInterval")
            .hasMessageContaining("loses them for good");
    }

    @Test
    void lodLevelsCannotBeConfigured() {
        assertThatThrownBy(() -> new MutationSummaryPlugin().configure(ConfigFactory.parseMap(
                Map.of("metricId", "m", "lodLevels", 5))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lodLevels");
    }

    @Test
    void everyRecordedTickIsRead() {
        assertThat(plugin.getSamplingInterval()).isEqualTo(1);
        assertThat(plugin.getLodLevels()).isEqualTo(1);
    }

    @Test
    void memoryIsBoundedByTheOrganismLimit() {
        SimulationParameters params = new SimulationParameters(
            new int[]{100, 100}, 10_000L, 10_000L, 1000, 1, 10, 50, 50, 0.1);

        List<MemoryEstimate> estimates = plugin.estimateWorstCaseMemory(params);

        assertThat(estimates).hasSize(1);
        assertThat(estimates.get(0).estimatedBytes()).isEqualTo(1000L * 2 * 200);
    }

    /**
     * The row-major flat index of a coordinate in this world: the first component runs over whole
     * rows of the second.
     */
    private static int flat(int first, int second) {
        return first * HEIGHT + second;
    }

    private static MutationEvent substitution(int cell) {
        return MutationEvent.newBuilder()
            .setPluginClass("org.example.Substitution")
            .setKind("substitution")
            .addCells(cell).addOldValues(100).addNewValues(200)
            .addDv(1).addDv(0)
            .build();
    }

    private static OrganismState newborn(int id, long birthTick, int originX, int originY) {
        return OrganismState.newBuilder()
            .setOrganismId(id)
            .setBirthTick(birthTick)
            .setInitialPosition(Vector.newBuilder().addComponents(originX).addComponents(originY))
            .build();
    }

    private IAnalyticsContext context(boolean toroidal) {
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setResolvedConfigJson(
                TestMetadataHelper.createResolvedConfigJson(WIDTH, HEIGHT, toroidal, 10))
            .build();
        return new IAnalyticsContext() {
            @Override public SimulationMetadata getMetadata() { return metadata; }
            @Override public String getRunId() { return "test-run"; }
            @Override public PublishedOutputStream openArtifactStream(String m, String l, String f)
                    throws IOException {
                throw new UnsupportedOperationException();
            }
            @Override public Path getTempDirectory() { throw new UnsupportedOperationException(); }
        };
    }
}
