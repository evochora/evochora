package org.evochora.cli.commands.node;

import com.typesafe.config.Config;
import org.evochora.node.Node;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Command(
    name = "run",
    description = "Starts the Evochora Node."
)
public class NodeRunCommand implements Callable<Integer> {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeRunCommand.class);

    @ParentCommand
    private NodeCommand parent;

    @Option(names = {"-d", "--detach"}, description = "Run the node in the background (detached).")
    private boolean detach;

    private static final String PID_FILE_NAME = ".evochora.pid";

    @Override
    public Integer call() throws Exception {
        final Config config = parent.getParent().getConfig();

        if (detach) {
            LOGGER.info("Starting node in detached mode...");
            createPidFile();
        } else {
            LOGGER.info("Starting node in foreground...");
        }

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

    private void createPidFile() throws IOException {
        final String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        final File pidFile = new File(PID_FILE_NAME);

        try (final PrintWriter writer = new PrintWriter(pidFile)) {
            writer.println(pid);
        }

        pidFile.deleteOnExit();
        LOGGER.info("PID file created at {}", pidFile.getAbsolutePath());
    }
}