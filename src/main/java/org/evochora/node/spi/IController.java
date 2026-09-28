package org.evochora.node.spi;

import io.javalin.Javalin;

/**
 * A marker interface for all API controllers. It ensures that every controller
 * provides a method to register its routes with the Javalin application.
 */
public interface IController {

    /**
     * Registers all HTTP routes for this controller with the given Javalin instance.
     *
     * @param app      The Javalin application instance to register routes with.
     * @param basePath The base path under which the controller's routes should be nested.
     */
    void registerRoutes(Javalin app, String basePath);

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
