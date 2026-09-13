package org.evochora.cli.commands.node;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import picocli.CommandLine;

/**
 * Tests for {@code node resume}: the configuration layer it places above the configuration file,
 * and what that layer reaches once the whole configuration is resolved.
 */
@Tag("unit")
class NodeResumeCommandTest {

    @Test
    void overridesNameTheRunAndTurnResumeOn() {
        NodeResumeCommand command = CommandLine.populateCommand(new NodeResumeCommand());

        Config overrides = command.overrides("20260101-12000000-a-run");

        assertThat(overrides.getString("pipeline.runId")).isEqualTo("20260101-12000000-a-run");
        assertThat(overrides.getBoolean("pipeline.autoStart")).isTrue();
        assertThat(overrides.getBoolean("pipeline.services.simulation-engine.options.resume.enabled")).isTrue();
    }

    @Test
    void theRunReachesTheEngineAndTheIndexers() {
        NodeResumeCommand command = CommandLine.populateCommand(new NodeResumeCommand(), "--run", "20260101-12000000-a-run");

        Config config = resolve(command.overrides("20260101-12000000-a-run"));

        assertThat(config.getString("pipeline.services.simulation-engine.options.resume.runId"))
            .isEqualTo("20260101-12000000-a-run");
        assertThat(config.getString("pipeline.services.environment-indexer-1.options.runId"))
            .isEqualTo("20260101-12000000-a-run");
        assertThat(config.getBoolean("pipeline.autoStart")).isTrue();
    }

    @Test
    void theCommandOffersRunAndStorage() {
        String usage = new CommandLine(new NodeResumeCommand()).getUsageMessage(CommandLine.Help.Ansi.OFF);

        assertThat(usage).contains("--run").contains("--storage").contains("tick-storage");
    }

    /**
     * Resolves the given override layer against the classpath defaults, the way
     * {@code ConfigLoader} does when no user configuration file takes part.
     *
     * @param overrides the unresolved override layer a command produced
     * @return the resolved configuration
     */
    private Config resolve(final Config overrides) {
        return overrides
            .withFallback(ConfigFactory.systemProperties())
            .withFallback(ConfigFactory.defaultReferenceUnresolved())
            .resolve();
    }
}
