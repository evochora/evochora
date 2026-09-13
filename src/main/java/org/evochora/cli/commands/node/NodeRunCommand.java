package org.evochora.cli.commands.node;

import com.typesafe.config.Config;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * The {@code node run} subcommand: starts an Evochora node from the configuration of the
 * invocation and runs it in the foreground. The command has no options of its own; everything the
 * node needs comes from the configuration file selected by the root command.
 * <p>
 * Starting the node registers a JVM shutdown hook that stops the node's processes, after which the
 * command blocks its own thread for the lifetime of the node. It returns exit code 0 when that
 * thread is interrupted.
 */
@Command(
    name = "run",
    description = "Starts the Evochora Node server in foreground mode"
)
public class NodeRunCommand implements Callable<Integer> {

    @ParentCommand
    private NodeCommand parent;

    @Override
    public Integer call() {
        final Config config = parent.getParent().getConfig();
        NodeLauncher.start(parent.getParent(), config);
        return NodeLauncher.awaitShutdown();
    }
}
