package org.evochora.compiler.model.token;

/**
 * Defines the different types of tokens recognized during lexical analysis.
 */
public enum TokenType {
    // Single-character tokens.
    /** The '|' character, used in vector literals. */
    PIPE,
    /** The ':' character, used for labels and typed literals. */
    COLON,
    /** The ',' character, which separates the items of a list. */
    COMMA,
    /** A '(' that does not touch the word before it, opening a group. */
    OPEN_PARENTHESIS,
    /**
     * A '(' that directly touches the word before it, an identifier or an opcode, as in
     * {@code MIN(}; it opens the arguments of a call.
     */
    CALL_PARENTHESIS,
    /** The ')' character, which closes a group or the arguments of a call. */
    CLOSE_PARENTHESIS,
    /** The '[' character, which opens an index. */
    OPEN_BRACKET,
    /** The ']' character, which closes an index. */
    CLOSE_BRACKET,

    // Registered symbols.
    /**
     * A character sequence a feature registered with the lexer, such as {@code ..} or {@code +};
     * the token's text is the sequence, and handlers tell symbols apart by it.
     */
    SYMBOL,

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
    /** A directive, such as .proc or .const. */
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
