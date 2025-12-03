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
}