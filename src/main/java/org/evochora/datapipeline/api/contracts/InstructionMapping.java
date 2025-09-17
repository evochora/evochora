package org.evochora.datapipeline.api.contracts;

/**
 * Represents a mapping between a position in the world and a machine code instruction.
 *
 * @param position    The n-dimensional coordinate of the instruction.
 * @param instruction The numeric value of the instruction.
 */
public record InstructionMapping(
    int[] position,
    int instruction
) {
}
