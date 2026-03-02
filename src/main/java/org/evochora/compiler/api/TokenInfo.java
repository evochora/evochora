package org.evochora.compiler.api;

import org.evochora.compiler.frontend.semantics.Symbol;

/**
 * Represents detailed information about a single token for debugging purposes.
 * This information is generated after semantic analysis and provides deterministic
 * token classification without guessing.
 *
 * @param tokenText The literal text of the token as it appears in source (e.g., "HARVEST" or "MYLIB.HARVEST").
 * @param tokenType The type of the symbol (e.g., LABEL, PARAMETER, CONSTANT).
 * @param scope The scope in which this token is defined (e.g., a procedure name or "global").
 * @param qualifiedName The canonical module-qualified name for artifact lookups (e.g., "ENERGY.HARVEST"), or null.
 */
public record TokenInfo(
    String tokenText,
    Symbol.Type tokenType,
    String scope,
    String qualifiedName
) {
    /**
     * Compatibility constructor for tokens that do not require a qualified name.
     */
    public TokenInfo(String tokenText, Symbol.Type tokenType, String scope) {
        this(tokenText, tokenType, scope, null);
    }
}
