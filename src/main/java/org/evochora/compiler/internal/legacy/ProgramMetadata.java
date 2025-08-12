package org.evochora.compiler.internal.legacy;

import org.evochora.runtime.model.Symbol;
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
        Map<List<Integer>, Integer> relativeCoordToLinearAddress,
        // Neu: Bindings pro CALL-Site (linear address -> Quellregister-IDs)
        Map<Integer, int[]> callSiteBindings,
        Map<String, DefinitionExtractor.ProcMeta> procMetaMap
) {}