package org.evochora.node.processes.h2;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * The H2 console and the H2 TCP server tell a client how to connect to the database the pipeline
 * uses. They take that from {@code database.jdbcUrl} and from nothing else: without it an
 * enabled process does not start, and a disabled one does not need it.
 */
@Tag("unit")
class H2ProcessOptionsTest {

    @Test
    void anEnabledTcpServerWithoutTheDatabaseUrlDoesNotStart() {
        Config options = ConfigFactory.parseMap(Map.of("enabled", true));

        assertThatThrownBy(() -> new H2TcpServerProcess("h2-tcp-server", Map.of(), options))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("database.jdbcUrl");
    }

    @Test
    void theTcpServerRejectsAUrlThatIsNoH2Url() {
        Config options = ConfigFactory.parseMap(Map.of("database", Map.of("jdbcUrl", "jdbc:postgresql://host/db")));

        assertThatThrownBy(() -> new H2TcpServerProcess("h2-tcp-server", Map.of(), options))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no H2 URL");
    }

    @Test
    void anEnabledConsoleWithoutTheDatabaseUrlDoesNotStart() {
        Config options = ConfigFactory.empty();

        assertThatThrownBy(() -> new H2ConsoleProcess("h2-console", Map.of(), options))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("database.jdbcUrl");
    }

    @Test
    void aDisabledProcessNeedsNoDatabaseUrl() {
        Config options = ConfigFactory.parseMap(Map.of("enabled", false));

        assertThatCode(() -> new H2TcpServerProcess("h2-tcp-server", Map.of(), options)).doesNotThrowAnyException();
        assertThatCode(() -> new H2ConsoleProcess("h2-console", Map.of(), options)).doesNotThrowAnyException();
    }
}
