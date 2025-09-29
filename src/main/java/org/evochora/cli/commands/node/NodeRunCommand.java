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

@Command(
    name = "run",
    description = "Starts the Evochora Node."
)
public class NodeRunCommand implements Callable<Integer> {

    @ParentCommand
    private NodeCommand parent;

    @Option(names = {"-d", "--detach"}, description = "Run the node in the background (detached).")
    private boolean detach;

    private static final String PID_FILE_NAME = ".evochora.pid";

    @Override
    public Integer call() throws Exception {
        final Config config = parent.getParent().getConfig();

        if (detach) {
            System.out.println("Starting node in detached mode...");
            createPidFile();
        } else {
            System.out.println("Starting node in foreground...");
        }

        final Node node = new Node(config);
        node.start();

        // In a real foreground process, this thread would block until the node is stopped.
        // The node.start() method as implemented will block, so this is sufficient.

        return 0;
    }

    private void createPidFile() throws IOException {
        final String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        final File pidFile = new File(PID_FILE_NAME);

        try (final PrintWriter writer = new PrintWriter(pidFile)) {
            writer.println(pid);
        }

        pidFile.deleteOnExit();
        System.out.println("PID file created at " + pidFile.getAbsolutePath());
    }
}