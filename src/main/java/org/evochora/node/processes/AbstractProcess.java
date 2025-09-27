package org.evochora.node.processes;

import com.typesafe.config.Config;
import org.evochora.node.spi.IProcess;
import org.evochora.node.spi.ServiceRegistry;

/**
 * An abstract base class for {@link IProcess} implementations. It provides a consistent
 * constructor for dependency injection, ensuring that every process receives the
 * {@link ServiceRegistry} and its specific configuration block.
 */
public abstract class AbstractProcess implements IProcess {

    protected final ServiceRegistry registry;
    protected final Config options;

    /**
     * Constructs a new AbstractProcess.
     *
     * @param registry The central service registry for accessing shared services.
     * @param options  The HOCON configuration specific to this process instance.
     */
    public AbstractProcess(final ServiceRegistry registry, final Config options) {
        this.registry = registry;
        this.options = options;
    }
}