package org.evochora.compiler.internal;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ICompiler;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.internal.legacy.*;
import org.evochora.runtime.model.Molecule;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Ein Adapter, der die neue, saubere {@link org.evochora.compiler.Compiler}-Schnittstelle implementiert,
 * indem er die Aufrufe an die alte, komplexe Legacy-Compiler-Implementierung
 * ({@link Assembler}, {@link PassManager}, etc.) delegiert.
 */
public class LegacyCompilerAdapter implements ICompiler {

    @Override
    public ProgramArtifact compile(List<String> sourceLines, String programName) throws CompilationException {
        try {
            Assembler legacyAssembler = new Assembler();
            List<AnnotatedLine> annotatedLines = sourceLines.stream()
                    .map(line -> new AnnotatedLine(line, -1, programName))
                    .collect(Collectors.toList());

            ProgramMetadata legacyMetadata = legacyAssembler.assemble(annotatedLines, programName, false);

            return convertMetadataToArtifact(legacyMetadata);

        } catch (AssemblerException e) {
            throw new CompilationException("Compilation failed: " + e.getMessage(), e);
        }
    }

    private ProgramArtifact convertMetadataToArtifact(ProgramMetadata metadata) {
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
                metadata.labelAddressToName(),
                metadata.registerMap() // Das 9. Argument, das vorher fehlte
        );
    }

    public void setVerbosity(int level) {}
}