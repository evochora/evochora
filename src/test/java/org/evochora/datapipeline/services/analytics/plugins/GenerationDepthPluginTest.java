package org.evochora.datapipeline.services.analytics.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for GenerationDepthPlugin.
 * <p>
 * The depth of an organism is read from the organism, which has carried it since birth. The tests
 * pin that each row depends on its tick alone - the property that makes the metric survive an
 * indexer restart and several indexers sharing the work.
 */
@Tag("unit")
class GenerationDepthPluginTest {

    private GenerationDepthPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new GenerationDepthPlugin();
        Config config = ConfigFactory.parseMap(Map.of("metricId", "depth"));
        plugin.configure(config);
        plugin.initialize(null);
    }

    @Test
    void reportsMaximumAndAverageOfTheLivingOrganisms() {
        TickData tick = createTick(100, organism(1, 0), organism(2, 1), organism(3, 2));

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[0]).isEqualTo(100L);
        assertThat(row[1]).isEqualTo(2);              // max_depth
        assertThat(row[2]).isEqualTo(1.0);            // avg_depth: (0 + 1 + 2) / 3
    }

    @Test
    void deadOrganismsDoNotCount() {
        TickData tick = createTick(100, organism(1, 0), dead(2, 9));

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[1]).isEqualTo(0);
        assertThat(row[2]).isEqualTo(0.0);
    }

    @Test
    void anEmptyPopulationReportsZero() {
        Object[] row = plugin.extractRows(createTick(100)).get(0);

        assertThat(row[1]).isEqualTo(0);
        assertThat(row[2]).isEqualTo(0.0);
    }

    @Test
    void aTickIsReadWithoutHavingSeenTheOnesBeforeIt() {
        // The point of the metric: a deep organism reports its depth even when this plugin never
        // saw its ancestors - after a restart, or because another instance processed them
        TickData tick = createTick(5_000_000, organism(42, 137));

        Object[] row = plugin.extractRows(tick).get(0);

        assertThat(row[1]).isEqualTo(137);
        assertThat(row[2]).isEqualTo(137.0);
    }

    @Test
    void theSameTickYieldsTheSameRowRegardlessOfWhatCameBefore() {
        TickData earlier = createTick(100, organism(1, 0), organism(2, 1));
        TickData target = createTick(200, organism(3, 7), organism(4, 9));

        Object[] withHistory = plugin.extractRows(target).get(0);
        plugin.extractRows(earlier);
        Object[] afterOtherTicks = plugin.extractRows(target).get(0);

        assertThat(afterOtherTicks).isEqualTo(withHistory);
    }

    private TickData createTick(long tickNumber, OrganismState... organisms) {
        TickData.Builder builder = TickData.newBuilder().setTickNumber(tickNumber);
        for (OrganismState organism : organisms) {
            builder.addOrganisms(organism);
        }
        return builder.build();
    }

    private OrganismState organism(int id, int generation) {
        return OrganismState.newBuilder()
            .setOrganismId(id)
            .setGeneration(generation)
            .build();
    }

    private OrganismState dead(int id, int generation) {
        return OrganismState.newBuilder()
            .setOrganismId(id)
            .setGeneration(generation)
            .setIsDead(true)
            .build();
    }

    /** Kept so the plugin's row shape stays pinned. */
    @Test
    void everyRowCarriesTickMaximumAndAverage() {
        List<Object[]> rows = plugin.extractRows(createTick(1, organism(1, 3)));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).hasSize(3);
    }
}
