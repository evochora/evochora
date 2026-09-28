package org.evochora.node.processes.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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

import io.javalin.Javalin;

/**
 * Tests that {@link HttpServerProcess} closes the controllers it created once the server has
 * stopped.
 * <p>
 * Integration test: it starts the server on a port the operating system picks.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class HttpServerProcessControllerLifecycleTest {

    /**
     * A controller that records whether it was closed and whether the server was still running
     * at that moment. The server creates it by class name, so the record is static; every test
     * resets it.
     */
    public static final class RecordingController implements IController {
        static volatile int closed;
        static volatile boolean serverRunningAtClose;
        private Javalin app;

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
        public void registerRoutes(final Javalin app, final String basePath) {
            this.app = app;
        }

        @Override
        public void close() {
            closed++;
            serverRunningAtClose = app.jettyServer().server().isRunning();
        }
    }

    @Test
    void closesItsControllersOnceAfterTheServerHasStopped() {
        RecordingController.closed = 0;
        RecordingController.serverRunningAtClose = true;
        final Config options = ConfigFactory.parseString(
            "network { host = \"127.0.0.1\", port = 0 }\n"
            + "routes { test { \"$controller\" { className = \""
            + RecordingController.class.getName() + "\" } } }");
        final HttpServerProcess process = new HttpServerProcess("http-test",
            Map.of("serviceManager", mock(ServiceManager.class)), options);

        process.start();
        assertThat(RecordingController.closed).isZero();

        process.stop();
        assertThat(RecordingController.closed).isEqualTo(1);
        assertThat(RecordingController.serverRunningAtClose).isFalse();

        process.stop();
        assertThat(RecordingController.closed).as("a second stop closes nothing again").isEqualTo(1);
    }
}
