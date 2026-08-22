package org.evochora.datapipeline.resources.broker;

import static org.assertj.core.api.Assertions.assertThat;

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
}
