package org.evochora.cli.commands.node;

import java.util.concurrent.Callable;

import org.evochora.cli.CommandLineInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/**
 * The {@code node show} subcommand: starts a node that serves the runs already in the database and
 * simulates nothing.
 * <p>
 * The node's processes start as always — the HTTP server among them — but its service manager
 * leaves the pipeline services alone, so no engine, no persistence and no indexer runs. What the
 * visualizer and the analyzer show is what earlier runs left in the database.
 */
@Command(
    name = "show",
    description = "Starts the Evochora Node server for viewing indexed runs only"
)
public class NodeShowCommand implements Callable<Integer> {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeShowCommand.class);

    /** Configuration of the network the HTTP process listens on. */
    private static final String HTTP_NETWORK_PATH = "node.processes.http.options.network";

    @ParentCommand
    private NodeCommand parent;

    @Override
    public Integer call() {
        final CommandLineInterface cli = parent.getParent();
        final Config config = cli.getConfig(overrides());
        NodeLauncher.start(cli, config);
        warnIfNothingIsServed(config);
        return NodeLauncher.awaitShutdown();
    }

    /**
     * Builds the configuration layer that keeps the pipeline services stopped.
     * <p>
     * {@code pipeline.autoStart = false} is the only value: the node's processes are not governed
     * by it and start as they always do, so the HTTP server comes up and serves the database
     * while the service manager waits for the pipeline API that it never gets told to obey.
     *
     * @return an unresolved configuration to be placed above every other source
     */
    Config overrides() {
        return ConfigFactory.parseString("pipeline.autoStart = false\n");
    }

    /**
     * Warns when the node has no HTTP server, since a node that simulates nothing is then of no use.
     * Where the web interface can be reached is reported by the HTTP server itself.
     *
     * @param config the configuration the node was started with
     */
    private void warnIfNothingIsServed(final Config config) {
        if (!config.hasPath(HTTP_NETWORK_PATH)) {
            LOGGER.warn("No HTTP server process is configured under {}, so this node serves nothing.",
                HTTP_NETWORK_PATH);
        }
    }
}
