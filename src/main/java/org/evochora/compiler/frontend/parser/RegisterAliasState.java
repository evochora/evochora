package org.evochora.compiler.frontend.parser;

import org.evochora.compiler.model.token.Token;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages scoped register aliases during parsing.
 * Each scope (e.g., procedure body) can define local aliases that shadow outer scopes.
 * The global scope is always present at the bottom of the stack.
 */
public class RegisterAliasState implements IScopedParserState {

    private final Deque<Map<String, Token>> scopes = new ArrayDeque<>();

    public RegisterAliasState() {
        scopes.push(new HashMap<>());
    }

    /**
     * Adds a register alias to the current scope.
     * @param name The alias name (will be uppercased).
     * @param registerToken The token representing the actual register.
     */
    public void addAlias(String name, Token registerToken) {
        scopes.peek().put(name.toUpperCase(), registerToken);
    }

    /**
     * Pushes a new scope onto the stack (e.g., when entering a procedure body).
     */
    public void pushScope() {
        scopes.push(new HashMap<>());
    }

    /**
     * Pops the current scope from the stack (e.g., when leaving a procedure body).
     * The global scope cannot be popped.
     */
    public void popScope() {
        if (scopes.size() > 1) {
            scopes.pop();
        }
    }

    /**
     * Returns a copy of the global (bottom-most) scope aliases.
     * @return A map of global register aliases.
     */
    public Map<String, Token> getGlobalAliases() {
        return new HashMap<>(scopes.getLast());
    }
}
