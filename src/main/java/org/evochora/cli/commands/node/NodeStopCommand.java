package org.evochora.cli.commands.node;

import com.typesafe.config.Config;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
    name = "stop",
    description = "Stops the currently running Evochora Node."
)
public class NodeStopCommand implements Callable<Integer> {

    @ParentCommand
    private NodeCommand parent;

    private static final String PID_FILE_NAME = ".evochora.pid";

    @Override
    public Integer call() throws Exception {
        final Config config = parent.getParent().getConfig();
        final String host = config.getString("node.processes.httpServer.options.network.host");
        final int port = config.getInt("node.processes.httpServer.options.network.port");
        final String stopUrl = String.format("http://%s:%d/node/stop", host, port);

        System.out.println("Attempting to stop the node by sending a request to " + stopUrl);

        final HttpClient client = HttpClient.newHttpClient();
        final HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(stopUrl))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        try {
            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 202) {
                System.out.println("Node shutdown request accepted.");
                deletePidFile();
                return 0;
            } else {
                System.err.println("Failed to stop the node. Server responded with status code: " + response.statusCode());
                return 1;
            }
        } catch (final IOException | InterruptedException e) {
            System.err.println("Failed to connect to the node. Is it running?");
            // Even if we fail to connect, we should try to clean up the PID file if it exists.
            deletePidFile();
            return 1;
        }
    }

    private void deletePidFile() {
        final File pidFile = new File(PID_FILE_NAME);
        if (pidFile.exists()) {
            try {
                Files.delete(Path.of(pidFile.getAbsolutePath()));
                System.out.println("PID file deleted.");
            } catch (IOException e) {
                System.err.println("Failed to delete PID file: " + e.getMessage());
            }
        }
    }
}