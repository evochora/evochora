package org.evochora.runtime.services;

import org.evochora.runtime.model.Molecule;

/**
 * A simple data structure for disassembly results.
 * @param opcodeId The ID of the opcode (value part of the CODE molecule).
 * @param opcodeName The name of the opcode.
 * @param args The argument molecules (with type, value, and marker).
 * @param argPositions The positions of the arguments in the environment.
 */
public record DisassemblyData(
    int opcodeId,
    String opcodeName,
    Molecule[] args,
    int[][] argPositions
) {}
