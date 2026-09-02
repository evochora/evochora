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

    /**
     * The registry through which the controller looks up the services published by the node's
     * processes. It is shared by all controllers of an HTTP server, so a service fetched here is
     * the same instance the rest of the node uses, and lookups may happen while requests are being
     * served.
     */
    protected final ServiceRegistry registry;
    /**
     * The {@code options} block of this controller's route entry, holding the settings that belong
     * to this controller alone. A route declared without such a block gives an empty configuration,
     * so subclasses must treat every setting as absent unless they check for it or supply a
     * default.
     */
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