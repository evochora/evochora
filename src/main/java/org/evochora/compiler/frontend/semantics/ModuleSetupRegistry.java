package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.frontend.module.IDependencyInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry for module setup handlers, keyed by IDependencyInfo subclass.
 * Same dispatch pattern as AnalysisHandlerRegistry (class → handler lookup).
 */
public class ModuleSetupRegistry {

    private final Map<Class<? extends IDependencyInfo>, IDependencySetupHandler<?>> handlers = new HashMap<>();

    /**
     * Registers the handler responsible for one kind of dependency data. Registering a
     * second handler for the same class replaces the first without complaint.
     *
     * @param type    The dependency info class, matched exactly when a handler is resolved.
     * @param handler The handler invoked for dependencies of that class.
     * @param <T>     The dependency info type shared by class and handler.
     */
    public <T extends IDependencyInfo> void register(Class<T> type, IDependencySetupHandler<T> handler) {
        handlers.put(type, handler);
    }

    /**
     * Resolves the handler for the given dependency info type.
     * Returns null if no handler is registered.
     *
     * @param <T>  The dependency info type the caller expects the handler to accept; the cast
     *             is unchecked, so the caller is responsible for passing a matching class.
     * @param type The dependency info class to look up. Lookup is by exact class; superclasses
     *             and interfaces are not consulted.
     * @return The registered handler, or null if the class has none.
     */
    @SuppressWarnings("unchecked")
    public <T extends IDependencyInfo> IDependencySetupHandler<T> resolve(Class<? extends IDependencyInfo> type) {
        return (IDependencySetupHandler<T>) handlers.get(type);
    }
}
