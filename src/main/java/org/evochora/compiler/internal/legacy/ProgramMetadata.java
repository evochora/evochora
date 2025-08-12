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
        Map<List<Integer>, Integer> relativeCoordToLinearAddress,
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

        /**
         * TODO: [Phase 4] Veraltetes Feld. Wird nur noch von der Legacy-UI (FooterController)
         *  verwendet. Sollte entfernt werden, sobald die UI auf ein neues Debug-Info-System umgestellt ist,
         *  das direkt mit dem ProgramArtifact arbeitet.
         */
        procMetaMap = Collections.unmodifiableMap(procMetaMap);
    }

    /**
     * TODO: [Phase 2] Temporäre Brückenmethode, um das alte Metadaten-Objekt aus dem neuen
     *  Artefakt zu erstellen. Wird entfernt, wenn die Runtime nur noch das Artefakt verwendet.
     */
    public static ProgramMetadata fromArtifact(ProgramArtifact artifact) {
        // Konvertiere die sauberen API-Objekte zurück in die internen Legacy-Typen
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

        // Das Register-Map ist im Artefakt nicht enthalten, wir übergeben eine leere Map.
        // Das ist okay, da es nur für das Debugging im alten System verwendet wurde.
        Map<String, Integer> emptyRegisterMap = Collections.emptyMap();

        return new ProgramMetadata(
                artifact.programId(),
                artifact.machineCodeLayout(),
                initialObjects,
                sourceMap,
                emptyRegisterMap,
                artifact.callSiteBindings(),
                artifact.relativeCoordToLinearAddress(),
                artifact.linearAddressToCoord(),
                artifact.labelAddressToName(),
                Collections.emptyMap() // procMetaMap
        );
    }
}