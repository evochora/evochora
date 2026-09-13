package org.evochora.cli.commands.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.evochora.cli.CommandLineInterface;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import picocli.CommandLine;

/**
 * Tests for {@code node fork}: the configuration layer it places above the configuration file,
 * what that layer reaches once the whole configuration is resolved, and the command lines it
 * refuses.
 */
@Tag("unit")
class NodeForkCommandTest {

    private static final String PARENT_RUN = "20260101-12000000-a-run";
    private static final String ENGINE_OPTIONS = "pipeline.services.simulation-engine.options";

    @Test
    void overridesNameTheParentRunToTheEngineOnly() {
        NodeForkCommand command = CommandLine.populateCommand(
            new NodeForkCommand(), "--from", "1000", "--to", "2000");

        Config overrides = command.overrides(PARENT_RUN);

        assertThat(overrides.getBoolean("pipeline.autoStart")).isTrue();
        assertThat(overrides.getBoolean(ENGINE_OPTIONS + ".resume.enabled")).isTrue();
        assertThat(overrides.getString(ENGINE_OPTIONS + ".resume.runId")).isEqualTo(PARENT_RUN);
        assertThat(overrides.getLong(ENGINE_OPTIONS + ".resume.fork.fromTick")).isEqualTo(1000L);
        assertThat(overrides.getLong(ENGINE_OPTIONS + ".resume.fork.toTick")).isEqualTo(2000L);
        assertThat(overrides.hasPath("pipeline.runId")).isFalse();
    }

    @Test
    void indexersStayUnpinnedSoTheyFindTheNewRun() {
        NodeForkCommand command = CommandLine.populateCommand(
            new NodeForkCommand(), "--from", "0", "--to", "10");

        Config config = resolve(command.overrides(PARENT_RUN));

        assertThat(config.getString(ENGINE_OPTIONS + ".resume.runId")).isEqualTo(PARENT_RUN);
        assertThat(config.hasPath("pipeline.services.environment-indexer-1.options.runId")).isFalse();
    }

    @Test
    void profileReachesTheRecordingIntervals() {
        NodeForkCommand command = CommandLine.populateCommand(
            new NodeForkCommand(), "--from", "0", "--to", "10", "--profile", "sampled");

        Config config = resolve(command.overrides(PARENT_RUN));

        int profileSamplingInterval = config.getInt("profiles.sampled.samplingInterval");
        assertThat(config.getInt("pipeline.tuning.samplingInterval")).isEqualTo(profileSamplingInterval);
        assertThat(config.getInt(ENGINE_OPTIONS + ".samplingInterval")).isEqualTo(profileSamplingInterval);
    }

    @Test
    void samplingWinsOverTheProfileItSitsOn() {
        NodeForkCommand command = CommandLine.populateCommand(
            new NodeForkCommand(), "--from", "0", "--to", "10", "--profile", "sparse", "--sampling", "3");

        Config config = resolve(command.overrides(PARENT_RUN));

        assertThat(config.getInt("pipeline.tuning.samplingInterval")).isEqualTo(3);
        assertThat(config.getInt(ENGINE_OPTIONS + ".samplingInterval")).isEqualTo(3);
        assertThat(config.getInt("pipeline.tuning.snapshotInterval"))
            .isEqualTo(config.getInt("profiles.sparse.snapshotInterval"));
    }

    @Test
    void samplingAloneWinsOverTheConfiguredProfile() {
        NodeForkCommand command = CommandLine.populateCommand(
            new NodeForkCommand(), "--from", "0", "--to", "10", "--sampling", "4");

        Config config = resolve(command.overrides(PARENT_RUN));

        assertThat(config.getInt(ENGINE_OPTIONS + ".samplingInterval")).isEqualTo(4);
    }

    @Test
    void aWindowThatEndsBeforeItBeginsIsRefused() {
        NodeForkCommand command = CommandLine.populateCommand(
            new NodeForkCommand(), "--from", "2000", "--to", "1000");

        assertThat(command.rangeError()).contains("--to").contains("1000").contains("2000");
    }

    @Test
    void aNegativeFirstTickIsRefused() {
        NodeForkCommand command = CommandLine.populateCommand(
            new NodeForkCommand(), "--from=-1", "--to", "10");

        assertThat(command.rangeError()).contains("--from").contains("-1");
    }

    @Test
    void aWindowOfOneTickIsAccepted() {
        NodeForkCommand command = CommandLine.populateCommand(
            new NodeForkCommand(), "--from", "7", "--to", "7");

        assertThat(command.rangeError()).isNull();
    }

    @Test
    void anUnknownProfileIsRefusedWithTheNamesThatExist() {
        NodeForkCommand command = CommandLine.populateCommand(
            new NodeForkCommand(), "--from", "0", "--to", "10", "--profile", "dense");
        Config config = ConfigFactory.parseString("profiles { detailed {}, sampled {}, sparse {} }");

        assertThat(command.profileError(config))
            .contains("dense")
            .contains("detailed")
            .contains("sampled")
            .contains("sparse");
    }

    @Test
    void aKnownProfileIsAccepted() {
        NodeForkCommand command = CommandLine.populateCommand(
            new NodeForkCommand(), "--from", "0", "--to", "10", "--profile", "sampled");
        Config config = ConfigFactory.parseString("profiles { detailed {}, sampled {} }");

        assertThat(command.profileError(config)).isNull();
    }

    @Test
    void theCommandLineRefusesAnInvertedWindowBeforeItTouchesTheConfiguration() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();
        StringWriter err = new StringWriter();
        cmdLine.setErr(new PrintWriter(err));

        int exitCode = cmdLine.execute("node", "fork", "--run", PARENT_RUN, "--from", "2000", "--to", "1000");

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString()).contains("--to").contains("--from");
    }

    @Test
    void theCommandLineRequiresBothEndsOfTheWindow() {
        CommandLine cmdLine = CommandLineInterface.createCommandLine();
        StringWriter err = new StringWriter();
        cmdLine.setErr(new PrintWriter(err));

        int exitCode = cmdLine.execute("node", "fork", "--from", "10");

        assertThat(exitCode).isNotEqualTo(0);
        assertThat(err.toString()).contains("--to");
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
