package org.evochora.cli.commands.node;

import com.typesafe.config.Config;
import org.evochora.node.Node;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeRunCommand.class);

    @ParentCommand
    private NodeCommand parent;

    @Override
    public Integer call() throws Exception {
        final Config config = parent.getParent().getConfig();

        // Show welcome message only for node run
        parent.getParent().showWelcomeMessage();

        final Node node = new Node(config);
        node.start();

        // Keep the main thread alive to prevent the application from exiting.
        // The shutdown hook in the Node class will handle termination.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.info("Node stopped gracefully.");
        }

        return 0;
    }
}