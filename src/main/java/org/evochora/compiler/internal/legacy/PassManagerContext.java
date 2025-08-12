package org.evochora.compiler.internal.legacy;

import org.evochora.runtime.model.Symbol;
import java.util.List;
import java.util.Map;

/**
 * Ein Datencontainer, der den gesamten Zustand während der Assembler-Durchläufe enthält.
 */
public record PassManagerContext(
        // Zustandsvariablen, die von Handlern modifiziert werden
        int[] currentPos,
        int[] currentDv,
        int linearAddress,

        // Maps zum Befüllen
        Map<String, Integer> registerMap,
        Map<Integer, String> registerIdToNameMap,
        Map<int[], Symbol> initialWorldObjects,
        Map<String, Integer> labelMap,
        Map<Integer, String> labelAddressToNameMap,
        Map<Integer, int[]> linearAddressToCoordMap,
        Map<List<Integer>, Integer> coordToLinearAddressMap,
        Map<Integer, SourceLocation> sourceMap,

        // Hinzugefügt für Vollständigkeit
        String programName
) {}