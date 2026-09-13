package org.evochora.cli.commands.node;

import org.evochora.cli.CommandLineInterface;
import org.evochora.node.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * The sequence every {@code node} subcommand runs: the welcome message, a node built from the
 * configuration of the invocation, its start, and the wait for its end.
 * <p>
 * The subcommands differ only in the configuration they hand over and in what they report once the
 * node is up, so the start itself lives here rather than in each of them.
 */
final class NodeLauncher {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeLauncher.class);

    private NodeLauncher() {
    }

    /**
     * Prints the welcome message and starts a node on the given configuration. Starting the node
     * registers a JVM shutdown hook that stops its processes again.
     *
     * @param cli    the root command, which decides whether the welcome message appears
     * @param config the configuration the node is built from
     */
    static void start(final CommandLineInterface cli, final Config config) {
        cli.showWelcomeMessage();
        new Node(config).start();
    }

    /**
     * Blocks the calling thread for the lifetime of the node, which the shutdown hook ends.
     *
     * @return the exit code of the subcommand, which is 0 once the wait is interrupted
     */
    static int awaitShutdown() {
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.info("Node stopped gracefully.");
        }
        return 0;
    }
}
