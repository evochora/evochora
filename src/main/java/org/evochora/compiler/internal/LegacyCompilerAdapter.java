package org.evochora.compiler.internal;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.Compiler;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.internal.legacy.*;
import org.evochora.runtime.model.Molecule;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Ein Adapter, der die neue, saubere {@link Compiler}-Schnittstelle implementiert,
 * indem er die Aufrufe an die alte, komplexe Legacy-Compiler-Implementierung
  * ({@link Assembler}, {@link org.evochora.compiler.internal.legacy.PassManager}, etc.) delegiert.
  * <p>
  * TODO: [Phase 4 Refactoring] Dies ist eine Brückenklasse, die am Ende des Refactorings
  *  vollständig entfernt wird. Ihre einzige Aufgabe ist es, die alte mit der neuen Welt
  *  zu verbinden. In Phase 4 wird sie durch die finale Implementierung des neuen Compilers
  *  (basierend auf der {@code DiagnosticsEngine}) ersetzt und diese Klasse wird gelöscht.
 * <p>
 * TODO: [Phase 2] Dies ist eine Brückenklasse. Ihre Hauptverantwortung ist die
 * Übersetzung des alten {@link ProgramMetadata}-Objekts in das neue, saubere
 * {@link ProgramArtifact}. In Phase 4 wird dieser Adapter durch die Implementierung
 * des neuen Compilers ersetzt.
 */
public class LegacyCompilerAdapter implements Compiler {

    @Override
    public ProgramArtifact compile(List<String> sourceLines, String programName) throws CompilationException {
        try {
            // 1. Rufe den alten Assembler auf
            Assembler legacyAssembler = new Assembler();
            List<AnnotatedLine> annotatedLines = sourceLines.stream()
                    .map(line -> new AnnotatedLine(line, -1, programName))
                    .collect(Collectors.toList());

            ProgramMetadata legacyMetadata = legacyAssembler.assemble(annotatedLines, programName, false);

            // 2. Übersetze das Ergebnis in das neue, saubere Artefakt
            return convertMetadataToArtifact(legacyMetadata);

        } catch (AssemblerException e) {
            // Fange die alte Exception und verpacke sie in die neue API-Exception
            throw new CompilationException("Compilation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Konvertiert das alte, interne {@link ProgramMetadata}-Objekt in das neue,
     * öffentliche und saubere {@link ProgramArtifact}.
     *
     * @param metadata Das Ergebnis des Legacy-Compilers.
     * @return Ein neues, unveränderliches ProgramArtifact.
     */
    private ProgramArtifact convertMetadataToArtifact(ProgramMetadata metadata) {
        // Konvertiere die Maps in die neuen, sauberen API-Typen
        Map<int[], PlacedMolecule> initialObjects = metadata.initialWorldObjects().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new PlacedMolecule(entry.getValue().type(), entry.getValue().value())
                ));

        Map<Integer, SourceInfo> sourceMap = metadata.sourceMap().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            SourceLocation loc = entry.getValue();
                            return new SourceInfo(loc.fileName(), loc.lineNumber(), loc.lineContent());
                        }
                ));

        return new ProgramArtifact(
                metadata.programId(),
                metadata.machineCodeLayout(),
                initialObjects,
                sourceMap,
                metadata.callSiteBindings(),
                metadata.relativeCoordToLinearAddress(),
                metadata.linearAddressToCoord(),
                metadata.labelAddressToName()
        );
    }
}
