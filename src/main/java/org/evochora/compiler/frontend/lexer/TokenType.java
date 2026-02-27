package org.evochora.compiler.frontend.lexer;

/**
 * Defines the different types of tokens that the {@link Lexer} can recognize.
 */
public enum TokenType {
    // Single-character tokens.
    /** The '|' character, used in vector literals. */
    PIPE,
    /** The '#' character, used for comments. */
    HASH,
    /** The ':' character, used for labels and typed literals. */
    COLON,
    /** The '*' character, used as a wildcard. */
    STAR,
    /** The ',' character, used as a separator. */
    COMMA,
    // Two-character tokens
    /** The '..' character, used for ranges. */
    DOT_DOT,

    // Literals.
    /** An identifier, such as a variable or label name. */
    IDENTIFIER,
    /** A register, such as %DR0. */
    REGISTER,
    /** A numeric literal. */
    NUMBER,
    /** A string literal. */
    STRING,

    // Keywords & Directives.
    /** A directive, such as .proc or .define. */
    DIRECTIVE,
    /** An opcode, such as SETI or ADD. */
    OPCODE,

    // Miscellaneous.
    /** A newline character. */
    NEWLINE,
    /** Represents the end of the source file. */
    END_OF_FILE,
    /** Represents an unexpected or unknown token. */
    UNEXPECTED
}
