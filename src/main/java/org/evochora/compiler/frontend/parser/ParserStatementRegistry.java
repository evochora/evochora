package org.evochora.compiler.frontend.parser;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for parser statement handlers.
 * Maps keywords (directives like ".ORG", opcodes like "CALL", or other identifiers)
 * to their handlers. Supports exactly one default handler for unrecognized keywords.
 */
public class ParserStatementRegistry {

    private final Map<String, IParserStatementHandler> handlers = new HashMap<>();
    private IParserStatementHandler defaultHandler;

    /**
     * Registers a handler for a keyword.
     * @param keyword The keyword (e.g., ".ORG", "CALL").
     * @param handler The handler for this keyword.
     * @throws IllegalStateException if a handler is already registered for this keyword.
     */
    public void register(String keyword, IParserStatementHandler handler) {
        String key = keyword.toUpperCase();
        if (handlers.containsKey(key)) {
            throw new IllegalStateException("Parser statement handler already registered for keyword: " + keyword);
        }
        handlers.put(key, handler);
    }

    /**
     * Registers the default handler for unrecognized keywords.
     * @param handler The default handler.
     * @throws IllegalStateException if a default handler is already registered.
     */
    public void registerDefault(IParserStatementHandler handler) {
        if (this.defaultHandler != null) {
            throw new IllegalStateException("Default parser statement handler already registered");
        }
        this.defaultHandler = handler;
    }

    /**
     * Looks up the handler for a keyword.
     * @param keyword The keyword.
     * @return The handler, or empty if no handler is registered for this keyword.
     */
    public Optional<IParserStatementHandler> get(String keyword) {
        return Optional.ofNullable(handlers.get(keyword.toUpperCase()));
    }

    /**
     * Returns the default handler, if registered.
     */
    public Optional<IParserStatementHandler> getDefault() {
        return Optional.ofNullable(defaultHandler);
    }
}
