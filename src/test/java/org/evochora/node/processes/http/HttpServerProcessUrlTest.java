package org.evochora.node.processes.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the URL the HTTP server reports as the entry point of its web interface.
 */
@Tag("unit")
class HttpServerProcessUrlTest {

    @Test
    void wildcardBindAddressIsShownAsLocalhost() {
        assertThat(HttpServerProcess.webInterfaceUrl("0.0.0.0", 8081)).isEqualTo("http://localhost:8081/");
        assertThat(HttpServerProcess.webInterfaceUrl("::", 8081)).isEqualTo("http://localhost:8081/");
    }

    @Test
    void configuredHostIsShownUnchanged() {
        assertThat(HttpServerProcess.webInterfaceUrl("evochora.example.org", 9000))
            .isEqualTo("http://evochora.example.org:9000/");
    }
}
