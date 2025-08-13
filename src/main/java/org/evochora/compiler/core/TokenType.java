package org.evochora.compiler.core;

/**
 * Definiert die verschiedenen Arten von Tokens, die der {@link Lexer} erkennen kann.
 */
public enum TokenType {
    // Einzelne Zeichen
    PIPE, HASH, COLON,

    // Literale
    IDENTIFIER,
    REGISTER,
    NUMBER,
    STRING,

    // Schlüsselwörter & Direktiven
    DIRECTIVE, // .PROC, .DEFINE, etc.
    OPCODE,    // SETI, ADD, etc.

    // Sonstiges
    NEWLINE,
    END_OF_FILE,
    UNEXPECTED
}
