package org.evochora.compiler.frontend.preprocessor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of the preprocessing handlers, keyed by the token text that triggers them: a
 * directive name like ".SOURCE" or ".MACRO", or the name of a macro. It is the one place the
 * preprocessor looks a token up in.
 * <p>
 * Features fill it before the phase runs. Unlike the registries of the other phases it also
 * grows during the phase: a {@code .MACRO} directive registers the handler that expands the
 * macro from that point of the stream on. Names are compared case-insensitively.
 */
public class PreProcessorHandlerRegistry {

    private final Map<String, IPreProcessorHandler> handlers = new HashMap<>();

    /**
     * Registers a handler for a token name.
     * <p>
     * Registering a handler that is {@link Object#equals equal} to the one already held under
     * the name is ignored, so the same definition may arrive more than once; a different
     * handler under a held name is a programming error. A feature that can tell the
     * programmer what is wrong, as the macro feature does for a second definition, reports a
     * diagnostic instead of getting here.
     *
     * @param name    The token text that triggers this handler (e.g., ".SOURCE", "MY_MACRO").
     * @param handler The handler for this token.
     * @throws IllegalStateException if a different handler is already registered for the name.
     */
    public void register(String name, IPreProcessorHandler handler) {
        String key = name.toUpperCase();
        IPreProcessorHandler existing = handlers.get(key);
        if (existing != null) {
            if (existing.equals(handler)) {
                return;
            }
            throw new IllegalStateException(
                    "Preprocessor handler for '" + key + "' is already registered with a different handler");
        }
        handlers.put(key, handler);
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
