package org.evochora.compiler.internal.legacy;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.runtime.model.Molecule;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Ein typsicherer Datencontainer (Record), der alle relevanten Informationen speichert,
 * die während des Assemblierungsprozesses generiert werden.
 */
public record ProgramMetadata(
        String programId,
        Map<int[], Integer> machineCodeLayout,
        Map<int[], Molecule> initialWorldObjects,
        Map<Integer, SourceLocation> sourceMap,
        Map<String, Integer> registerMap,
        Map<Integer, int[]> callSiteBindings,
        Map<String, Integer> relativeCoordToLinearAddress, // KORRIGIERT
        Map<Integer, int[]> linearAddressToCoord,
        Map<Integer, String> labelAddressToName,
        Map<String, DefinitionExtractor.ProcMeta> procMetaMap
) {
    public ProgramMetadata {
        // Stellt sicher, dass die Maps unveränderlich sind.
        machineCodeLayout = Collections.unmodifiableMap(machineCodeLayout);
        initialWorldObjects = Collections.unmodifiableMap(initialWorldObjects);
        sourceMap = Collections.unmodifiableMap(sourceMap);
        registerMap = Collections.unmodifiableMap(registerMap);
        callSiteBindings = Collections.unmodifiableMap(callSiteBindings);
        relativeCoordToLinearAddress = Collections.unmodifiableMap(relativeCoordToLinearAddress);
        linearAddressToCoord = Collections.unmodifiableMap(linearAddressToCoord);
        labelAddressToName = Collections.unmodifiableMap(labelAddressToName);
        procMetaMap = Collections.unmodifiableMap(procMetaMap);
    }

    public static ProgramMetadata fromArtifact(ProgramArtifact artifact) {
        Map<int[], Molecule> initialObjects = artifact.initialWorldObjects().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new Molecule(e.getValue().type(), e.getValue().value())
                ));

        Map<Integer, SourceLocation> sourceMap = artifact.sourceMap().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> {
                            SourceInfo info = e.getValue();
                            return new SourceLocation(info.fileName(), info.lineNumber(), info.lineContent());
                        }
                ));

        Map<String, List<String>> procParams = artifact.procNameToParamNames() != null ? artifact.procNameToParamNames() : Collections.emptyMap();
        Map<String, DefinitionExtractor.ProcMeta> procMeta = procParams.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> new DefinitionExtractor.ProcMeta(true, Collections.emptyList(), "unknown", 0, e.getValue(), Collections.emptyMap())));


        return new ProgramMetadata(
                artifact.programId(),
                artifact.machineCodeLayout(),
                initialObjects,
                sourceMap,
                artifact.registerAliasMap(),
                artifact.callSiteBindings(),
                artifact.relativeCoordToLinearAddress(),
                artifact.linearAddressToCoord(),
                artifact.labelAddressToName(),
                procMeta
        );
    }
}
