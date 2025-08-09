package org.evochora.assembler;

import org.evochora.Messages;
import org.evochora.organism.Instruction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DefinitionExtractor {
    private final String programName;
    private final Map<String, RoutineDefinition> routineMap = new HashMap<>();
    private final Map<String, MacroDefinition> macroMap = new HashMap<>();
    private final Map<String, String> defineMap = new HashMap<>();
    private final Map<String, ProcMeta> procMetaMap = new HashMap<>();

    record RoutineDefinition(String name, List<String> parameters, List<String> body, String fileName) {}
    record MacroDefinition(String name, List<String> parameters, List<String> body, String fileName) {}
    record ProcMeta(boolean exported, List<String> requires, String fileName, int lineNumber) {}

    public DefinitionExtractor(String programName) {
        this.programName = programName;
    }

    public List<AnnotatedLine> extractFrom(List<AnnotatedLine> allLines) {
        List<AnnotatedLine> mainCode = new ArrayList<>();
        String currentBlock = null;
        String blockName = null;
        List<String> blockParams = new ArrayList<>();
        List<String> blockBody = new ArrayList<>();
        boolean currentProcExported = false;
        List<String> currentProcRequires = new ArrayList<>();
        AnnotatedLine blockStartLine = null;

        for (AnnotatedLine line : allLines) {
            String strippedLine = line.content().split("#", 2)[0].strip();
            if (strippedLine.isEmpty()) {
                if (currentBlock == null) mainCode.add(line);
                else blockBody.add(line.content());
                continue;
            }
            String[] parts = strippedLine.split("\\s+");
            String directive = parts[0].toUpperCase();

            if (directive.equals(".DEFINE")) {
                if (parts.length != 3) throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.defineArguments"), line.content());
                defineMap.put(parts[1].toUpperCase(), parts[2]);
            } else if (directive.equals(".MACRO") || directive.equals(".ROUTINE") || directive.equals(".PROC")) {
                if (currentBlock != null) throw new AssemblerException(programName, blockStartLine.originalFileName(), blockStartLine.originalLineNumber(), Messages.get("definitionExtractor.nestedDefinitionsNotAllowed"), blockStartLine.content());
                if (parts.length < 2) throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.directiveNeedsName", directive), line.content());

                currentBlock = directive;
                blockName = parts[1].toUpperCase();
                blockParams = new ArrayList<>(Arrays.asList(parts).subList(2, parts.length));
                blockBody = new ArrayList<>();
                currentProcExported = false;
                currentProcRequires = new ArrayList<>();
                blockStartLine = line;

                if (directive.equals(".ROUTINE")) {
                    for (String param : blockParams) {
                        if (Instruction.getInstructionIdByName(param.toUpperCase()) != null) {
                            throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.routineParameterCollidesWithInstruction", param), line.content());
                        }
                    }
                }
            } else if (directive.equals(".ENDM") || directive.equals(".ENDR") || directive.equals(".ENDP")) {
                if (currentBlock == null) throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.unexpectedDirectiveOutsideBlock", directive), line.content());

                String prefix = getPrefixFromFileName(blockStartLine.originalFileName());

                if (currentBlock.equals(".MACRO") && directive.equals(".ENDM")) {
                    macroMap.put(blockName, new MacroDefinition(blockName, blockParams, blockBody, blockStartLine.originalFileName()));
                } else if (currentBlock.equals(".ROUTINE") && directive.equals(".ENDR")) {
                    String qualifiedName = prefix + "." + blockName;
                    routineMap.put(qualifiedName, new RoutineDefinition(qualifiedName, blockParams, blockBody, blockStartLine.originalFileName()));
                } else if (currentBlock.equals(".PROC") && directive.equals(".ENDP")) {
                    // Emit the PROC body as code labeled with the given name, record meta
                    String procLabel = blockName; // assume fully-qualified name is provided (LIB.NAME)
                    mainCode.add(new AnnotatedLine(procLabel + ":", blockStartLine.originalLineNumber(), blockStartLine.originalFileName()));
                    for (String bodyLine : blockBody) {
                        mainCode.add(new AnnotatedLine(bodyLine, blockStartLine.originalLineNumber(), blockStartLine.originalFileName()));
                    }
                    procMetaMap.put(procLabel, new ProcMeta(currentProcExported, new ArrayList<>(currentProcRequires), blockStartLine.originalFileName(), blockStartLine.originalLineNumber()));
                } else {
                    throw new AssemblerException(programName, blockStartLine.originalFileName(), blockStartLine.originalLineNumber(), Messages.get("definitionExtractor.blockClosedWithWrongEndTag", blockName), blockStartLine.content());
                }
                currentBlock = null;
                blockBody = new ArrayList<>();
            } else {
                if (currentBlock == null) {
                    mainCode.add(line);
                } else {
                    // Inside a definition block
                    if (currentBlock.equals(".PROC")) {
                        // Capture .EXPORT / .REQUIRE and do not emit as code
                        if (directive.equals(".EXPORT")) {
                            currentProcExported = true;
                        } else if (directive.equals(".REQUIRE")) {
                            if (parts.length >= 2) {
                                currentProcRequires.add(parts[1].toUpperCase());
                            }
                        } else {
                            blockBody.add(line.content());
                        }
                    } else {
                        blockBody.add(line.content());
                    }
                }
            }
        }
        if (currentBlock != null) {
            throw new AssemblerException(programName, blockStartLine.originalFileName(), blockStartLine.originalLineNumber(), Messages.get("definitionExtractor.blockNotClosed", blockName), blockStartLine.content());
        }
        return mainCode;
    }

    private String getPrefixFromFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex).toUpperCase();
        }
        return fileName.toUpperCase();
    }

    public Map<String, RoutineDefinition> getRoutineMap() { return routineMap; }
    public Map<String, MacroDefinition> getMacroMap() { return macroMap; }
    public Map<String, String> getDefineMap() { return defineMap; }
    public Map<String, ProcMeta> getProcMetaMap() { return procMetaMap; }
}