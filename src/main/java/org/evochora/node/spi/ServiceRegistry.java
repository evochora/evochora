package org.evochora.node.spi;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simple, type-safe dependency injection container that holds singleton instances of core services.
 * This acts as a service locator, allowing different parts of the application to access shared
 * components without hard-coded dependencies.
 */
public final class ServiceRegistry {

    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    /**
     * Registers a service instance with the registry.
     *
     * @param type     The class type under which to register the service. This is typically an interface.
     * @param instance The singleton instance of the service.
     * @throws IllegalArgumentException if a service for the given type is already registered.
     */
    public void register(final Class<?> type, final Object instance) {
        if (services.containsKey(type)) {
            throw new IllegalArgumentException("Service of type " + type.getName() + " is already registered.");
        }
        services.put(type, instance);
    }

    /**
     * Retrieves a service instance from the registry.
     *
     * @param type The class type of the service to retrieve.
     * @param <T>  The type of the service.
     * @return The service instance.
     * @throws IllegalArgumentException if no service for the given type is found.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(final Class<T> type) {
        final Object instance = services.get(type);
        if (instance == null) {
            throw new IllegalArgumentException("No service registered for type " + type.getName());
        }
        return (T) instance;
    }

    /**
     * Checks if a service of the given type is already registered.
     *
     * @param type The class type to check.
     * @return true if a service is registered, false otherwise.
     */
    public boolean hasService(final Class<?> type) {
        return services.containsKey(type);
    }
}
