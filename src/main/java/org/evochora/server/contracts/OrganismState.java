package org.evochora.server.contracts;

import java.util.List;

/**
 * Snapshot of a single organism at a given tick, using native Java types.
 */
public record OrganismState(
        int organismId,
        String programId,
        Integer parentId,
        long birthTick,
        long energy,
        List<Integer> position,
        List<Integer> dp,
        List<Integer> dv,
        // stateJson parts flattened as native: registers and stacks
        int ip,
        int er,
        List<String> dataRegisters, // Geändert von int[]
        List<String> procRegisters, // Geändert von int[]
        List<String> dataStack,     // Geändert von List<Integer>
        List<String> callStack,     // Geändert von List<Integer>
        List<String> formalParameters,
        List<String> fprs,
        String disassembledInstructionJson
) {
}