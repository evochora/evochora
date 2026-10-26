package org.evochora.node.processes.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

import org.evochora.datapipeline.ServiceManager;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.node.spi.IController;
import org.evochora.node.spi.ServiceRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import io.javalin.config.RoutesConfig;

/**
 * Tests that {@link HttpServerProcess} closes the controllers it created once the server has
 * stopped.
 * <p>
 * Integration test: it starts the server on a free port the operating system names.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class HttpServerProcessControllerLifecycleTest {

    /**
     * A controller that records whether it was closed and whether the server still accepted
     * connections at that moment. The server creates it by class name, so the record and the port
     * to probe are static; every test resets them.
     */
    public static final class RecordingController implements IController {
        static volatile int closed;
        static volatile boolean serverRunningAtClose;
        static volatile int port;

        /**
         * Constructor the server calls for every controller route.
         *
         * @param registry Not used
         * @param options  Not used
         */
        public RecordingController(final ServiceRegistry registry, final Config options) {
            // Nothing to set up
        }

        @Override
        public void registerRoutes(final RoutesConfig routes, final String basePath) {
            // Nothing to serve: the controller only records when it is closed
        }

        @Override
        public void close() {
            closed++;
            serverRunningAtClose = acceptsConnections(port);
        }
    }

    private static boolean acceptsConnections(final int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (final IOException e) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void closesItsControllersOnceAfterTheServerHasStopped() throws IOException {
        RecordingController.closed = 0;
        RecordingController.serverRunningAtClose = true;
        RecordingController.port = freePort();
        final Config options = ConfigFactory.parseString(
            "network { host = \"127.0.0.1\", port = " + RecordingController.port + " }\n"
            + "routes { test { \"$controller\" { className = \""
            + RecordingController.class.getName() + "\" } } }");
        final HttpServerProcess process = new HttpServerProcess("http-test",
            Map.of("serviceManager", mock(ServiceManager.class)), options);

        process.start();
        assertThat(RecordingController.closed).isZero();
        assertThat(acceptsConnections(RecordingController.port)).as("the server is up").isTrue();

        process.stop();
        assertThat(RecordingController.closed).isEqualTo(1);
        assertThat(RecordingController.serverRunningAtClose).isFalse();

        process.stop();
        assertThat(RecordingController.closed).as("a second stop closes nothing again").isEqualTo(1);
    }
}
