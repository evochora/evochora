package org.evochora.assembler;

import org.evochora.Messages;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodeExpander {
    private final String programName;
    private final Map<String, DefinitionExtractor.RoutineDefinition> routineMap;
    private final Map<String, DefinitionExtractor.MacroDefinition> macroMap;
    private final Random random = new Random();

    private final Set<String> importedProcs = new HashSet<>();
    private final Map<String, String> importAliasToProcName = new HashMap<>();
    private final List<AnnotatedLine> deferredImportAliasLines = new ArrayList<>();
    private final Map<String, String> includeSignaturePrimaryAlias = new HashMap<>();

    public CodeExpander(String programName, Map<String, DefinitionExtractor.RoutineDefinition> routineMap, Map<String, DefinitionExtractor.MacroDefinition> macroMap) {
        this.programName = programName;
        this.routineMap = routineMap;
        this.macroMap = macroMap;
    }

    public List<AnnotatedLine> expand(List<AnnotatedLine> initialCode) {
        List<AnnotatedLine> expanded = expandRecursively(initialCode, new LinkedList<>());
        expanded.addAll(deferredImportAliasLines);
        return expanded;
    }

    private List<AnnotatedLine> expandRecursively(List<AnnotatedLine> codeToProcess, Deque<String> callStack) {
        if (callStack.size() > 100) {
            throw new AssemblerException(programName, "main.s", -1, Messages.get("codeExpander.maxRecursionDepth", String.join(" -> ", callStack)), "");
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
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("codeExpander.infiniteLoopInMacro", String.join(" -> ", callStack), command), line.content());
                }
                callStack.push(command);
                List<AnnotatedLine> expandedMacro = expandMacro(command, parts, line);
                expandedCode.addAll(expandRecursively(expandedMacro, callStack));
                callStack.pop();
            } else if (macroMap.containsKey(command.toUpperCase())) {
                String macroName = command.toUpperCase();
                if (callStack.contains(macroName)) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("codeExpander.infiniteLoopInMacro", String.join(" -> ", callStack), macroName), line.content());
                }
                callStack.push(macroName);
                List<AnnotatedLine> expandedMacro = expandMacro(macroName, parts, line);
                expandedCode.addAll(expandRecursively(expandedMacro, callStack));
                callStack.pop();
            } else if (command.equalsIgnoreCase(".IMPORT")) {
                if (parts.length < 4 || !parts[2].equalsIgnoreCase("AS")) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("codeExpander.invalidImportSyntax"), line.content());
                }
                String procName = parts[1].toUpperCase();
                String alias = parts[3].toUpperCase();
                importedProcs.add(procName);
                importAliasToProcName.put(alias, procName);
                deferredImportAliasLines.add(new AnnotatedLine(alias + ":", line.originalLineNumber(), line.originalFileName()));
                deferredImportAliasLines.add(new AnnotatedLine("    JMPI " + procName, line.originalLineNumber(), line.originalFileName()));
            } else if (command.equalsIgnoreCase(".INCLUDE_STRICT")) {
                if (parts.length < 5 || !parts[2].equalsIgnoreCase("AS") || !parts[4].equalsIgnoreCase("WITH")) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("codeExpander.invalidIncludeStrictSyntax"), line.content());
                }
                String routineName = parts[1].toUpperCase();
                String instanceName = parts[3].toUpperCase();
                String[] args = Arrays.copyOfRange(parts, 5, parts.length);
                String signatureKey = routineName + "|" + String.join(",", args);

                if (callStack.contains(routineName)) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("codeExpander.infiniteLoopInRoutine", String.join(" -> ", callStack), routineName), line.content());
                }
                callStack.push(routineName);
                List<AnnotatedLine> expandedRoutine = expandInclude(parts, line);
                includeSignaturePrimaryAlias.putIfAbsent(signatureKey, instanceName);
                expandedCode.addAll(expandRecursively(expandedRoutine, callStack));
                callStack.pop();
            } else if (command.equalsIgnoreCase(".INCLUDE")) {
                if (parts.length < 5 || !parts[2].equalsIgnoreCase("AS") || !parts[4].equalsIgnoreCase("WITH")) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("codeExpander.invalidIncludeSyntax"), line.content());
                }
                String routineName = parts[1].toUpperCase();
                String instanceName = parts[3].toUpperCase();
                String[] args = Arrays.copyOfRange(parts, 5, parts.length);
                String signatureKey = routineName + "|" + String.join(",", args);

                String primaryAlias = includeSignaturePrimaryAlias.get(signatureKey);
                if (primaryAlias == null) {
                    if (callStack.contains(routineName)) {
                        throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("codeExpander.infiniteLoopInRoutine", String.join(" -> ", callStack), routineName), line.content());
                    }
                    callStack.push(routineName);
                    List<AnnotatedLine> expandedRoutine = expandInclude(parts, line);
                    includeSignaturePrimaryAlias.put(signatureKey, instanceName);
                    expandedCode.addAll(expandRecursively(expandedRoutine, callStack));
                    callStack.pop();
                } else {
                    List<AnnotatedLine> aliasOnly = new ArrayList<>();
                    aliasOnly.add(new AnnotatedLine(instanceName + ":", line.originalLineNumber(), line.originalFileName()));
                    aliasOnly.add(new AnnotatedLine("    JMPI " + primaryAlias, line.originalLineNumber(), line.originalFileName()));
                    expandedCode.addAll(aliasOnly);
                }
            } else {
                expandedCode.add(line);
            }
        }
        return expandedCode;
    }

    private List<AnnotatedLine> expandMacro(String name, String[] parts, AnnotatedLine originalLine) {
        DefinitionExtractor.MacroDefinition macro = macroMap.get(name);
        int expected = macro.parameters().size();

        String[] args;
        if (expected == 1) {
            String codePart = originalLine.content().split("#", 2)[0].strip();
            String remainder = codePart.length() > name.length() ? codePart.substring(name.length()).strip() : "";
            args = new String[]{ remainder };
        } else {
            args = Arrays.copyOfRange(parts, 1, parts.length);
        }

        if (expected != args.length) {
            throw new AssemblerException(programName, originalLine.originalFileName(), originalLine.originalLineNumber(), Messages.get("codeExpander.wrongArgumentCountForMacro", name), originalLine.content());
        }

        List<AnnotatedLine> expanded = new ArrayList<>();
        String macroBase = name.startsWith("$") ? name.substring(1) : name;
        String prefix = macroBase.toUpperCase() + "_" + random.nextInt(9999) + "_";

        for (String bodyLine : macro.body()) {
            String expandedLine = bodyLine;
            for (int j = 0; j < args.length; j++) {
                String param = macro.parameters().get(j);
                String arg = args[j];
                expandedLine = expandedLine.replaceAll("\\b" + Pattern.quote(param) + "\\b", Matcher.quoteReplacement(arg));
            }
            expandedLine = expandedLine.replace("@@", prefix);
            expanded.add(new AnnotatedLine(expandedLine, originalLine.originalLineNumber(), macro.fileName()));
        }
        return expanded;
    }

    public Set<String> getImportedProcs() { return Collections.unmodifiableSet(importedProcs); }
    public Map<String, String> getImportAliasToProcName() { return Collections.unmodifiableMap(importAliasToProcName); }

    private List<AnnotatedLine> expandInclude(String[] parts, AnnotatedLine originalLine) {
        if (parts.length < 5 || !parts[2].equalsIgnoreCase("AS") || !parts[4].equalsIgnoreCase("WITH")) {
            throw new AssemblerException(programName, originalLine.originalFileName(), originalLine.originalLineNumber(), Messages.get("codeExpander.invalidIncludeSyntax"), originalLine.content());
        }
        String routineName = parts[1].toUpperCase();
        String instanceName = parts[3].toUpperCase();

        DefinitionExtractor.RoutineDefinition routine = routineMap.get(routineName);
        if (routine == null) {
            throw new AssemblerException(programName, originalLine.originalFileName(), originalLine.originalLineNumber(), Messages.get("codeExpander.unknownRoutine", routineName), originalLine.content());
        }

        String[] args = Arrays.copyOfRange(parts, 5, parts.length);
        if (routine.parameters().size() != args.length) {
            throw new AssemblerException(programName, originalLine.originalFileName(), originalLine.originalLineNumber(), Messages.get("codeExpander.wrongArgumentCountForRoutine", routineName, routine.parameters().size(), args.length), originalLine.content());
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