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
 *                 and the macro expansion it stands in. The lexer gives every token the position
 *                 it was read at, in placement and expansion 0 of the main module; the preprocessor
 *                 sets the placement when it inlines a file and the expansion when it expands a
 *                 macro.
 * @param replaces The positions of the parameters this token was substituted for. A macro
 *                 argument substituted for a parameter keeps its own position and remembers the
 *                 parameter's, with the expansion the parameter stands in; an argument that passes
 *                 through nested macros replaces one parameter per level, outermost first. Empty
 *                 for a token that replaces nothing.
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
