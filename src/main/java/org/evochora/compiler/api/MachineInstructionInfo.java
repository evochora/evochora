package org.evochora.compiler.api;

/**
 * Represents information about a single machine instruction that was generated from source code.
 * This is used to display all machine instructions that correspond to a single source line
 * in the visualizer, particularly for compiler-generated instructions (e.g., PUSH/POP for CALL).
 *
 * @param linearAddress The linear address of the instruction's opcode in the machine code layout.
 * @param opcode The opcode name (e.g., "PUSH", "CALL", "POP").
 * @param operandsAsString A formatted string representation of the operands (e.g., "%DR0", "DATA:3", "10|20").
 */
public record MachineInstructionInfo(
        int linearAddress,
        String opcode,
        String operandsAsString
) {}

