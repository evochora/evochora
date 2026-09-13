package org.evochora.cli.commands.node;

import java.util.Set;
import java.util.concurrent.Callable;

import org.evochora.cli.CommandLineInterface;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * The {@code node fork} subcommand: records a window of an existing run again as a new run, so a
 * stretch that was written coarsely can be written densely.
 * <p>
 * The simulation engine restores the parent run's checkpoint that holds the first tick of the
 * window and replays it; the recording intervals come from the current configuration, which
 * {@code --profile} and {@code --sampling} select from the command line. The new run gets an ID of
 * its own, which the indexers find because nothing pins them to the parent.
 */
@Command(
    name = "fork",
    description = "Records a tick window of a run again as a new run"
)
public class NodeForkCommand implements Callable<Integer> {

    @Option(
        names = {"-r", "--run"},
        description = "Run ID to fork from (default: the newest run in storage)"
    )
    private String runId;

    @Option(
        names = "--from",
        required = true,
        paramLabel = "<tick>",
        description = "First tick that must be recorded"
    )
    private long fromTick;

    @Option(
        names = "--to",
        required = true,
        paramLabel = "<tick>",
        description = "Last tick that must be recorded"
    )
    private long toTick;

    @Option(
        names = {"-p", "--profile"},
        paramLabel = "<name>",
        description = "Tuning profile the fork is recorded with (default: the configured one)"
    )
    private String profile;

    @Option(
        names = "--sampling",
        paramLabel = "<n>",
        description = "Ticks between two recorded ticks, on top of the profile"
    )
    private Integer samplingInterval;

    @Option(
        names = {"-s", "--storage"},
        description = "Storage resource the newest run is looked up in (default: tick-storage)"
    )
    private String storageName = "tick-storage";

    @ParentCommand
    private NodeCommand parent;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        final String rangeError = rangeError();
        if (rangeError != null) {
            spec.commandLine().getErr().println(rangeError);
            return 1;
        }

        final CommandLineInterface cli = parent.getParent();
        final Config plainConfig = cli.getConfig();

        final String profileError = profileError(plainConfig);
        if (profileError != null) {
            spec.commandLine().getErr().println(profileError);
            return 1;
        }

        final String parentRunId;
        if (runId != null) {
            parentRunId = runId;
        } else {
            try {
                parentRunId = LatestRun.runId(plainConfig, storageName);
            } catch (Exception e) {
                spec.commandLine().getErr().println(e.getMessage());
                return 1;
            }
        }

        final Config config = cli.getConfig(overrides(parentRunId));
        NodeLauncher.start(cli, config);
        return NodeLauncher.awaitShutdown();
    }

    /**
     * Checks the tick window the command line asks for.
     *
     * @return what is wrong with the window, or {@code null} when it is a window at all
     */
    String rangeError() {
        if (fromTick < 0) {
            return "--from must not be negative, but is " + fromTick + ".";
        }
        if (toTick < fromTick) {
            return "--to must not lie before --from, but is " + toTick
                + " while --from is " + fromTick + ".";
        }
        return null;
    }

    /**
     * Checks the tuning profile the command line asks for against the profiles the configuration
     * defines, so that a mistyped name is reported before a node is built rather than as a failed
     * substitution.
     *
     * @param config the resolved configuration of the invocation
     * @return what is wrong with the profile, or {@code null} when none was asked for or it exists
     */
    String profileError(final Config config) {
        if (profile == null || profileNames(config).contains(profile)) {
            return null;
        }
        return "Unknown tuning profile '" + profile + "'. Configured profiles: "
            + String.join(", ", profileNames(config)) + ".";
    }

    /**
     * Returns the names of the tuning profiles the configuration defines, in the order the
     * configuration lists them.
     *
     * @param config the resolved configuration of the invocation
     * @return the keys of the {@code profiles} block, empty when the block is absent
     */
    private Set<String> profileNames(final Config config) {
        return config.hasPath("profiles") ? config.getObject("profiles").keySet() : Set.of();
    }

    /**
     * Builds the configuration layer that turns a node start into a fork of a run.
     * <p>
     * The parent run is named to the simulation engine alone, as {@code resume.runId}, and not as
     * {@code pipeline.runId}: the fork is written under a new ID, and an indexer pinned to the
     * parent would never see it. {@code resume.fork} carries the window, and
     * {@code pipeline.autoStart} makes the node's service manager start the services.
     * <p>
     * A profile is set as the substitution {@code ${profiles.<name>}}, which resolves against the
     * profiles of the configuration below this layer, and a sampling interval is merged over the
     * object that substitution stands for — both in one document, so that the more specific value
     * wins however the profile is composed.
     *
     * @param parentRunId the run the window is taken from
     * @return an unresolved configuration to be placed above every other source
     */
    Config overrides(final String parentRunId) {
        final StringBuilder document = new StringBuilder()
            .append("pipeline.autoStart = true\n")
            .append("pipeline.services.\"simulation-engine\".options.resume {\n")
            .append("  enabled = true\n")
            .append("  runId = \"").append(parentRunId).append("\"\n")
            .append("  fork {\n")
            .append("    fromTick = ").append(fromTick).append("\n")
            .append("    toTick = ").append(toTick).append("\n")
            .append("  }\n")
            .append("}\n");
        if (profile != null) {
            document.append("pipeline.tuning = ${profiles.\"").append(profile).append("\"}\n");
        }
        if (samplingInterval != null) {
            document.append("pipeline.tuning.samplingInterval = ").append(samplingInterval).append("\n");
        }
        return ConfigFactory.parseString(document.toString());
    }
}
