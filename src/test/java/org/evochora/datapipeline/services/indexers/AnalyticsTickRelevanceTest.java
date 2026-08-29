package org.evochora.datapipeline.services.indexers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pins which recorded ticks the analytics plugins of a run look at.
 */
@Tag("unit")
class AnalyticsTickRelevanceTest {

    @Test
    void organismsAreReadWherePluginsWriteRows() {
        // A plugin taking every 10th recording of a run recording every 100 ticks
        AnalyticsTickRelevance relevance = new AnalyticsTickRelevance(List.of(1000L), List.of());

        assertThat(relevance.readsOrganismsAt(0)).isTrue();
        assertThat(relevance.readsOrganismsAt(1000)).isTrue();
        assertThat(relevance.readsOrganismsAt(100)).isFalse();
        assertThat(relevance.readsOrganismsAt(900)).isFalse();
    }

    @Test
    void organismsAreReadWhenAnyPluginReadsThem() {
        AnalyticsTickRelevance relevance = new AnalyticsTickRelevance(List.of(1000L, 300L), List.of());

        assertThat(relevance.readsOrganismsAt(300)).isTrue();
        assertThat(relevance.readsOrganismsAt(1000)).isTrue();
        assertThat(relevance.readsOrganismsAt(400)).isFalse();
    }

    @Test
    void cellsAreReadOnlyWhereAnEnvironmentPluginLooks() {
        AnalyticsTickRelevance relevance = new AnalyticsTickRelevance(List.of(100L), List.of(1000L));

        assertThat(relevance.readsCellsAt(1000)).isTrue();
        assertThat(relevance.readsCellsAt(100)).isFalse();
        assertThat(relevance.readsOrganismsAt(100)).isTrue();
    }

    @Test
    void withoutAnEnvironmentPluginNoCellsAreRead() {
        AnalyticsTickRelevance relevance = new AnalyticsTickRelevance(List.of(1000L), List.of());

        assertThat(relevance.readsCellsAt(0)).isFalse();
        assertThat(relevance.readsCellsAt(1000)).isFalse();
    }
}
