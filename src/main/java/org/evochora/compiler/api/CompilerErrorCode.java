package org.evochora.compiler.api;

/**
 * Definiert eindeutige, testbare Fehler-Codes für alle Fehler, die während der Kompilierung auftreten können.
 * Dies entkoppelt die Testlogik von den übersetzten Fehlermeldungen.
 */
public enum CompilerErrorCode {
    // Definition Errors
    DIRECTIVE_NEEDS_NAME,
    BLOCK_NOT_CLOSED,
    UNEXPECTED_DIRECTIVE_OUTSIDE_BLOCK,
    DEFINE_INVALID_ARGUMENT_COUNT,
    PROC_FORMAL_MUST_NOT_BE_PERCENT,
    ROUTINE_PARAMETER_COLLIDES_WITH_INSTRUCTION,
    PREG_INVALID_SYNTAX,
    PREG_NAME_MUST_START_WITH_PERCENT,
    PREG_INVALID_INDEX,
    INVALID_PREG_OUTSIDE_PROC,

    // Expansion Errors
    INVALID_IMPORT_SYNTAX,
    INVALID_INCLUDE_SYNTAX,
    UNKNOWN_ROUTINE,
    WITH_CLAUSE_REQUIRES_PROC,
    MISSING_IMPORT_FOR_REQUIRE,

    // Pass Errors
    UNKNOWN_INSTRUCTION,
    LABEL_COLLIDES_WITH_INSTRUCTION,
    UNKNOWN_TYPE_IN_PLACE_DIRECTIVE,

    // Placeholder Errors
    LABEL_NOT_FOUND,

    // General
    IO_ERROR_READING_FILE,
    UNKNOWN_ERROR
}
