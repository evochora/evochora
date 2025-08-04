// src/main/java/org/evochora/assembler/ProgramMetadata.java
package org.evochora.assembler;

import org.evochora.world.Symbol;

import java.util.List;
import java.util.Map;

/**
 * Ein typsicherer Datencontainer (Record), der alle relevanten Informationen speichert,
 * die während des Assemblierungsprozesses für ein einzelnes AssemblyProgram generiert werden.
 * Dieses Objekt dient als Ergebnis des Assemblers und als Eingabe für den Disassembler.
 */
public record ProgramMetadata(
        String programId,
        Map<int[], Integer> machineCodeLayout,
        Map<int[], Symbol> initialWorldObjects,
        int[] finalAssemblyDv,
        Map<String, Integer> registerMap,
        Map<Integer, String> registerIdToName,
        Map<String, Integer> labelMap,
        Map<Integer, String> labelAddressToName,
        Map<Integer, int[]> linearAddressToRelativeCoord,
        Map<List<Integer>, Integer> relativeCoordToLinearAddress
) {}