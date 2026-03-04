package org.evochora.compiler.api;

/**
 * Represents detailed information about a single token for debugging purposes.
 * This information is generated after semantic analysis and provides deterministic
 * token classification without guessing.
 *
 * @param tokenText The literal text of the token as it appears in source (e.g., "HARVEST" or "MYLIB.HARVEST").
 * @param tokenType The semantic classification of the token (e.g., LABEL, VARIABLE, CONSTANT).
 * @param scope The scope in which this token is defined (e.g., a procedure name or "global").
 * @param qualifiedName The canonical module-qualified name for artifact lookups (e.g., "ENERGY.HARVEST"), or null.
 */
public record TokenInfo(
    String tokenText,
    TokenKind tokenType,
    String scope,
    String qualifiedName
) {
    /**
     * Compatibility constructor for tokens that do not require a qualified name.
     */
    public TokenInfo(String tokenText, TokenKind tokenType, String scope) {
        this(tokenText, tokenType, scope, null);
    }
}
