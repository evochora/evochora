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
        int[] dataRegisters,
        int[] procRegisters,
        List<Integer> dataStack,
        List<Integer> callStack
) {
}


