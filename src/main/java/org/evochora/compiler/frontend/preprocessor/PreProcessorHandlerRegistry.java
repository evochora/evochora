package org.evochora.compiler.frontend.preprocessor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for static preprocessing handlers. Maps token text (directive names like
 * ".SOURCE" or ".MACRO") to their handlers. This registry is populated at initialization
 * time and remains immutable during preprocessing. Dynamic runtime handlers (e.g., macro
 * expansion handlers) are registered on {@link PreProcessorContext} instead.
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
