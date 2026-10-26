package org.evochora.runtime.model;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The topology words: a configuration, a run's metadata and a command line write them in any
 * case, and a word that only resembles one is no topology.
 */
@Tag("unit")
class TopologyTest {

    @Test
    void theWordsAreReadInAnyCase() {
        assertThat(Topology.parse("torus")).isEqualTo(Topology.TORUS);
        assertThat(Topology.parse("TORUS")).isEqualTo(Topology.TORUS);
        assertThat(Topology.parse("Bound")).isEqualTo(Topology.BOUND);
    }

    @Test
    void aWordThatOnlyResemblesATopologyIsRejected() {
        for (String word : Arrays.asList("toroidal", "BOUNDED", "", null)) {
            assertThatThrownBy(() -> Topology.parse(word))
                .describedAs("%s", word)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("torus or bound");
        }
    }

    @Test
    void ofReturnsTheTopologyThatWrapsAsAsked() {
        for (Topology topology : Topology.values()) {
            assertThat(Topology.of(topology.isToroidal())).isEqualTo(topology);
        }
    }
}
