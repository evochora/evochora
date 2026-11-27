package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.IAnalyticsContext;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.storage.PublishedOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for DeathsPlugin.
 * <p>
 * The table is the death half of a run's life table, so the tests pin that every death gets
 * exactly one row, that the row carries the moment of death and not the recording, and that an
 * organism is never left out silently.
 * <p>
 * Test metadata uses max-entropy=8191 from TestMetadataHelper.
 */
@Tag("unit")
class DeathsPluginTest {

    /** Max entropy from TestMetadataHelper defaults. */
    private static final int MAX_ENTROPY = 8191;

    private DeathsPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new DeathsPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "deaths")));
        plugin.initialize(contextOfARun());
    }

    @Test
    void schemaCarriesTheOrganismItsDeathTickAndTheCause() {
        ParquetSchema schema = plugin.getSchema();

        List<ParquetSchema.Column> columns = schema.getColumns();
        assertThat(columns).extracting(ParquetSchema.Column::name)
            .containsExactly("tick", "organism_id", "death_tick", "cause");
        assertThat(columns).extracting(ParquetSchema.Column::type)
            .containsExactly(ColumnType.BIGINT, ColumnType.INTEGER, ColumnType.BIGINT, ColumnType.INTEGER);
        assertThat(columns.get(3).description())
            .contains("0 (energy)").contains("1 (entropy)");
    }

    @Test
    void everyDeadOrganismOfARecordingGetsOneRow() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(10_000)
            .addOrganisms(alive(1))
            .addOrganisms(dead(2, 9_300, 0, 10))
            .addOrganisms(alive(3))
            .addOrganisms(dead(4, 9_999, 50, MAX_ENTROPY + 1))
            .build();

        List<Object[]> rows = plugin.extractRows(tick);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly(10_000L, 2, 9_300L, DeathCause.ENERGY);
        assertThat(rows.get(1)).containsExactly(10_000L, 4, 9_999L, DeathCause.ENTROPY);
    }

    @Test
    void aRecordingWithoutDeathsProducesNoRow() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(10_000)
            .addOrganisms(alive(1))
            .build();

        assertThat(plugin.extractRows(tick)).isEmpty();
    }

    @Test
    void aDeadOrganismWithoutATimeOfDeathIsRefused() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(10_000)
            .addOrganisms(OrganismState.newBuilder().setOrganismId(7).setIsDead(true).build())
            .build();

        assertThatThrownBy(() -> plugin.extractRows(tick))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("organism 7")
            .hasMessageContaining("without a time of death");
    }

    @Test
    void aDeathThatFitsNoCauseIsRefused() {
        TickData tick = TickData.newBuilder()
            .setTickNumber(10_000)
            .addOrganisms(dead(7, 9_000, 50, MAX_ENTROPY))
            .build();

        assertThatThrownBy(() -> plugin.extractRows(tick))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("neither");
    }

    @Test
    void theSamplingIntervalCannotBeConfigured() {
        DeathsPlugin configured = new DeathsPlugin();

        assertThatThrownBy(() -> configured.configure(ConfigFactory.parseMap(
                Map.of("metricId", "deaths", "samplingInterval", 10))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("samplingInterval");
    }

    @Test
    void theLevelsOfDetailCannotBeConfigured() {
        DeathsPlugin configured = new DeathsPlugin();

        assertThatThrownBy(() -> configured.configure(ConfigFactory.parseMap(
                Map.of("metricId", "deaths", "lodLevels", 3))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lodLevels");
    }

    @Test
    void theCardNamesTheDerivationAndTheTwoTablesItReads() {
        ManifestEntry entry = plugin.getManifestEntry();

        // Everything drawn is derived in the browser, so the card reads none of its own files
        assertThat(entry.dataSources).isNull();
        assertThat(entry.generatedQuery).isNull();
        // What the browser looks up: the derivation by its name, and the two tables by the ids
        // the companions are loaded under
        assertThat(entry.visualization.config)
            .containsEntry("derived", "death-lifetimes")
            .containsEntry("deathsMetric", "deaths")
            .containsEntry("birthsMetric", "births");
        // The columns the derivation joins and measures by, read whole and column by column
        assertThat(entry.companions).hasSize(2);
        assertThat(entry.companions.get(0)).satisfies(deaths -> {
            assertThat(deaths.metricId()).isEqualTo("deaths");
            assertThat(deaths.query()).contains("organism_id").contains("death_tick");
            assertThat(deaths.columnar()).isTrue();
            assertThat(deaths.followsLevel()).isFalse();
        });
        assertThat(entry.companions.get(1)).satisfies(births -> {
            assertThat(births.metricId()).isEqualTo("births");
            assertThat(births.query()).contains("organism_id").contains("birth_tick");
            assertThat(births.columnar()).isTrue();
            assertThat(births.followsLevel()).isFalse();
        });
    }

    @Test
    void theBirthsTableCanBeConfiguredUnderAnotherName() {
        DeathsPlugin renamed = new DeathsPlugin();
        renamed.configure(ConfigFactory.parseMap(Map.of("metricId", "deaths", "birthsMetricId", "life_table")));
        renamed.initialize(contextOfARun());

        ManifestEntry entry = renamed.getManifestEntry();

        assertThat(entry.companions.get(1).metricId()).isEqualTo("life_table");
        assertThat(entry.visualization.config).containsEntry("birthsMetric", "life_table");
    }

    @Test
    void aBirthsTableWithoutANameIsRefused() {
        // The card would look for a table under no name and show nothing
        DeathsPlugin unnamed = new DeathsPlugin();

        assertThatThrownBy(() -> unnamed.configure(ConfigFactory.parseMap(
                Map.of("metricId", "deaths", "birthsMetricId", ""))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("birthsMetricId");
    }

    private static OrganismState alive(int id) {
        return OrganismState.newBuilder().setOrganismId(id).setEnergy(100).build();
    }

    private static OrganismState dead(int id, long deathTick, int energy, int entropy) {
        return OrganismState.newBuilder()
            .setOrganismId(id)
            .setIsDead(true)
            .setDeathTick(deathTick)
            .setEnergy(energy)
            .setEntropyRegister(entropy)
            .build();
    }

    private static IAnalyticsContext contextOfARun() {
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setResolvedConfigJson(TestMetadataHelper.builder().build())
            .build();

        return new IAnalyticsContext() {
            @Override
            public SimulationMetadata getMetadata() {
                return metadata;
            }

            @Override
            public String getRunId() {
                return "test-run";
            }

            @Override
            public PublishedOutputStream openArtifactStream(String metricId, String lodLevel, String filename)
                    throws IOException {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.nio.file.Path getTempDirectory() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
