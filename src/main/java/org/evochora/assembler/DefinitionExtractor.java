package org.evochora.assembler;

import org.evochora.Messages;
import org.evochora.assembler.directives.IBlockDirectiveHandler;
import org.evochora.assembler.directives.MacroDirectiveHandler;
import org.evochora.assembler.directives.ProcDirectiveHandler;
import org.evochora.assembler.directives.RoutineDirectiveHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DefinitionExtractor {
    private final String programName;
    private final Map<String, RoutineDefinition> routineMap = new HashMap<>();
    private final Map<String, MacroDefinition> macroMap = new HashMap<>();
    private final Map<String, String> defineMap = new HashMap<>();
    private final Map<String, ProcMeta> procMetaMap = new HashMap<>();
    private final List<AnnotatedLine> mainCode = new ArrayList<>();
    // Defer PROC bodies to ensure entry starts at caller code
    private final List<AnnotatedLine> deferredProcBodies = new ArrayList<>();

    // Records bleiben unverändert
    public record RoutineDefinition(String name, List<String> parameters, List<String> body, String fileName) {}
    public record MacroDefinition(String name, List<String> parameters, List<String> body, String fileName) {}
    public record ProcMeta(boolean exported, List<String> requires, String fileName, int lineNumber,
                           List<String> formalParams, Map<String, Integer> pregAliases) {}

    public DefinitionExtractor(String programName) {
        this.programName = programName;
    }

    public List<AnnotatedLine> extractFrom(List<AnnotatedLine> allLines) {
        IBlockDirectiveHandler activeBlockHandler = null;
        String expectedEndTag = null;
        AnnotatedLine blockStartLine = null;

        for (AnnotatedLine line : allLines) {
            String strippedLine = line.content().split("#", 2)[0].strip();
            if (strippedLine.isEmpty()) {
                if (activeBlockHandler != null) {
                    activeBlockHandler.processLine(line);
                } else {
                    mainCode.add(line);
                }
                continue;
            }
            String[] parts = strippedLine.split("\\s+");
            String directive = parts[0].toUpperCase();

            if (activeBlockHandler != null) {
                if (directive.equals(expectedEndTag)) {
                    activeBlockHandler.endBlock(this);
                    activeBlockHandler = null;
                    expectedEndTag = null;
                } else {
                    activeBlockHandler.processLine(line);
                }
            } else {
                switch (directive) {
                    case ".PROC" -> {
                        activeBlockHandler = new ProcDirectiveHandler(programName);
                        activeBlockHandler.startBlock(line);
                        expectedEndTag = ".ENDP";
                        blockStartLine = line;
                    }
                    case ".MACRO" -> {
                        activeBlockHandler = new MacroDirectiveHandler(programName);
                        activeBlockHandler.startBlock(line);
                        expectedEndTag = ".ENDM";
                        blockStartLine = line;
                    }
                    case ".ROUTINE" -> {
                        activeBlockHandler = new RoutineDirectiveHandler(programName);
                        activeBlockHandler.startBlock(line);
                        expectedEndTag = ".ENDR";
                        blockStartLine = line;
                    }
                    case ".DEFINE" -> {
                        if (parts.length != 3) throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.defineArguments"), line.content());
                        defineMap.put(parts[1].toUpperCase(), parts[2]);
                    }
                    case ".PREG" -> {
                        // .PREG is only valid inside a .PROC block
                        throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), "Invalid .PREG outside .PROC", line.content());
                    }
                    case ".ENDP", ".ENDM", ".ENDR" -> {
                        throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.unexpectedDirectiveOutsideBlock", directive), line.content());
                    }
                    default -> mainCode.add(line);
                }
            }
        }

        if (activeBlockHandler != null) {
            String blockName = blockStartLine.content().strip().split("\\s+")[0];
            throw new AssemblerException(programName, blockStartLine.originalFileName(), blockStartLine.originalLineNumber(), Messages.get("definitionExtractor.blockNotClosed", blockName), blockStartLine.content());
        }

        // Append deferred PROC bodies after the rest of the code
        mainCode.addAll(deferredProcBodies);
        return mainCode;
    }

    // --- GETTERS ---
    public Map<String, RoutineDefinition> getRoutineMap() { return routineMap; }
    public Map<String, MacroDefinition> getMacroMap() { return macroMap; }
    public Map<String, String> getDefineMap() { return defineMap; }
    public Map<String, ProcMeta> getProcMetaMap() { return procMetaMap; }
    public List<AnnotatedLine> getMainCode() { return mainCode; }

    // Public for cross-package access from directive handlers
    public void addDeferredProcBody(List<AnnotatedLine> procBodyLines) {
        this.deferredProcBodies.addAll(procBodyLines);
    }
}