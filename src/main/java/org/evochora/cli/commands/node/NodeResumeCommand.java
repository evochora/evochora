package org.evochora.cli.commands.node;

import java.util.concurrent.Callable;

import org.evochora.cli.CommandLineInterface;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigUtil;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * The {@code node resume} subcommand: takes a run up again where it was interrupted and continues
 * it under its own ID, with the recording intervals it was written with.
 * <p>
 * The command names the run to the whole node: the simulation engine restores the run's latest
 * checkpoint, and the indexers work on that same run instead of waiting for a new one to appear.
 * Without {@code --run} the newest run in storage is taken.
 */
@Command(
    name = "resume",
    description = "Continues a simulation run from its latest checkpoint"
)
public class NodeResumeCommand implements Callable<Integer> {

    @Option(
        names = {"-r", "--run"},
        description = "Run ID to continue (default: the newest run in storage)"
    )
    private String runId;

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
        final CommandLineInterface cli = parent.getParent();

        final String targetRunId;
        try {
            targetRunId = runId != null
                ? RunLookup.existing(cli.getConfig(), storageName, runId)
                : RunLookup.newest(cli.getConfig(), storageName);
        } catch (Exception e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        }

        final Config config = cli.getConfig(overrides(targetRunId));
        NodeLauncher.start(cli, config);
        return NodeLauncher.awaitShutdown();
    }

    /**
     * Builds the configuration layer that turns a node start into the continuation of a run.
     * <p>
     * {@code pipeline.runId} names the run for the whole node — the engine's
     * {@code resume.runId} is a substitution of it, and every indexer inherits it as the run to
     * index. {@code pipeline.autoStart} makes the node's service manager start the services rather
     * than wait for the pipeline API, and {@code resume.enabled} tells the engine to restore a
     * checkpoint instead of seeding a new world.
     *
     * @param targetRunId the run to continue
     * @return an unresolved configuration to be placed above every other source
     */
    Config overrides(final String targetRunId) {
        return ConfigFactory.parseString("""
            pipeline.runId = %s
            pipeline.autoStart = true
            pipeline.services."simulation-engine".options.resume.enabled = true
            pipeline.services."simulation-engine".resources.resumeStorage = %s
            """.formatted(ConfigUtil.quoteString(targetRunId), ConfigUtil.quoteString("storage-read:" + storageName)));
    }
}
