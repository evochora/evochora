package org.evochora.compiler.frontend.parser;

/**
 * Capability interface for parser state objects that participate in scope
 * transitions. When the parser enters or exits a scope (e.g., procedure body),
 * all registered scoped state objects are notified via {@link ParserState#pushScope()}
 * and {@link ParserState#popScope()}.
 */
public interface IScopedParserState {

    /**
     * Called when entering a new scope (e.g., procedure body).
     */
    void pushScope();

    /**
     * Called when leaving the current scope.
     */
    void popScope();
}
