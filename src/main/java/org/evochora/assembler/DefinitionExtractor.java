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
    // Extended ProcMeta: keep existing components (exported, requires, fileName, lineNumber) and append new ones to preserve existing accessors like requires()
    record ProcMeta(boolean exported, List<String> requires, String fileName, int lineNumber,
                    List<String> formalParams, Map<String, Integer> pregAliases) {}

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
        List<String> currentProcFormals = new ArrayList<>();
        Map<String, Integer> currentPregAliases = new HashMap<>();
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
                blockParams = new ArrayList<>();
                blockBody = new ArrayList<>();
                currentProcExported = false;
                currentProcRequires = new ArrayList<>();
                currentProcFormals = new ArrayList<>();
                currentPregAliases = new HashMap<>();
                blockStartLine = line;

                if (directive.equals(".PROC")) {
                    // Optional WITH formals: .PROC NAME WITH X [Y ...]
                    // parts: [0]=.PROC, [1]=NAME, [2]=WITH?, [3...]=formals
                    if (parts.length >= 3 && parts[2].equalsIgnoreCase("WITH")) {
                        if (parts.length < 4) {
                            throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.directiveNeedsName", "FORMALS"), line.content());
                        }
                        for (int i = 3; i < parts.length; i++) {
                            String formal = parts[i].toUpperCase();
                            if (formal.startsWith("%")) {
                                throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.procFormalMustNotBePercent", formal), line.content());
                            }
                            currentProcFormals.add(formal);
                        }
                    }
                } else if (directive.equals(".ROUTINE")) {
                    // Validate routine parameters don't collide with instruction names
                    for (int i = 2; i < parts.length; i++) {
                        String param = parts[i];
                        if (Instruction.getInstructionIdByName(param.toUpperCase()) != null) {
                            throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.routineParameterCollidesWithInstruction", param), line.content());
                        }
                    }
                }
            } else if (directive.equals(".ENDM") || directive.equals(".ENDR") || directive.equals(".ENDP")) {
                if (currentBlock == null) throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.unexpectedDirectiveOutsideBlock", directive), line.content());

                String prefix = getPrefixFromFileName(blockStartLine.originalFileName());

                if (currentBlock.equals(".MACRO") && directive.equals(".ENDM")) {
                    MacroDefinition def = new MacroDefinition(blockName, blockParams, blockBody, blockStartLine.originalFileName());
                    // Store macro by its raw name
                    macroMap.put(blockName, def);
                    // Also store by stripped name if it starts with '$' to support calls without '$'
                    if (blockName.startsWith("$")) {
                        String stripped = blockName.substring(1);
                        macroMap.put(stripped, def);
                    }
                } else if (currentBlock.equals(".ROUTINE") && directive.equals(".ENDR")) {
                    String qualifiedName = prefix + "." + blockName;
                    routineMap.put(qualifiedName, new RoutineDefinition(qualifiedName, blockParams, blockBody, blockStartLine.originalFileName()));
                } else if (currentBlock.equals(".PROC") && directive.equals(".ENDP")) {
                    // Append implicit RET if none present
                    boolean hasRet = blockBody.stream()
                            .map(s -> s.split("#", 2)[0].strip())
                            .anyMatch(s -> s.equalsIgnoreCase("RET"));
                    if (!hasRet) {
                        blockBody.add("RET");
                        // Warning: auto-RET appended
                        System.out.println(Messages.get("definitionExtractor.autoRetAppended",
                                blockName, blockStartLine.originalFileName(), String.valueOf(blockStartLine.originalLineNumber())));
                    }

                    // Emit the PROC body as code labeled with the given name, record extended meta
                    String procLabel = blockName; // assume fully-qualified name is provided (LIB.NAME)
                    mainCode.add(new AnnotatedLine(procLabel + ":", blockStartLine.originalLineNumber(), blockStartLine.originalFileName()));

                    // Build a simple rewrite map: formal -> %DR<i> (in declaration order)
                    Map<String, String> formalToDr = new HashMap<>();
                    for (int i = 0; i < currentProcFormals.size(); i++) {
                        formalToDr.put(currentProcFormals.get(i), "%DR" + i);
                    }

                    // Emit PROC body lines unchanged; resolution of formals/.PREG happens during second pass
                    for (String bodyLine : blockBody) {
                        mainCode.add(new AnnotatedLine(bodyLine, blockStartLine.originalLineNumber(), blockStartLine.originalFileName()));
                    }

                    procMetaMap.put(procLabel, new ProcMeta(
                            currentProcExported,
                            new ArrayList<>(currentProcRequires),
                            blockStartLine.originalFileName(),
                            blockStartLine.originalLineNumber(),
                            new ArrayList<>(currentProcFormals),
                            new HashMap<>(currentPregAliases)
                    ));
                } else {
                    throw new AssemblerException(programName, blockStartLine.originalFileName(), blockStartLine.originalLineNumber(), Messages.get("definitionExtractor.blockClosedWithWrongEndTag", blockName), blockStartLine.content());
                }
                currentBlock = null;
                blockBody = new ArrayList<>();
            } else {
                if (currentBlock == null) {
                    // Outside of any definition block
                    if (directive.equals(".PREG")) {
                        // .PREG is only valid inside a .PROC block
                        throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.pregInvalidContext"), line.content());
                    }
                    mainCode.add(line);
                } else {
                    // Inside a definition block
                    if (currentBlock.equals(".PROC")) {
                        // Handle PROC-local directives, not emitted as code
                        if (directive.equals(".EXPORT")) {
                            currentProcExported = true;
                        } else if (directive.equals(".REQUIRE")) {
                            if (parts.length >= 2) {
                                currentProcRequires.add(parts[1].toUpperCase());
                            }
                        } else if (directive.equals(".PREG")) {
                            // .PREG %NAME 0|1
                            if (parts.length != 3) {
                                throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.pregInvalidSyntax"), line.content());
                            }
                            String alias = parts[1].toUpperCase();
                            if (!alias.startsWith("%")) {
                                throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.pregNameMustStartPercent", alias), line.content());
                            }
                            int index;
                            try {
                                index = Integer.parseInt(parts[2]);
                            } catch (NumberFormatException nfe) {
                                throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.pregInvalidIndex", parts[2]), line.content());
                            }
                            if (index != 0 && index != 1) {
                                throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("definitionExtractor.pregInvalidIndex", parts[2]), line.content());
                            }
                            currentPregAliases.put(alias, index);
                        } else {
                            // Not a PROC-local directive: treat as code
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