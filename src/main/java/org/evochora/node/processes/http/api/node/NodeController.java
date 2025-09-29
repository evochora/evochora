package org.evochora.node.processes.http.api.node;

import com.typesafe.config.Config;
import io.javalin.Javalin;
import org.evochora.node.processes.http.AbstractController;
import org.evochora.node.spi.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.evochora.node.Node;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class NodeController extends AbstractController {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeController.class);

    public NodeController(final ServiceRegistry registry, final Config options) {
        super(registry, options);
    }

    @Override
    public void registerRoutes(final Javalin app, final String basePath) {
        app.post(basePath + "/stop", ctx -> {
            LOGGER.info("Received stop request. Shutting down node...");
            ctx.status(202).result("Shutdown initiated.");

            // Schedule the shutdown to allow the HTTP response to be sent.
            Executors.newSingleThreadScheduledExecutor().schedule(() -> {
                final Node node = registry.get(Node.class);
                if (node != null) {
                    node.stop();
                } else {
                    LOGGER.error("Could not find Node in ServiceRegistry to stop it.");
                }
            }, 200, TimeUnit.MILLISECONDS);
        });
    }
}