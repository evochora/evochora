package org.evochora.server.contracts;

import java.util.List;

/**
 * Snapshot of a single organism at a given tick, using native Java types.
 * This record serves as a data transfer object for serialization and persistence.
 */
public record OrganismState(
        int organismId,
        String programId,
        Integer parentId,
        long birthTick,
        long energy,
        List<Integer> position,
        List<List<Integer>> dps,
        List<Integer> dv,
        List<Integer> returnIp,
        // stateJson parts flattened as native: registers and stacks
        int ip,
        int er,
        List<String> dataRegisters,
        List<String> procRegisters,
        List<String> dataStack,
        List<String> callStack,
        List<String> formalParameters,
        List<String> fprs,
        String disassembledInstructionJson,
        // NEW: Location-based architecture fields
        List<String> locationRegisters,
        List<String> locationStack
) {
}