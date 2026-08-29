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
    void schemaCarriesCountAndThreePercentiles() {
        ParquetSchema schema = plugin.getSchema();

        assertThat(schema.getColumnCount()).isEqualTo(5);
        List<ParquetSchema.Column> columns = schema.getColumns();
        assertThat(columns.get(0).name()).isEqualTo("tick");
        assertThat(columns.get(0).type()).isEqualTo(ColumnType.BIGINT);
        assertThat(columns.get(1).name()).isEqualTo("death_count");
        assertThat(columns.get(1).type()).isEqualTo(ColumnType.INTEGER);
        assertThat(columns.get(2).name()).isEqualTo("death_lifetime_p10");
        assertThat(columns.get(3).name()).isEqualTo("death_lifetime_p50");
        assertThat(columns.get(4).name()).isEqualTo("death_lifetime_p90");
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
        assertThat(row[3]).isEqualTo(248L);
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
        assertThat(row[2]).isEqualTo(248L);
        assertThat(row[3]).isEqualTo(248L);
        assertThat(row[4]).isEqualTo(248L);
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
        assertThat(row[2]).isEqualTo(10L);    // nearest rank: ceil(10 * 0.1) = 1st value
        assertThat(row[3]).isEqualTo(50L);    // ceil(10 * 0.5) = 5th value
        assertThat(row[4]).isEqualTo(90L);    // ceil(10 * 0.9) = 9th value
    }

    @Test
    void aSingleDeathIsReportedByAllThreePercentiles() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(500)
            .addOrganisms(dead(1, 100, 400))
            .build();

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[1]).isEqualTo(1);
        assertThat(row[2]).isEqualTo(300L);
        assertThat(row[3]).isEqualTo(300L);
        assertThat(row[4]).isEqualTo(300L);
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
        assertThat(row[3]).isEqualTo(300L);
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
        assertThat(row[3]).isEqualTo(10L);
    }

    @Test
    void manifestPlotsThePercentilesAgainstTheDeathCount() {
        ManifestEntry entry = plugin.getManifestEntry();

        assertThat(entry.id).isEqualTo("death_lifetimes");
        assertThat(entry.visualization.type).isEqualTo("line-chart");

        @SuppressWarnings("unchecked")
        List<String> yAxis = (List<String>) entry.visualization.config.get("y");
        assertThat(yAxis).containsExactly(
            "death_lifetime_p10", "death_lifetime_p50", "death_lifetime_p90");

        @SuppressWarnings("unchecked")
        List<String> y2Axis = (List<String>) entry.visualization.config.get("y2");
        assertThat(y2Axis).containsExactly("death_count");
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
