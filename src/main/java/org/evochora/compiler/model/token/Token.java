package org.evochora.compiler.model.token;

import org.evochora.compiler.api.SourceInfo;

import java.util.List;

/**
 * Represents a single token extracted from the source code by the Lexer.
 *
 * @param type     The type of the token (e.g., Opcode, Register, Number).
 * @param text     The exact text of the token from the source code.
 * @param value    The processed value of the token (e.g., the integer value of a number).
 * @param source   Where the token stands: its file, line and column, the placement it belongs to
 *                 and the instance of injected tokens it stands in. The lexer gives every token
 *                 the position it was read at, with an empty placement and instance 0; the
 *                 preprocessor gives the tokens of the main file the root placement, and its
 *                 handlers set the placement and the instance of the tokens they inject.
 * @param replaces The positions this token replaced. A handler that substitutes a token for a
 *                 token at another position keeps the substituted token's own position and adds
 *                 the replaced one, with the instance it stands in; a token substituted again at
 *                 a further level adds one position per level, outermost first. Empty for a token
 *                 that replaces nothing.
 */
public record Token(
        TokenType type,
        String text,
        Object value,
        SourceInfo source,
        List<SourceInfo> replaces
) {
    /**
     * Makes the list of replaced positions immutable; null becomes an empty list.
     */
    public Token {
        replaces = replaces != null ? List.copyOf(replaces) : List.of();
    }

    /**
     * Creates a token that replaces nothing.
     *
     * @param type   The type of the token.
     * @param text   The exact text of the token.
     * @param value  The processed value of the token.
     * @param source Where the token stands.
     */
    public Token(TokenType type, String text, Object value, SourceInfo source) {
        this(type, text, value, source, List.of());
    }

    /**
     * Returns this token standing at another position, replacing the same parameters.
     *
     * @param newSource The position the token stands at.
     * @return A token equal to this one but for its position.
     */
    public Token with(SourceInfo newSource) {
        return new Token(type, text, value, newSource, replaces);
    }
}
