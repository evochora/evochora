package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for DeathLifetimesPlugin.
 * <p>
 * The point of the metric is to make an episode of same-aged deaths visible, so the tests pin what
 * a lifetime is measured from and what happens when there is nothing to report.
 */
@Tag("unit")
class DeathLifetimesPluginTest {

    private DeathLifetimesPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new DeathLifetimesPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "death_lifetimes")));
        plugin.initialize(null);
    }

    @Test
    void schemaCarriesCountAndFivePercentiles() {
        ParquetSchema schema = plugin.getSchema();

        assertThat(schema.getColumnCount()).isEqualTo(8);
        List<ParquetSchema.Column> columns = schema.getColumns();
        assertThat(columns.get(0).name()).isEqualTo("tick");
        assertThat(columns.get(0).type()).isEqualTo(ColumnType.BIGINT);
        assertThat(columns.get(1).name()).isEqualTo("death_count");
        assertThat(columns.get(1).type()).isEqualTo(ColumnType.INTEGER);
        assertThat(columns.get(2).name()).isEqualTo("deaths_total");
        assertThat(columns.get(2).type()).isEqualTo(ColumnType.BIGINT);
        assertThat(columns.get(3).name()).isEqualTo("death_lifetime_p10");
        assertThat(columns.get(4).name()).isEqualTo("death_lifetime_p25");
        assertThat(columns.get(5).name()).isEqualTo("death_lifetime_p50");
        assertThat(columns.get(6).name()).isEqualTo("death_lifetime_p75");
        assertThat(columns.get(7).name()).isEqualTo("death_lifetime_p90");
    }

    @Test
    void lifetimeIsMeasuredFromDeathTickNotFromTheRecording() {
        // The recording is 9000 ticks later than the death: taking the recording's tick would
        // report a lifetime of 9248 instead of 248
        TickData tick = TickData.newBuilder()
            .setTickNumber(10_000)
            .addOrganisms(dead(1, 752, 1000))
            .build();

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[0]).isEqualTo(10_000L);
        assertThat(row[1]).isEqualTo(1);
        assertThat(row[5]).isEqualTo(248L);
    }

    @Test
    void manyDeathsOfTheSameAgeCollapseThePercentiles() {
        // The signature of an organism forking children that are not viable
        TickData.Builder builder = TickData.newBuilder().setTickNumber(500);
        for (int i = 0; i < 40; i++) {
            builder.addOrganisms(dead(i, 100L * i, 100L * i + 248));
        }

        Object[] row = plugin.extractRows(builder.build()).get(0);

        assertThat(row[1]).isEqualTo(40);
        assertThat(row[3]).isEqualTo(248L);
        assertThat(row[4]).isEqualTo(248L);
        assertThat(row[5]).isEqualTo(248L);
        assertThat(row[6]).isEqualTo(248L);
        assertThat(row[7]).isEqualTo(248L);
    }

    @Test
    void spreadLifetimesSpreadThePercentiles() {
        // Lifetimes 10, 20, ..., 100
        TickData.Builder builder = TickData.newBuilder().setTickNumber(500);
        for (int i = 1; i <= 10; i++) {
            builder.addOrganisms(dead(i, 0, 10L * i));
        }

        Object[] row = plugin.extractRows(builder.build()).get(0);

        assertThat(row[1]).isEqualTo(10);
        assertThat(row[3]).isEqualTo(10L);    // nearest rank: ceil(10 * 0.1) = 1st value
        assertThat(row[4]).isEqualTo(30L);    // ceil(10 * 0.25) = 3rd value
        assertThat(row[5]).isEqualTo(50L);    // ceil(10 * 0.5) = 5th value
        assertThat(row[6]).isEqualTo(80L);    // ceil(10 * 0.75) = 8th value
        assertThat(row[7]).isEqualTo(90L);    // ceil(10 * 0.9) = 9th value
    }

    @Test
    void aSingleDeathIsReportedByEveryPercentile() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(500)
            .addOrganisms(dead(1, 100, 400))
            .build();

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[1]).isEqualTo(1);
        assertThat(row[3]).isEqualTo(300L);
        assertThat(row[5]).isEqualTo(300L);
        assertThat(row[7]).isEqualTo(300L);
    }

    @Test
    void theRunningTotalIsEveryOrganismEverCreatedMinusTheLiving() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(500)
            .setTotalOrganismsCreated(100)
            .addOrganisms(alive(1, 100))
            .addOrganisms(alive(2, 200))
            .addOrganisms(dead(3, 100, 400))
            .build();

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[1]).as("sample behind the percentiles").isEqualTo(1);
        assertThat(row[2]).as("100 created, 2 still alive").isEqualTo(98L);
    }

    @Test
    void livingOrganismsAreNotCounted() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(500)
            .addOrganisms(alive(1, 100))
            .addOrganisms(dead(2, 100, 400))
            .addOrganisms(alive(3, 200))
            .build();

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[1]).isEqualTo(1);
        assertThat(row[5]).isEqualTo(300L);
    }

    @Test
    void aTickWithoutDeathsProducesNoRow() {
        // A zero would read as a lifetime rather than as an absence of deaths
        TickData tick = TickData.newBuilder()
            .setTickNumber(500)
            .addOrganisms(alive(1, 100))
            .build();

        assertThat(plugin.extractRows(tick)).isEmpty();
    }

    @Test
    void aDeathWithoutATimeOfDeathIsRejected() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(500)
            .addOrganisms(OrganismState.newBuilder().setOrganismId(7).setBirthTick(100).setIsDead(true).build())
            .build();

        assertThatThrownBy(() -> plugin.extractRows(tick))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("without a time of death");
    }

    @Test
    void moreDeathsThanTheInitialBufferAreCollected() {
        TickData.Builder builder = TickData.newBuilder().setTickNumber(500);
        for (int i = 0; i < 500; i++) {
            builder.addOrganisms(dead(i, 0, 10));
        }

        Object[] row = plugin.extractRows(builder.build()).get(0);

        assertThat(row[1]).isEqualTo(500);
        assertThat(row[5]).isEqualTo(10L);
    }

    @Test
    void manifestPlotsThePercentilesAgainstTheDeathCount() {
        ManifestEntry entry = plugin.getManifestEntry();

        assertThat(entry.id).isEqualTo("death_lifetimes");
        // A band between p10 and p90 with the median as a line: a futile-forking episode is the
        // band collapsing onto its own line, which no line chart of three curves shows as clearly
        assertThat(entry.visualization.type).isEqualTo("band-chart");

        @SuppressWarnings("unchecked")
        List<String> yAxis = (List<String>) entry.visualization.config.get("y");
        assertThat(yAxis).containsExactly(
            "death_lifetime_p10", "death_lifetime_p25", "death_lifetime_p50",
            "death_lifetime_p75", "death_lifetime_p90");

        @SuppressWarnings("unchecked")
        List<String> y2Axis = (List<String>) entry.visualization.config.get("y2");
        assertThat(y2Axis).containsExactly("deaths", "death_count");
    }

    @Test
    void theNumberOfDeathsSurvivesACoarseLevelOfDetail() {
        // A coarse level keeps every tenth recording, so a row stands for ten of them. Counting
        // the deaths of its own tick would drop nine tenths; the difference of a running total
        // spans the whole gap.
        ManifestEntry entry = plugin.getManifestEntry();

        assertThat(entry.generatedQuery)
            .contains("deaths_total - LAG(deaths_total)")
            .contains("AS deaths");
        assertThat(entry.outputColumns).contains("deaths", "death_count");
    }

    private OrganismState dead(int id, long birthTick, long deathTick) {
        return OrganismState.newBuilder()
            .setOrganismId(id)
            .setBirthTick(birthTick)
            .setDeathTick(deathTick)
            .setIsDead(true)
            .build();
    }

    private OrganismState alive(int id, long birthTick) {
        return OrganismState.newBuilder()
            .setOrganismId(id)
            .setBirthTick(birthTick)
            .build();
    }
}
