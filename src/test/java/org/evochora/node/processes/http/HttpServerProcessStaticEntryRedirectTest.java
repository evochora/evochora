package org.evochora.node.processes.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import org.evochora.datapipeline.ServiceManager;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Tests that {@link HttpServerProcess} redirects the entry path of a static route to the same
 * path with a trailing slash, so that the relative paths of the page served there resolve.
 * <p>
 * Integration test: it starts the server on a free port the operating system names.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class HttpServerProcessStaticEntryRedirectTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    private static HttpServerProcess process;
    private static int port;

    @BeforeAll
    static void startServer() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        final Config options = ConfigFactory.parseString(
            "network { host = \"127.0.0.1\", port = " + port + " }\n"
            + "routes { app { \"$static\" = \"org/evochora/node/processes/http/static-entry\" } }");
        process = new HttpServerProcess("http-test",
            Map.of("serviceManager", mock(ServiceManager.class)), options);
        process.start();
    }

    @AfterAll
    static void stopServer() {
        process.stop();
    }

    private static HttpResponse<String> send(final String method, final String path)
            throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void redirectsTheEntryPathToItsDirectoryAndKeepsTheQuery() throws Exception {
        final HttpResponse<String> response = send("GET", "/app?runId=r1&x=2");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValue("/app/?runId=r1&x=2");
    }

    @Test
    void redirectsTheEntryPathWithoutQueryToItsDirectory() throws Exception {
        final HttpResponse<String> response = send("GET", "/app");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValue("/app/");
    }

    @Test
    void redirectsHeadRequestsOnTheEntryPath() throws Exception {
        final HttpResponse<String> response = send("HEAD", "/app");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValue("/app/");
    }

    @Test
    void servesThePageUnderTheDirectoryPath() throws Exception {
        final HttpResponse<String> response = send("GET", "/app/");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("ENTRY PAGE\n");
    }

    @Test
    void servesFilesOfTheRouteWithoutRedirect() throws Exception {
        final HttpResponse<String> response = send("GET", "/app/app.js");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("APP FILE\n");
    }

    @Test
    void doesNotRedirectMethodsThatStaticFilesDoNotAnswer() throws Exception {
        final HttpResponse<String> response = send("POST", "/app");

        assertThat(response.statusCode()).isNotEqualTo(302);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }
}
