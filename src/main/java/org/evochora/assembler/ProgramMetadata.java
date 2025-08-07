package org.evochora.assembler;

import org.evochora.world.Symbol;
import java.util.List;
import java.util.Map;

/**
 * Ein typsicherer Datencontainer (Record), der alle relevanten Informationen speichert,
 * die während des Assemblierungsprozesses generiert werden.
 * Diese Version wurde erweitert, um die neue Source Map für das Laufzeit-Debugging aufzunehmen.
 */
public record ProgramMetadata(
        String programId,
        Map<int[], Integer> machineCodeLayout,
        Map<int[], Symbol> initialWorldObjects,
        // NEU: Die Source Map, die eine lineare Adresse auf ihren Ursprungsort abbildet.
        Map<Integer, SourceLocation> linearAddressToSourceLocation,
        Map<String, Integer> registerMap,
        Map<Integer, String> registerIdToName,
        Map<String, Integer> labelMap,
        Map<Integer, String> labelAddressToName,
        Map<Integer, int[]> linearAddressToRelativeCoord,
        Map<List<Integer>, Integer> relativeCoordToLinearAddress
) {}