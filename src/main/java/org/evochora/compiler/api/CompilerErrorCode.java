package org.evochora.compiler.api;

/**
 * Defines unique, testable error codes for all errors that can occur during compilation.
 * This decouples the test logic from the translated error messages.
 */
public enum CompilerErrorCode {
    // region Pre-processor & Parser Errors
    /** A directive that requires a name (e.g., .SCOPE) was missing it. */
    DIRECTIVE_NEEDS_NAME,
    /** A block directive (e.g., .SCOPE) was not closed with an .END directive. */
    BLOCK_NOT_CLOSED,
    /** An unexpected directive was found outside of a block. */
    UNEXPECTED_DIRECTIVE_OUTSIDE_BLOCK,
    /** .DEFINE directive has an invalid number of arguments. */
    DEFINE_INVALID_ARGUMENT_COUNT,
    /** A procedure formal parameter name illegally starts with '%'. */
    PROC_FORMAL_MUST_NOT_BE_PERCENT,
    /** A routine parameter name collides with an instruction name. */
    ROUTINE_PARAMETER_COLLIDES_WITH_INSTRUCTION,
    /** .PREG directive has invalid syntax. */
    PREG_INVALID_SYNTAX,
    /** A physical register alias name must start with '%'. */
    PREG_NAME_MUST_START_WITH_PERCENT,
    /** A physical register alias index is invalid. */
    PREG_INVALID_INDEX,
    /** .PREG directive is invalid outside of a procedure. */
    INVALID_PREG_OUTSIDE_PROC,
    /** .IMPORT directive has invalid syntax. */
    INVALID_IMPORT_SYNTAX,
    /** .INCLUDE directive has invalid syntax. */
    INVALID_INCLUDE_SYNTAX,
    // endregion

    // region Semantic Analysis Errors
    /** An unknown routine (procedure or external function) was called. */
    UNKNOWN_ROUTINE,
    /** A WITH clause was used on a non-procedure call. */
    WITH_CLAUSE_REQUIRES_PROC,
    /** A .REQUIRE directive was used for a symbol that was not imported. */
    MISSING_IMPORT_FOR_REQUIRE,
    /** An unknown instruction mnemonic was used. */
    UNKNOWN_INSTRUCTION,
    /** A label name collides with an instruction name. */
    LABEL_COLLIDES_WITH_INSTRUCTION,
    /** An unknown type was used in a .PLACE directive. */
    UNKNOWN_TYPE_IN_PLACE_DIRECTIVE,
    // endregion

    // region Linker Errors
    /** A label was referenced but not defined. */
    LABEL_NOT_FOUND,
    // endregion

    // region General Errors
    /** An I/O error occurred while reading a file. */
    IO_ERROR_READING_FILE,
    /** An unknown or unexpected error occurred. */
    UNKNOWN_ERROR
    // endregion
}
