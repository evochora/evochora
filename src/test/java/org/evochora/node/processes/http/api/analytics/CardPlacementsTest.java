package org.evochora.node.processes.http.api.analytics;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a card stands comes from the node's plugin list, not from the run: the group and the
 * width of the plugin's entry, and the order of the list.
 */
@Tag("unit")
class CardPlacementsTest {

    private static final Config PLUGINS = ConfigFactory.parseString("""
        plugins = [
          { className = "A", options { metricId = "vital_stats", group = "Population" } },
          { className = "B", options { metricId = "genome", group = "Evolution", fullWidth = true } },
          { className = "C", options { metricId = "population", group = "Population" } },
          { className = "D", options { metricId = "genome_lineage" } },
          { className = "E", options { } }
        ]
        """);

    private static ManifestEntry entry(String id, String storageMetricId) {
        ManifestEntry entry = new ManifestEntry();
        entry.id = id;
        entry.storageMetricId = storageMetricId;
        return entry;
    }

    private static CardPlacements placements() {
        return new CardPlacements(PLUGINS.getConfigList("plugins"));
    }

    @Test
    void cardsStandInTheOrderOfThePluginList() {
        List<ManifestEntry> placed = placements().apply(new ArrayList<>(List.of(
            entry("population", null), entry("genome_clades", "genome"), entry("vital_stats", null))));

        assertThat(placed).extracting(entry -> entry.id)
            .containsExactly("vital_stats", "genome_clades", "population");
        assertThat(placed).extracting(entry -> entry.order).containsExactly(0, 1, 2);
    }

    @Test
    void aCardTakesGroupAndWidthFromItsPlugin() {
        ManifestEntry placed = placements().apply(List.of(entry("genome_clades", "genome"))).get(0);

        assertThat(placed.group).isEqualTo("Evolution");
        assertThat(placed.fullWidth).isTrue();
    }

    @Test
    void theEntriesOfOnePluginShareItsPlacementAndKeepTheirOrder() {
        List<ManifestEntry> placed = placements().apply(new ArrayList<>(List.of(
            entry("genome_diversity", "genome"), entry("vital_stats", null), entry("genome_clades", "genome"))));

        assertThat(placed).extracting(entry -> entry.id)
            .containsExactly("vital_stats", "genome_diversity", "genome_clades");
        assertThat(placed).extracting(entry -> entry.group)
            .containsExactly("Population", "Evolution", "Evolution");
    }

    @Test
    void aCardOfNoConfiguredPluginComesLastWithoutAGroup() {
        List<ManifestEntry> placed = placements().apply(new ArrayList<>(List.of(
            entry("stranger", null), entry("vital_stats", null), entry("another", null))));

        assertThat(placed).extracting(entry -> entry.id)
            .containsExactly("vital_stats", "stranger", "another");
        assertThat(placed.get(1).group).isNull();
        assertThat(placed.get(1).fullWidth).isNull();
    }

    @Test
    void aPluginWithoutAGroupPlacesItsCardInNoGroupButInItsOrder() {
        List<ManifestEntry> placed = placements().apply(new ArrayList<>(List.of(
            entry("stranger", null), entry("genome_lineage", null))));

        assertThat(placed).extracting(entry -> entry.id).containsExactly("genome_lineage", "stranger");
        assertThat(placed.get(0).group).isNull();
        assertThat(placed).extracting(entry -> entry.order).containsExactly(0, 1);
    }

    @Test
    @ExpectLog(level = LogLevel.WARN,
        messagePattern = "Analytics plugins 0 \\(A\\) and 1 \\(B\\) share the metric id 'vital_stats'; the later one places its cards")
    void ofTwoPluginsSharingAMetricIdTheLaterPlacesTheCards() {
        Config twice = ConfigFactory.parseString("""
            plugins = [
              { className = "A", options { metricId = "vital_stats", group = "Population" } },
              { className = "B", options { metricId = "vital_stats", group = "Ecology" } }
            ]
            """);
        ManifestEntry placed = new CardPlacements(twice.getConfigList("plugins"))
            .apply(List.of(entry("vital_stats", null))).get(0);

        assertThat(placed.group).isEqualTo("Ecology");
    }

    @Test
    void withoutAPluginListEveryCardKeepsTheOrderItArrivedIn() {
        List<ManifestEntry> placed = CardPlacements.none().apply(new ArrayList<>(List.of(
            entry("b", null), entry("a", null))));

        assertThat(placed).extracting(entry -> entry.id).containsExactly("b", "a");
        assertThat(placed).allSatisfy(entry -> assertThat(entry.group).isNull());
    }
}
