package org.evochora.datapipeline.resources.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the parts of {@link EmbeddedBrokerRegistry} that need no running broker.
 * <p>
 * Behaviour that requires an actual Artemis instance is covered by the Artemis resource
 * integration tests and by the test of the node process that owns a broker.
 */
@Tag("unit")
class EmbeddedBrokerRegistryTest {

    @Test
    @DisplayName("Should parse InVM server-ID from broker URL")
    void shouldParseInVmServerId() {
        assertThat(EmbeddedBrokerRegistry.parseInVmServerId("vm://0")).isEqualTo(0);
        assertThat(EmbeddedBrokerRegistry.parseInVmServerId("vm://1")).isEqualTo(1);
        assertThat(EmbeddedBrokerRegistry.parseInVmServerId("vm://42")).isEqualTo(42);
        assertThat(EmbeddedBrokerRegistry.parseInVmServerId("tcp://localhost:61616")).isEqualTo(-1);
        assertThat(EmbeddedBrokerRegistry.parseInVmServerId(null)).isEqualTo(-1);
    }

    /**
     * An in-VM URL that carries no usable server-ID is rejected instead of passing for an external
     * broker.
     * <p>
     * {@code -1} means "not an in-VM URL", and a typo used to produce exactly that value. The
     * resource then treated itself as talking to an external broker: no address settings, no byte
     * limit, no purge of messages left over from an earlier run — and nothing above debug level.
     * Artemis does not catch the typo either; its connection factory accepts {@code vm://abc}.
     */
    @Test
    @DisplayName("Should reject an InVM URL without a usable server-ID")
    void shouldRejectMalformedInVmUrl() {
        assertThatThrownBy(() -> EmbeddedBrokerRegistry.parseInVmServerId("vm://abc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("vm://abc");

        assertThatThrownBy(() -> EmbeddedBrokerRegistry.parseInVmServerId("vm://"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A negative server-ID is rejected, because {@code vm://-1} would parse straight onto the value
     * that means "not an in-VM URL" — the very ambiguity this rejection exists to remove.
     */
    @Test
    @DisplayName("Should reject a negative server-ID")
    void shouldRejectNegativeServerId() {
        assertThatThrownBy(() -> EmbeddedBrokerRegistry.parseInVmServerId("vm://-1"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Artemis allows transport parameters after the server-ID, so a URL carrying them is valid and
     * must keep working. Reading the ID by cutting off a fixed number of characters would reject it.
     */
    @Test
    @DisplayName("Should accept an InVM URL with transport parameters")
    void shouldAcceptInVmUrlWithParameters() {
        assertThat(EmbeddedBrokerRegistry.parseInVmServerId("vm://0?connectionsAllowed=2")).isEqualTo(0);
        assertThat(EmbeddedBrokerRegistry.parseInVmServerId("vm://1?connectionsAllowed=10")).isEqualTo(1);
    }
}
