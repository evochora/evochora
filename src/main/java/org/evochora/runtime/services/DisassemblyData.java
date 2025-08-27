package org.evochora.runtime.services;

/**
 * A simple data structure for disassembly results.
 * Contains only primitive values without objects for maximum performance.
 * @param opcodeId The ID of the opcode.
 * @param opcodeName The name of the opcode.
 * @param argValues The values of the arguments.
 * @param argPositions The positions of the arguments.
 */
public record DisassemblyData(
    int opcodeId,
    String opcodeName,
    int[] argValues,
    int[][] argPositions
) {}
