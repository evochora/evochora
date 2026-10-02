package org.evochora.compiler.model.token;

import org.evochora.compiler.api.SourceInfo;

/**
 * Represents a single token extracted from the source code by the Lexer.
 *
 * @param type The type of the token (e.g., Opcode, Register, Number).
 * @param text The exact text of the token from the source code.
 * @param value The processed value of the token (e.g., the integer value of a number).
 * @param line The line number where the token was found.
 * @param column The column number where the token begins.
 * @param fileName The logical file name/source file path from which this token originates
 *                 (set correctly after preprocessor/include).
 * @param placement The alias chain of the module placement the token belongs to: the chain of
 *                  the main module for the main file and the files it sources, the chain of an
 *                  import for the module's tokens and the files that module sources. The
 *                  preprocessor sets it when it inlines a file.
 */
public record Token(
        TokenType type,
        String text,
        Object value,
        int line,
        int column,
        String fileName,
        String placement
) {
    /**
     * Converts this token's location into a {@link SourceInfo}.
     *
     * @return A SourceInfo with this token's file name, line, column and placement.
     */
    public SourceInfo toSourceInfo() {
        return new SourceInfo(fileName(), line(), column(), placement());
    }

    /**
     * Returns this token as it stands in another placement.
     *
     * @param newPlacement The alias chain of the placement.
     * @return A token equal to this one but for its placement.
     */
    public Token withPlacement(String newPlacement) {
        return new Token(type, text, value, line, column, fileName, newPlacement);
    }
}
