package org.evochora.node.spi;

/**
 * Marker interface for processes that expose a service to be consumed by other processes.
 * Processes implementing this interface can provide a single service instance that will be
 * injected into dependent processes via their constructors.
 *
 * <p>This enables explicit dependency injection between processes, replacing the implicit
 * Service Locator pattern. Dependencies are declared in the configuration and resolved at
 * startup time.</p>
 *
 * <p>Example: ServiceManagerProcess implements this to expose ServiceManager to HttpServerProcess.</p>
 */
public interface IServiceProvider {

    /**
     * Returns the service instance that this process exposes to other processes.
     * This method is called during process initialization to build the dependency graph.
     *
     * @return The service instance, or null if this process doesn't expose a service
     */
    Object getExposedService();
}
