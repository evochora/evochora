package org.evochora.compiler.frontend.preprocessor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for preprocessing handlers. Maps token text (directive names like ".SOURCE"
 * or macro names like "EMIT") to their handlers. Unlike other compiler registries, this
 * registry is mutated at processing time — macro definitions dynamically register
 * expansion handlers.
 */
public class PreProcessorHandlerRegistry {

    private final Map<String, IPreProcessorHandler> handlers = new HashMap<>();

    /**
     * Registers a handler for a token name.
     * @param name    The token text that triggers this handler (e.g., ".SOURCE", "MY_MACRO").
     * @param handler The handler for this token.
     */
    public void register(String name, IPreProcessorHandler handler) {
        handlers.put(name.toUpperCase(), handler);
    }

    /**
     * Looks up the handler for a token name.
     * @param name The token text.
     * @return The handler, or empty if no handler is registered for this name.
     */
    public Optional<IPreProcessorHandler> get(String name) {
        return Optional.ofNullable(handlers.get(name.toUpperCase()));
    }

}
