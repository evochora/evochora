package org.evochora.node.processes.http.api.analytics;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the analyzer puts the card of each metric: its group, whether it takes a row of its own,
 * and its place among the cards.
 * <p>
 * These are properties of the view, not of a run's data, so they come from the node's
 * configuration when the manifest is served rather than from the run. A run indexed long ago is
 * laid out like one indexed today, and a regrouping in the configuration shows at once. The
 * source is the plugin list the analytics indexer is configured with: an entry's
 * {@code options.group} and {@code options.fullWidth}, and its position in the list. A manifest
 * entry belongs to the plugin whose {@code metricId} is its {@link ManifestEntry#storageMetricId}
 * or, without one, its {@link ManifestEntry#id}, so the entries of a plugin that writes several
 * share its placement. Among themselves they stand in the order {@code options.cards} names their
 * ids in; those it does not name follow in the order they arrived, which is all of them where the
 * option is absent. Entries of no configured plugin come last, in the order they arrived.
 */
final class CardPlacements {

    private static final Logger log = LoggerFactory.getLogger(CardPlacements.class);

    /**
     * The placement of one plugin's cards, the ids of its cards in the order they stand in, and the
     * plugin's class for naming it in a message.
     */
    private record Placement(String group, Boolean fullWidth, int order, List<String> cards, String className) { }

    /** A manifest entry with its place among the cards of its plugin. */
    private record Placed(ManifestEntry entry, int placeInPlugin) { }

    private final Map<String, Placement> byMetricId = new HashMap<>();
    /** How many plugins were configured; the cards of no plugin come after all of theirs. */
    private final int pluginCount;

    /**
     * Reads the placements from a plugin list as the analytics indexer is configured with.
     *
     * @param plugins Entries with {@code options.metricId}, and possibly {@code options.group},
     *                {@code options.fullWidth} and {@code options.cards}; an entry without a metric
     *                id places nothing, and of two entries sharing a metric id the later places the
     *                cards
     */
    CardPlacements(List<? extends Config> plugins) {
        this.pluginCount = plugins.size();
        for (int index = 0; index < plugins.size(); index++) {
            Config options = plugins.get(index).hasPath("options")
                ? plugins.get(index).getConfig("options") : null;
            if (options == null || !options.hasPath("metricId")) {
                continue;
            }
            String metricId = options.getString("metricId");
            Config plugin = plugins.get(index);
            Placement placement = new Placement(
                options.hasPath("group") ? options.getString("group") : null,
                options.hasPath("fullWidth") ? options.getBoolean("fullWidth") : null,
                index,
                options.hasPath("cards") ? List.copyOf(options.getStringList("cards")) : List.of(),
                plugin.hasPath("className") ? plugin.getString("className") : "?");
            Placement earlier = byMetricId.put(metricId, placement);
            if (earlier != null) {
                log.warn("Analytics plugins {} ({}) and {} ({}) share the metric id '{}'; the later one places its cards",
                    earlier.order(), earlier.className(), index, placement.className(), metricId);
            }
        }
    }

    /**
     * Says whether a plugin of this name is in the list the node is configured with.
     *
     * @param metricId The metric id to look for
     * @return {@code true} if a configured plugin writes under that name
     */
    boolean knowsMetric(String metricId) {
        return byMetricId.containsKey(metricId);
    }

    /** Placements of no plugin at all: every card keeps the order it arrived in. */
    static CardPlacements none() {
        return new CardPlacements(List.of());
    }

    /**
     * Writes the placements into the entries and puts the entries into their order.
     *
     * @param entries Manifest entries as read from the run
     * @return The entries in the order of their plugins, those of one plugin in the order its
     *         {@code cards} name them and the rest of them as they arrived, those of no configured
     *         plugin last; {@code order} counts them from 0 without gaps
     */
    List<ManifestEntry> apply(List<ManifestEntry> entries) {
        List<Placed> ranked = new ArrayList<>(entries.size());
        for (int arrived = 0; arrived < entries.size(); arrived++) {
            ManifestEntry entry = entries.get(arrived);
            Placement placement = byMetricId.get(entry.storageMetricId != null ? entry.storageMetricId : entry.id);
            if (placement != null) {
                entry.group = placement.group();
                entry.fullWidth = placement.fullWidth();
                entry.order = placement.order();
                int named = placement.cards().indexOf(entry.id);
                ranked.add(new Placed(entry, named >= 0 ? named : placement.cards().size()));
            } else {
                entry.group = null;
                entry.fullWidth = null;
                entry.order = pluginCount + arrived;
                ranked.add(new Placed(entry, 0));
            }
        }
        // The sort is stable, so cards that tie in both keep the order they arrived in
        ranked.sort(Comparator.<Placed>comparingInt(card -> card.entry().order)
            .thenComparingInt(Placed::placeInPlugin));
        List<ManifestEntry> placed = new ArrayList<>(ranked.size());
        for (Placed card : ranked) {
            placed.add(card.entry());
        }
        // The place among the cards, without the gaps the plugins that write no card would leave
        for (int place = 0; place < placed.size(); place++) {
            placed.get(place).order = place;
        }
        return placed;
    }
}
