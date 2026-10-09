package org.evochora.compiler.api;

/**
 * Represents detailed information about a single token for debugging purposes.
 * This information is generated after semantic analysis and provides deterministic
 * token classification without guessing.
 *
 * @param tokenText The literal text of the token as it appears in source (e.g., "HARVEST" or "MYLIB.HARVEST").
 * @param tokenType The semantic classification of the token (e.g., LABEL, VARIABLE, CONSTANT).
 * @param scope For a token that names a defined symbol, the scope the symbol is defined in; for any other token,
 *              the scope it is written in: {@link #MODULE_LEVEL} at module level, or the path of the level
 *              it stands in (e.g., "ENERGY.SCAN").
 * @param qualifiedName The path of the symbol the token names, under which the artifact files what it knows of it
 *                      (e.g., "ENERGY.HARVEST", or "ENERGY.SCAN.DONE" for a name DONE defined in the level SCAN
 *                      of module ENERGY), or null.
 */
public record TokenInfo(
    String tokenText,
    TokenKind tokenType,
    String scope,
    String qualifiedName
) {
    /**
     * The scope of a token at module level, outside every level a node opens: empty, as the module
     * level has no path of its own ({@link QualifiedNames}), so that no name a program can define
     * is mistaken for it. Any other scope is the path of the level the token stands in.
     */
    public static final String MODULE_LEVEL = "";

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
