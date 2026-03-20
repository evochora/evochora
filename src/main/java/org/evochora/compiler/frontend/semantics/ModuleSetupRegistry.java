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

    public <T extends IDependencyInfo> void register(Class<T> type, IDependencySetupHandler<T> handler) {
        handlers.put(type, handler);
    }

    /**
     * Resolves the handler for the given dependency info type.
     * Returns null if no handler is registered.
     */
    @SuppressWarnings("unchecked")
    public <T extends IDependencyInfo> IDependencySetupHandler<T> resolve(Class<? extends IDependencyInfo> type) {
        return (IDependencySetupHandler<T>) handlers.get(type);
    }
}
