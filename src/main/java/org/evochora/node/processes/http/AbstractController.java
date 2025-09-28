package org.evochora.node.processes.http;

import com.typesafe.config.Config;
import org.evochora.node.spi.IController;
import org.evochora.node.spi.ServiceRegistry;

/**
 * An abstract base class for {@link IController} implementations. It provides a
 * consistent constructor for dependency injection, ensuring every controller has
 * access to the {@link ServiceRegistry} and its specific configuration.
 */
public abstract class AbstractController implements IController {

    protected final ServiceRegistry registry;
    protected final Config options;

    /**
     * Constructs a new AbstractController.
     *
     * @param registry The central service registry for accessing shared services.
     * @param options  The HOCON configuration specific to this controller instance.
     */
    public AbstractController(final ServiceRegistry registry, final Config options) {
        this.registry = registry;
        this.options = options;
    }
}