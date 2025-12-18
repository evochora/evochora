package org.evochora.cli.commands.node;

import com.typesafe.config.Config;
import org.evochora.node.Node;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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