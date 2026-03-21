package org.evochora.compiler.api;

/**
 * Classifies tokens by their semantic role for external consumers (e.g., syntax highlighting, debugging).
 * This is the public API equivalent of internal symbol classifications.
 */
public enum TokenKind {
    /** A label defined in the source code. */
    LABEL,
    /** A constant defined with .DEFINE. */
    CONSTANT,
    /** A procedure defined with .PROC. */
    PROCEDURE,
    /** A variable, such as a procedure parameter. */
    VARIABLE,
    /** A register alias defined with .REG. */
    ALIAS,
    /** An instruction opcode (e.g., CALL, RET, NOP, MOV). */
    INSTRUCTION
}
