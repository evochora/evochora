package org.evochora.node.spi;

import io.javalin.config.RoutesConfig;

/**
 * A marker interface for all API controllers. It ensures that every controller
 * provides a method to register its routes while the Javalin application is configured.
 */
public interface IController {

    /**
     * Registers all HTTP routes for this controller while the Javalin application is configured,
     * before the server starts.
     *
     * @param routes   The routes of the Javalin application to register this controller's routes with.
     * @param basePath The base path under which the controller's routes should be nested.
     */
    void registerRoutes(RoutesConfig routes, String basePath);

    /**
     * Releases what the controller holds beyond the lifetime of a request, such as threads or
     * caches. Called once, after the HTTP server has stopped and before the resources the
     * controller reads from are closed, so no request is running any more.
     * <p>
     * The default holds nothing and does nothing.
     */
    default void close() {
    }
}
