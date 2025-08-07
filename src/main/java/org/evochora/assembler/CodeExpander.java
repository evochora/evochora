package org.evochora.assembler;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodeExpander {
    private final String programName;
    private final Map<String, DefinitionExtractor.RoutineDefinition> routineMap;
    private final Map<String, DefinitionExtractor.MacroDefinition> macroMap;
    private final Random random = new Random();

    public CodeExpander(String programName, Map<String, DefinitionExtractor.RoutineDefinition> routineMap, Map<String, DefinitionExtractor.MacroDefinition> macroMap) {
        this.programName = programName;
        this.routineMap = routineMap;
        this.macroMap = macroMap;
    }

    public List<AnnotatedLine> expand(List<AnnotatedLine> initialCode) {
        return expandRecursively(initialCode, new LinkedList<>());
    }

    private List<AnnotatedLine> expandRecursively(List<AnnotatedLine> codeToProcess, Deque<String> callStack) {
        if (callStack.size() > 100) {
            throw new AssemblerException(programName, "main.s", -1, "Maximale Rekursionstiefe für Makros/Includes erreicht: " + String.join(" -> ", callStack), "");
        }

        List<AnnotatedLine> expandedCode = new ArrayList<>();
        for (AnnotatedLine line : codeToProcess) {
            String strippedLine = line.content().split("#", 2)[0].strip();
            if (strippedLine.isEmpty()) {
                expandedCode.add(line);
                continue;
            }
            String[] parts = strippedLine.split("\\s+");
            String command = parts[0];

            if (command.startsWith("$") && macroMap.containsKey(command)) {
                if (callStack.contains(command)) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), "Endlosrekursion im Makro erkannt: " + String.join(" -> ", callStack) + " -> " + command, line.content());
                }
                callStack.push(command);
                List<AnnotatedLine> expandedMacro = expandMacro(command, parts, line);
                expandedCode.addAll(expandRecursively(expandedMacro, callStack));
                callStack.pop();
            } else if (command.equalsIgnoreCase(".INCLUDE")) {
                String routineName = parts[1].toUpperCase();
                if (callStack.contains(routineName)) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), "Endlosrekursion in Routine erkannt: " + String.join(" -> ", callStack) + " -> " + routineName, line.content());
                }
                callStack.push(routineName);
                List<AnnotatedLine> expandedRoutine = expandInclude(parts, line);
                expandedCode.addAll(expandRecursively(expandedRoutine, callStack));
                callStack.pop();
            } else {
                expandedCode.add(line);
            }
        }
        return expandedCode;
    }

    private List<AnnotatedLine> expandMacro(String name, String[] parts, AnnotatedLine originalLine) {
        DefinitionExtractor.MacroDefinition macro = macroMap.get(name);
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        if (macro.parameters().size() != args.length) {
            throw new AssemblerException(programName, originalLine.originalFileName(), originalLine.originalLineNumber(), "Falsche Argumentanzahl für Makro " + name, originalLine.content());
        }

        List<AnnotatedLine> expanded = new ArrayList<>();
        String prefix = name.substring(1).toUpperCase() + "_" + random.nextInt(9999) + "_";

        for (String bodyLine : macro.body()) {
            String expandedLine = bodyLine;
            for (int j = 0; j < args.length; j++) {
                expandedLine = expandedLine.replace(macro.parameters().get(j), args[j]);
            }
            expandedLine = expandedLine.replace("@@", prefix);
            expanded.add(new AnnotatedLine(expandedLine, originalLine.originalLineNumber(), macro.fileName()));
        }
        return expanded;
    }

    private List<AnnotatedLine> expandInclude(String[] parts, AnnotatedLine originalLine) {
        if (parts.length < 5 || !parts[2].equalsIgnoreCase("AS") || !parts[4].equalsIgnoreCase("WITH")) {
            throw new AssemblerException(programName, originalLine.originalFileName(), originalLine.originalLineNumber(), "Ungültige .INCLUDE Syntax. Erwartet: .INCLUDE <routine> AS <instance> WITH <args...>", originalLine.content());
        }
        String routineName = parts[1].toUpperCase();
        String instanceName = parts[3].toUpperCase();

        DefinitionExtractor.RoutineDefinition routine = routineMap.get(routineName);
        if (routine == null) {
            throw new AssemblerException(programName, originalLine.originalFileName(), originalLine.originalLineNumber(), "Unbekannte Routine: " + routineName, originalLine.content());
        }

        String[] args = Arrays.copyOfRange(parts, 5, parts.length);
        if (routine.parameters().size() != args.length) {
            throw new AssemblerException(programName, originalLine.originalFileName(), originalLine.originalLineNumber(), "Falsche Argumentanzahl für Routine " + routineName + ". Erwartet " + routine.parameters().size() + ", aber " + args.length + " erhalten.", originalLine.content());
        }

        Map<String, String> replacements = new HashMap<>();
        for (int i = 0; i < routine.parameters().size(); i++) {
            replacements.put(routine.parameters().get(i), args[i]);
        }

        Set<String> localSymbols = new HashSet<>();
        Pattern labelPattern = Pattern.compile("^\\s*([a-zA-Z0-9_]+):");
        Pattern macroPattern = Pattern.compile("^\\s*\\.MACRO\\s+(\\$[a-zA-Z0-9_]+)");

        for(String bodyLine : routine.body()) {
            Matcher labelMatcher = labelPattern.matcher(bodyLine.strip());
            if(labelMatcher.find()) localSymbols.add(labelMatcher.group(1));

            Matcher macroMatcher = macroPattern.matcher(bodyLine.strip());
            if(macroMatcher.find()) localSymbols.add(macroMatcher.group(1));
        }

        List<AnnotatedLine> expanded = new ArrayList<>();
        expanded.add(new AnnotatedLine(instanceName + ":", originalLine.originalLineNumber(), originalLine.originalFileName()));

        for(String bodyLine : routine.body()) {
            String processedLine = bodyLine;
            for(Map.Entry<String, String> entry : replacements.entrySet()) {
                processedLine = processedLine.replaceAll("\\b" + entry.getKey() + "\\b", entry.getValue());
            }
            for(String symbol : localSymbols) {
                processedLine = processedLine.replaceAll("\\b" + Pattern.quote(symbol) + "\\b", instanceName + "_" + symbol);
            }
            expanded.add(new AnnotatedLine(processedLine, originalLine.originalLineNumber(), routine.fileName()));
        }
        return expanded;
    }
}