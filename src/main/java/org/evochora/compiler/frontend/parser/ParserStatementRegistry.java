package org.evochora.compiler.frontend.parser;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for parser directive handlers.
 * Maps directive names (e.g., ".ORG", ".PROC") to their handlers.
 */
public class ParserDirectiveRegistry {

    private final Map<String, IParserDirectiveHandler> handlers = new HashMap<>();

    /**
     * Registers a handler for a directive name.
     * @param directiveName The directive name (e.g., ".ORG").
     * @param handler       The handler for this directive.
     */
    public void register(String directiveName, IParserDirectiveHandler handler) {
        handlers.put(directiveName.toUpperCase(), handler);
    }

    /**
     * Looks up the handler for a directive name.
     * @param directiveName The directive name.
     * @return The handler, or empty if no handler is registered for this directive.
     */
    public Optional<IParserDirectiveHandler> get(String directiveName) {
        return Optional.ofNullable(handlers.get(directiveName.toUpperCase()));
    }
}
