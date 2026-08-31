package org.evochora.datapipeline.services.indexers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void anIntervalOfZeroIsRefusedWhereItIsStated() {
        // Left to the divisibility test it would surface as a division by zero in the middle of
        // parsing a chunk, where nobody looks for a configuration mistake
        assertThatThrownBy(() -> new AnalyticsTickRelevance(List.of(0L), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("organisms")
            .hasMessageContaining("at least 1");
    }

    @Test
    void aNegativeIntervalIsRefusedToo() {
        // It would pass the divisibility test with a meaning nobody intended
        assertThatThrownBy(() -> new AnalyticsTickRelevance(List.of(), List.of(-100L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("the environment");
    }

    @Test
    void withoutAnEnvironmentPluginNoCellsAreRead() {
        AnalyticsTickRelevance relevance = new AnalyticsTickRelevance(List.of(1000L), List.of());

        assertThat(relevance.readsCellsAt(0)).isFalse();
        assertThat(relevance.readsCellsAt(1000)).isFalse();
    }
}
