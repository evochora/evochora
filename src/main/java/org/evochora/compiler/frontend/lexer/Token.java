package org.evochora.compiler.frontend.lexer;

/**
 * Represents a single token extracted from the source code by the {@link Lexer}.
 *
 * @param type The type of the token (e.g., Opcode, Register, Number).
 * @param text The exact text of the token from the source code.
 * @param value The processed value of the token (e.g., the integer value of a number).
 * @param line The line number where the token was found.
 * @param column The column number where the token begins.
 * @param fileName The logical file name/source file path from which this token originates
 *                 (set correctly after preprocessor/include).
 */
public record Token(
        TokenType type,
        String text,
        Object value,
        int line,
        int column,
        String fileName
) {
}
