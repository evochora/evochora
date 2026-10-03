package org.evochora.compiler.api;

/**
 * Represents detailed information about a single token for debugging purposes.
 * This information is generated after semantic analysis and provides deterministic
 * token classification without guessing.
 *
 * @param tokenText The literal text of the token as it appears in source (e.g., "HARVEST" or "MYLIB.HARVEST").
 * @param tokenType The semantic classification of the token (e.g., LABEL, VARIABLE, CONSTANT).
 * @param scope For a token that names a defined symbol, the scope the symbol is defined in; for any other token,
 *              the scope it is written in: "global" at module level, or the qualified name of a procedure.
 * @param qualifiedName The canonical module-qualified name for artifact lookups (e.g., "ENERGY.HARVEST"), or null.
 */
public record TokenInfo(
    String tokenText,
    TokenKind tokenType,
    String scope,
    String qualifiedName
) {
    /**
     * Creates a token without a qualified name, for the kinds that do not carry one. The
     * qualified name is left null.
     *
     * @param tokenText The literal text of the token as it appears in source.
     * @param tokenType The semantic classification of the token.
     * @param scope The scope, as the canonical constructor describes it.
     */
    public TokenInfo(String tokenText, TokenKind tokenType, String scope) {
        this(tokenText, tokenType, scope, null);
    }
}
