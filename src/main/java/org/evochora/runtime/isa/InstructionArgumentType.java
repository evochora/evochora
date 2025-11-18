package org.evochora.runtime.isa;

/**
 * Defines the semantic types of instruction arguments for the compiler.
 * This is used by the SemanticAnalyzer to check the correctness of instructions.
 */
public enum InstructionArgumentType {
    /** A register (e.g., %DR0, %PR1). */
    REGISTER,
    /** A numeric or typed literal (e.g., 42, DATA:10). */
    LITERAL,
    /** A vector literal (e.g., 1|0). */
    VECTOR,
    /** A label that resolves to an address. */
    LABEL,
    /** A location register (e.g., %LR0, %LR3). */
    LOCATION_REGISTER
}