package org.evochora.assembler;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class Assembler {

    public ProgramMetadata assemble(List<AnnotatedLine> allLines, String programName, boolean isDebugMode) {

        // Phase 1: Definitionen extrahieren
        DefinitionExtractor extractor = new DefinitionExtractor(programName);
        List<AnnotatedLine> mainCode = extractor.extractFrom(allLines);

        // Phase 2: Code expandieren (Routinen & Makros)
        CodeExpander expander = new CodeExpander(programName, extractor.getRoutineMap(), extractor.getMacroMap());
        List<AnnotatedLine> processedCode = expander.expand(mainCode);

        // Phase 3 & 4: Assemblieren (First & Second Pass)
        PassManager passManager = new PassManager(programName, extractor.getDefineMap());
        passManager.runPasses(processedCode);

        // Phase 5: Platzhalter auflösen (Linking)
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
                passManager.getRegisterIdToNameMap(),
                passManager.getLabelMap(),
                passManager.getLabelAddressToNameMap(),
                passManager.getLinearAddressToCoordMap(),
                passManager.getCoordToLinearAddressMap()
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