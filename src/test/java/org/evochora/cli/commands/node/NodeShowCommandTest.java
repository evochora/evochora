package org.evochora.cli.commands.node;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import picocli.CommandLine;

/**
 * Tests for {@code node show}: the single value it places above the configuration file, and that
 * the HTTP server it leaves running is still configured.
 */
@Tag("unit")
class NodeShowCommandTest {

    @Test
    void theOnlyOverrideKeepsThePipelineServicesStopped() {
        NodeShowCommand command = CommandLine.populateCommand(new NodeShowCommand());

        Config overrides = command.overrides();

        assertThat(overrides.getBoolean("pipeline.autoStart")).isFalse();
        assertThat(overrides.entrySet()).hasSize(1);
    }

    @Test
    void theHttpServerStaysConfiguredAndKeepsItsAddress() {
        NodeShowCommand command = CommandLine.populateCommand(new NodeShowCommand());

        Config config = command.overrides()
            .withFallback(ConfigFactory.systemProperties())
            .withFallback(ConfigFactory.defaultReferenceUnresolved())
            .resolve();

        assertThat(config.getBoolean("pipeline.autoStart")).isFalse();
        assertThat(config.getString("node.processes.http.options.network.host")).isNotBlank();
        assertThat(config.getInt("node.processes.http.options.network.port")).isPositive();
    }

    @Test
    void theCommandTakesNoOptionsOfItsOwn() {
        CommandLine commandLine = new CommandLine(new NodeShowCommand());

        assertThat(commandLine.getCommandSpec().options()).isEmpty();
    }
}
