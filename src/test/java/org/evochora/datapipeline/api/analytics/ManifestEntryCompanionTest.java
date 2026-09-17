package org.evochora.datapipeline.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

/**
 * The manifest travels as JSON from the indexer to the node and on to the browser; a companion
 * has to arrive there saying whether it follows the chart's level of detail.
 */
@Tag("unit")
class ManifestEntryCompanionTest {

    private final Gson gson = new Gson();

    @Test
    void aCompanionKeepsWhetherItFollowsTheLevelThroughJson() {
        ManifestEntry entry = new ManifestEntry();
        entry.companions = List.of(
            new ManifestEntry.Companion("population", "SELECT 1", true),
            new ManifestEntry.Companion("lineage", "SELECT 2"));

        String json = gson.toJson(entry);
        ManifestEntry read = gson.fromJson(json, ManifestEntry.class);

        assertThat(json).contains("\"followsLevel\":true").contains("\"followsLevel\":false");
        assertThat(read.companions).extracting(ManifestEntry.Companion::followsLevel)
            .containsExactly(true, false);
    }

    @Test
    void aCompanionWrittenWithoutTheFlagIsReadWhole() {
        ManifestEntry read = gson.fromJson(
            "{\"companions\":[{\"metricId\":\"lineage\",\"query\":\"SELECT 1\"}]}", ManifestEntry.class);

        assertThat(read.companions.get(0).followsLevel()).isFalse();
    }
}
