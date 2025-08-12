package org.evochora.compiler.internal.legacy;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import org.evochora.runtime.isa.Instruction;

/**
 * The main assembler class that orchestrates the entire assembly process.
 * It takes a list of source code lines and produces a fully linked program.
 */
public class Assembler {

    /**
     * Assembles the given source code into a program.
     * The process is divided into several phases:
     * 0. File Preprocessing: Resolves all .FILE directives recursively.
     * 1. Definition Extraction: Extracts macros and routines.
     * 2. Code Expansion: Expands all macro and routine calls.
     * 3. Two-Pass Assembly: Resolves symbols and generates machine code.
     * 4. Placeholder Resolution: Links all jumps and vector references.
     *
     * @param allLines The list of annotated source code lines from the main file.
     * @param programName The name of the program being assembled.
     * @param isDebugMode A flag to enable or disable debug features.
     * @return The metadata of the assembled program.
     * @throws AssemblerException if any error occurs during assembly.
     */
    public ProgramMetadata assemble(List<AnnotatedLine> allLines, String programName, boolean isDebugMode) {

        // NEU: Phase 0 - .FILE Direktiven mit dem Preprocessor auflösen
        FilePreprocessor preprocessor = new FilePreprocessor(programName);
        List<AnnotatedLine> flattenedCode = preprocessor.process(allLines);

        // Ensure instruction registry is initialized
        Instruction.init();

        // Phase 1: Extract definitions (macros, routines) from the flattened code
        DefinitionExtractor extractor = new DefinitionExtractor(programName);
        List<AnnotatedLine> mainCode = extractor.extractFrom(flattenedCode);

        // Phase 2: Expand code (routines & macros)
        CodeExpander expander = new CodeExpander(programName, extractor.getRoutineMap(), extractor.getMacroMap());
        List<AnnotatedLine> processedCode = expander.expand(mainCode);

        // Phase 2.5: Validate .REQUIRE declarations against seen .IMPORTs
        if (!extractor.getProcMetaMap().isEmpty()) {
            Set<String> allRequires = new java.util.HashSet<>();
            extractor.getProcMetaMap().forEach((name, meta) -> allRequires.addAll(meta.requires()));
            Set<String> imported = new java.util.HashSet<>(expander.getImportedProcs());
            allRequires.removeAll(imported);
            if (!allRequires.isEmpty()) {
                throw new AssemblerException(programName, "N/A", -1, "Missing IMPORT for required PROC(s): " + String.join(", ", allRequires), "");
            }
        }

        // Phase 3 & 4: Assemble (First & Second Pass)
        PassManager passManager = new PassManager(
                programName,
                extractor.getDefineMap(),
                extractor.getProcMetaMap(),
                expander.getImportAliasToProcName()
        );
        passManager.runPasses(processedCode);

        // Phase 5: Resolve placeholders (Linking)
        PlaceholderResolver resolver = new PlaceholderResolver(
                programName,
                passManager.getMachineCodeLayout(),
                passManager.getLabelMap(),
                passManager.getLinearAddressToCoordMap()
        );
        resolver.resolve(passManager.getJumpPlaceholders(), passManager.getVectorPlaceholders());

        // Metadaten zusammenbauen und zurückgeben
        return new ProgramMetadata(
                generateProgramId(passManager.getMachineCodeLayout()),
                passManager.getMachineCodeLayout(),
                passManager.getInitialWorldObjects(),
                passManager.getSourceMap(),
                passManager.getRegisterMap(),
                passManager.getCallSiteBindings(),
                passManager.getCoordToLinearAddressMap(), // In der Definition relativeCoordToLinearAddress genannt
                passManager.getLinearAddressToCoordMap(), // In der Definition linearAddressToCoord genannt
                passManager.getLabelAddressToNameMap(),
                extractor.getProcMetaMap()
        );
    }

    private String generateProgramId(Map<int[], Integer> layout) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Integer> codeForHash = layout.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Arrays::compare))
                    .map(Map.Entry::getValue)
                    .toList();

            ByteBuffer buffer = ByteBuffer.allocate(codeForHash.size() * 4);
            for (int code : codeForHash) {
                buffer.putInt(code);
            }
            byte[] hashBytes = digest.digest(buffer.array());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hashBytes, 12));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 nicht gefunden", e);
        }
    }
}