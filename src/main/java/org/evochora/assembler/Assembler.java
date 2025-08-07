package org.evochora.assembler;

import org.evochora.Config;
import org.evochora.organism.Instruction;
import org.evochora.world.Symbol;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Assembler {

    // Interne Datenstrukturen
    private record MacroDefinition(List<String> parameters, List<String> body) {}
    private record RoutineDefinition(String name, List<String> parameters, List<String> body) {}
    private record AnnotatedLine(String content, int originalLineNumber) {}
    private record VectorRequest(int linearAddress, String labelName, int registerId) {}
    private record Placeholder(int linearAddress, String value, int originalLineNumber, String lineContent) {}

    private String programName;
    private boolean isDebugMode;

    // Zustandsvariablen für die Assemblierung
    private Map<String, MacroDefinition> macroMap;
    private Map<String, RoutineDefinition> routineMap;
    private Map<int[], Integer> machineCodeLayout;
    private Map<int[], Symbol> initialWorldObjects;
    private Map<String, Integer> registerMap;
    private Map<Integer, String> registerIdToName;
    private Map<String, Integer> labelMap;
    private Map<Integer, String> labelAddressToName;
    private Map<Integer, int[]> linearAddressToRelativeCoord;
    private Map<List<Integer>, Integer> relativeCoordToLinearAddress;
    private List<Placeholder> jumpPlaceholderLocations;
    private List<VectorRequest> vectorPlaceholderLocations;

    /**
     * Hauptmethode, die den gesamten Assemblierungsprozess steuert.
     */
    public ProgramMetadata assemble(String assemblyCode, String programName, boolean isDebugMode) {
        initializeState(programName, isDebugMode);

        List<AnnotatedLine> allLines = new ArrayList<>();
        int lineNum = 1;
        for (String line : assemblyCode.split("\\r?\\n")) {
            allLines.add(new AnnotatedLine(line, lineNum++));
        }

        // Phase 1: Extrahiere alle Definitionen (.ROUTINE, .MACRO)
        List<AnnotatedLine> mainCode = extractDefinitions(allLines);

        // Phase 2: Expandiere rekursiv alle .INCLUDE und Makro-Aufrufe im Hauptcode
        List<AnnotatedLine> processedLines = expandCode(mainCode);

        // Phase 3: Führe die klassischen Assembler-Durchläufe durch
        performFirstPass(processedLines);
        performSecondPass(processedLines);

        // Phase 4: Löse Sprung- und Vektor-Platzhalter auf
        resolveJumpPlaceholders(processedLines);
        resolveVectorPlaceholders(processedLines);

        String programId = generateProgramId();

        return new ProgramMetadata(programId, machineCodeLayout, initialWorldObjects, null, registerMap,
                registerIdToName, labelMap, labelAddressToName, linearAddressToRelativeCoord, relativeCoordToLinearAddress);
    }

    private void initializeState(String programName, boolean isDebugMode) {
        this.programName = programName;
        this.isDebugMode = isDebugMode;
        this.macroMap = new HashMap<>();
        this.routineMap = new HashMap<>();
        this.machineCodeLayout = new LinkedHashMap<>();
        this.initialWorldObjects = new HashMap<>();
        this.registerMap = new HashMap<>();
        this.registerIdToName = new HashMap<>();
        this.labelMap = new HashMap<>();
        this.labelAddressToName = new LinkedHashMap<>();
        this.linearAddressToRelativeCoord = new LinkedHashMap<>();
        this.relativeCoordToLinearAddress = new LinkedHashMap<>();
        this.jumpPlaceholderLocations = new ArrayList<>();
        this.vectorPlaceholderLocations = new ArrayList<>();
    }

    /**
     * Scannt den gesamten Code, extrahiert alle .ROUTINE und .MACRO Blöcke
     * und gibt den Code zurück, der übrig bleibt (das Hauptprogramm).
     */
    private List<AnnotatedLine> extractDefinitions(List<AnnotatedLine> allLines) {
        List<AnnotatedLine> mainCode = new ArrayList<>();
        String currentBlock = null;
        String blockName = null;
        List<String> blockParams = new ArrayList<>();
        List<String> blockBody = new ArrayList<>();
        int blockStartLine = -1;

        for (AnnotatedLine line : allLines) {
            String strippedLine = line.content().split("#", 2)[0].strip();
            if (strippedLine.isEmpty()) {
                if (currentBlock == null) mainCode.add(line);
                else blockBody.add(line.content());
                continue;
            }
            String[] parts = strippedLine.split("\\s+");
            String directive = parts[0].toUpperCase();

            if (directive.equals(".MACRO") || directive.equals(".ROUTINE")) {
                if (currentBlock != null) throw new AssemblerException(programName, line.originalLineNumber(), "Verschachtelte Definitionen sind nicht erlaubt.", line.content());
                if (parts.length < 2) throw new AssemblerException(programName, line.originalLineNumber(), directive + " benötigt einen Namen.", line.content());
                currentBlock = directive;
                blockName = parts[1].toUpperCase();
                blockParams = new ArrayList<>(Arrays.asList(parts).subList(2, parts.length));
                blockStartLine = line.originalLineNumber();

                if (directive.equals(".ROUTINE")) {
                    for (String param : blockParams) {
                        if (Instruction.getInstructionIdByName(param.toUpperCase()) != null) {
                            throw new AssemblerException(programName, line.originalLineNumber(), "Routinen-Parameter '" + param + "' kollidiert mit einem Befehl.", line.content());
                        }
                    }
                }
            } else if (directive.equals(".ENDM") || directive.equals(".ENDR")) {
                if (currentBlock == null) throw new AssemblerException(programName, line.originalLineNumber(), "Unerwartetes " + directive, line.content());

                if (currentBlock.equals(".MACRO") && directive.equals(".ENDM")) {
                    macroMap.put(blockName, new MacroDefinition(blockParams, blockBody));
                } else if (currentBlock.equals(".ROUTINE") && directive.equals(".ENDR")) {
                    for (String bodyLine : blockBody) {
                        String cleanBodyLine = bodyLine.split("#")[0].strip().toUpperCase();
                        if (cleanBodyLine.startsWith(".ORG") || cleanBodyLine.startsWith(".DIR")) {
                            // Erlaubt für lokale Gültigkeit
                        }
                    }
                    routineMap.put(blockName, new RoutineDefinition(blockName, blockParams, blockBody));
                } else {
                    throw new AssemblerException(programName, blockStartLine, "Block '" + blockName + "' wurde mit dem falschen End-Tag geschlossen. Erwartet " + (currentBlock.equals(".MACRO") ? ".ENDM" : ".ENDR"), line.content());
                }

                currentBlock = null;
                blockBody = new ArrayList<>();
            } else {
                if (currentBlock == null) {
                    mainCode.add(line);
                } else {
                    blockBody.add(line.content());
                }
            }
        }
        if (currentBlock != null) {
            throw new AssemblerException(programName, blockStartLine, "Block '" + blockName + "' wurde nicht mit " + (currentBlock.equals(".MACRO") ? ".ENDM" : ".ENDR") + " geschlossen.", "");
        }
        return mainCode;
    }

    /**
     * Nimmt den Hauptcode und expandiert rekursiv alle .INCLUDE und Makro-Aufrufe.
     */
    private List<AnnotatedLine> expandCode(List<AnnotatedLine> initialCode) {
        List<AnnotatedLine> codeToProcess = initialCode;
        for (int i = 0; i < 100; i++) { // Sicherheitslimit gegen Endlosschleifen
            List<AnnotatedLine> nextLines = new ArrayList<>();
            boolean changed = false;
            for (AnnotatedLine line : codeToProcess) {
                String strippedLine = line.content().split("#", 2)[0].strip();
                if (strippedLine.isEmpty()) {
                    nextLines.add(line);
                    continue;
                }
                String[] parts = strippedLine.split("\\s+");
                String command = parts[0];

                if (command.startsWith("$") && macroMap.containsKey(command)) {
                    nextLines.addAll(expandMacro(command, parts, line));
                    changed = true;
                } else if (command.equalsIgnoreCase(".INCLUDE")) {
                    nextLines.addAll(expandInclude(parts, line));
                    changed = true;
                } else {
                    nextLines.add(line);
                }
            }
            codeToProcess = nextLines;
            if (!changed) {
                return codeToProcess;
            }
        }
        throw new AssemblerException(programName, -1, "Maximale Rekursionstiefe für Makros/Includes erreicht. Mögliche Endlosschleife.", "");
    }

    private List<AnnotatedLine> expandMacro(String name, String[] parts, AnnotatedLine originalLine) {
        MacroDefinition macro = macroMap.get(name);
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        if (macro.parameters().size() != args.length) {
            throw new AssemblerException(programName, originalLine.originalLineNumber(), "Falsche Argumentanzahl für Makro " + name, originalLine.content());
        }

        List<AnnotatedLine> expanded = new ArrayList<>();
        String prefix = name.substring(1).toUpperCase() + "_" + (new Random().nextInt(9999)) + "_";

        for (String bodyLine : macro.body()) {
            String expandedLine = bodyLine;
            for (int j = 0; j < args.length; j++) {
                expandedLine = expandedLine.replace(macro.parameters().get(j), args[j]);
            }
            expandedLine = expandedLine.replace("@@", prefix);
            expanded.add(new AnnotatedLine(expandedLine, originalLine.originalLineNumber()));
        }
        return expanded;
    }

    private List<AnnotatedLine> expandInclude(String[] parts, AnnotatedLine originalLine) {
        // Syntax: .INCLUDE <RoutinenName> AS <InstanzName> WITH <Argument1> ...
        if (parts.length < 5 || !parts[2].equalsIgnoreCase("AS") || !parts[4].equalsIgnoreCase("WITH")) {
            throw new AssemblerException(programName, originalLine.originalLineNumber(), "Ungültige .INCLUDE Syntax. Erwartet: .INCLUDE <routine> AS <instance> WITH <args...>", originalLine.content());
        }
        String routineName = parts[1].toUpperCase();
        String instanceName = parts[3].toUpperCase();

        RoutineDefinition routine = routineMap.get(routineName);
        if (routine == null) {
            throw new AssemblerException(programName, originalLine.originalLineNumber(), "Unbekannte Routine: " + routineName, originalLine.content());
        }

        String[] args = Arrays.copyOfRange(parts, 5, parts.length);
        if (routine.parameters().size() != args.length) {
            throw new AssemblerException(programName, originalLine.originalLineNumber(), "Falsche Argumentanzahl für Routine " + routineName + ". Erwartet " + routine.parameters().size() + ", aber " + args.length + " erhalten.", originalLine.content());
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
        expanded.add(new AnnotatedLine(instanceName + ":", originalLine.originalLineNumber()));

        for(String bodyLine : routine.body()) {
            String processedLine = bodyLine;
            for(Map.Entry<String, String> entry : replacements.entrySet()) {
                processedLine = processedLine.replaceAll("\\b" + entry.getKey() + "\\b", entry.getValue());
            }
            for(String symbol : localSymbols) {
                processedLine = processedLine.replaceAll("\\b" + Pattern.quote(symbol) + "\\b", instanceName + "_" + symbol);
            }
            expanded.add(new AnnotatedLine(processedLine, originalLine.originalLineNumber()));
        }

        return expanded;
    }

    private void performFirstPass(List<AnnotatedLine> annotatedLines) {
        int linearAddress = 0;
        int[] currentDv = {1, 0};
        int[] currentPos = new int[Config.WORLD_DIMENSIONS];

        for (AnnotatedLine annotatedLine : annotatedLines) {
            String strippedLine = annotatedLine.content().split("#", 2)[0].strip();
            if (strippedLine.isEmpty()) continue;

            String[] parts = strippedLine.split("\\s+");
            String directive = parts[0].toUpperCase();

            if (directive.startsWith(".")) {
                if (directive.equals(".DIR")) {
                    currentDv = Arrays.stream(parts[1].split("\\|")).map(String::strip).mapToInt(Integer::parseInt).toArray();
                } else if (directive.equals(".ORG")) {
                    currentPos = Arrays.stream(parts[1].split("\\|")).map(String::strip).mapToInt(Integer::parseInt).toArray();
                }
                processDirective(directive, parts, annotatedLine);
            } else if (strippedLine.endsWith(":")) {
                processLabel(strippedLine, linearAddress, annotatedLine);
            } else {
                Integer opcodeId = Instruction.getInstructionIdByName(directive);
                if (opcodeId == null) {
                    throw new AssemblerException(programName, annotatedLine.originalLineNumber(), "Unbekannter Befehl: " + directive, annotatedLine.content());
                }
                int instructionLength = Instruction.getInstructionLengthById(Config.TYPE_CODE | opcodeId);
                for (int j = 0; j < instructionLength; j++) {
                    linearAddressToRelativeCoord.put(linearAddress, Arrays.copyOf(currentPos, currentPos.length));
                    relativeCoordToLinearAddress.put(Arrays.stream(currentPos).boxed().collect(Collectors.toList()), linearAddress);
                    linearAddress++;
                    for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) {
                        currentPos[d] += currentDv[d];
                    }
                }
            }
        }
    }

    private void processDirective(String directive, String[] parts, AnnotatedLine line) {
        switch (directive) {
            case ".PLACE" -> {
                String[] typeAndValue = parts[1].split(":");
                int type = getTypeFromString(typeAndValue[0], line);
                int value = Integer.parseInt(typeAndValue[1]);
                int[] relativePos = Arrays.stream(parts[2].split("\\|")).map(String::strip).mapToInt(Integer::parseInt).toArray();
                initialWorldObjects.put(relativePos, new Symbol(type, value));
            }
            case ".REG" -> {
                registerMap.put(parts[1].toUpperCase(), Integer.parseInt(parts[2]));
                registerIdToName.put(Integer.parseInt(parts[2]), parts[1].toUpperCase());
            }
        }
    }

    private void processLabel(String strippedLine, int linearAddress, AnnotatedLine line) {
        String label = strippedLine.substring(0, strippedLine.length() - 1).toUpperCase();
        if (labelMap.containsKey(label)) {
            throw new AssemblerException(programName, line.originalLineNumber(), "Label '" + label + "' wurde mehrfach vergeben.", line.content());
        }
        if (Instruction.getInstructionIdByName(label) != null) {
            throw new AssemblerException(programName, line.originalLineNumber(), "Label '" + label + "' hat denselben Namen wie ein Befehl.", line.content());
        }
        labelMap.put(label, linearAddress);
        labelAddressToName.put(linearAddress, label);
    }

    private void performSecondPass(List<AnnotatedLine> annotatedLines) {
        int linearAddress = 0;
        int[] currentPos = new int[Config.WORLD_DIMENSIONS];
        int[] currentDv = {1, 0};

        Deque<int[]> dvStack = new ArrayDeque<>();
        Deque<int[]> orgStack = new ArrayDeque<>();

        for (AnnotatedLine annotatedLine : annotatedLines) {
            String strippedLine = annotatedLine.content().split("#", 2)[0].strip();
            if (strippedLine.isEmpty() || strippedLine.endsWith(":") || strippedLine.startsWith(".")) {
                String[] parts = strippedLine.split("\\s+");
                String directive = parts[0].toUpperCase();

                if (directive.equals(".ORG")) {
                    currentPos = Arrays.stream(parts[1].split("\\|")).mapToInt(Integer::parseInt).toArray();
                } else if (directive.equals(".DIR")) {
                    currentDv = Arrays.stream(parts[1].split("\\|")).mapToInt(Integer::parseInt).toArray();
                }
                continue;
            }

            linearAddress = processInstruction(annotatedLine, linearAddress, currentPos, currentDv);

            String[] parts = strippedLine.split("\\s+");
            Integer opcodeId = Instruction.getInstructionIdByName(parts[0].toUpperCase());
            if(opcodeId != null){
                int instructionLength = Instruction.getInstructionLengthById(Config.TYPE_CODE | opcodeId);
                for (int i = 0; i < instructionLength; i++) {
                    for(int d=0; d<Config.WORLD_DIMENSIONS; d++) currentPos[d] += currentDv[d];
                }
            }
        }
    }

    private int processInstruction(AnnotatedLine annotatedLine, int address, int[] pos, int[] dv) {
        String strippedLine = annotatedLine.content().split("#", 2)[0].strip();
        String[] parts = strippedLine.split("\\s+", 2);
        String command = parts[0].toUpperCase();
        String[] args = (parts.length > 1) ? parts[1].split("\\s+") : new String[0];

        try {
            Integer opcodeId = Instruction.getInstructionIdByName(command);
            if(opcodeId == null) {
                throw new AssemblerException(programName, annotatedLine.originalLineNumber(), "Unbekannter Befehl oder nicht expandiertes Makro: " + command, annotatedLine.content());
            }

            AssemblerOutput output = Instruction.getAssemblerById(Config.TYPE_CODE | opcodeId).apply(args, registerMap, labelMap);

            int instructionLength = Instruction.getInstructionLengthById(Config.TYPE_CODE | opcodeId);
            machineCodeLayout.put(Arrays.copyOf(pos, pos.length), Config.TYPE_CODE | opcodeId);
            int[] argPos = Arrays.copyOf(pos, pos.length);
            for(int d=0; d<Config.WORLD_DIMENSIONS; d++) argPos[d] += dv[d];

            switch (output) {
                case AssemblerOutput.CodeSequence code -> {
                    for (int val : code.machineCode()) {
                        machineCodeLayout.put(Arrays.copyOf(argPos, argPos.length), val);
                        for(int d=0; d<Config.WORLD_DIMENSIONS; d++) argPos[d] += dv[d];
                    }
                }
                case AssemblerOutput.JumpInstructionRequest req -> {
                    jumpPlaceholderLocations.add(new Placeholder(address, req.labelName(), annotatedLine.originalLineNumber(), annotatedLine.content()));
                    fillWithPlaceholders(argPos, dv, instructionLength - 1);
                }
                case AssemblerOutput.LabelToVectorRequest req -> {
                    vectorPlaceholderLocations.add(new VectorRequest(address, req.labelName(), req.registerId()));
                    machineCodeLayout.put(Arrays.copyOf(argPos, argPos.length), new Symbol(Config.TYPE_DATA, req.registerId()).toInt());
                    int[] nextArgPos = Arrays.copyOf(argPos, argPos.length);
                    for(int d=0; d<Config.WORLD_DIMENSIONS; d++) nextArgPos[d] += dv[d];
                    fillWithPlaceholders(nextArgPos, dv, instructionLength - 2);
                }
                default -> {}
            }
            return address + instructionLength;
        } catch (IllegalArgumentException e) {
            throw new AssemblerException(programName, annotatedLine.originalLineNumber(), e.getMessage(), annotatedLine.content());
        }
    }

    private void fillWithPlaceholders(int[] startPos, int[] dv, int count) {
        int[] currentPos = Arrays.copyOf(startPos, startPos.length);
        for (int i = 0; i < count; i++) {
            machineCodeLayout.put(Arrays.copyOf(currentPos, currentPos.length), 0);
            for(int d=0; d<Config.WORLD_DIMENSIONS; d++) currentPos[d] += dv[d];
        }
    }

    private void resolveJumpPlaceholders(List<AnnotatedLine> annotatedLines) {
        for (Placeholder placeholder : jumpPlaceholderLocations) {
            int jumpOpcodeAddress = placeholder.linearAddress();
            String targetLabel = placeholder.value();

            int[] jumpOpcodeCoord = linearAddressToRelativeCoord.get(jumpOpcodeAddress);
            Integer targetLabelAddress = labelMap.get(targetLabel.toUpperCase());
            if (targetLabelAddress == null) {
                throw new AssemblerException(programName, placeholder.originalLineNumber(), "Unbekanntes Label für Sprungbefehl: " + targetLabel, placeholder.lineContent());
            }
            int[] targetCoord = linearAddressToRelativeCoord.get(targetLabelAddress);

            int[] delta = new int[Config.WORLD_DIMENSIONS];
            for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                delta[i] = targetCoord[i] - jumpOpcodeCoord[i];
            }

            int[] dvAtJump = getDvAtLinearAddress(jumpOpcodeAddress, annotatedLines);
            int[] argPos = Arrays.copyOf(jumpOpcodeCoord, jumpOpcodeCoord.length);
            for(int d=0; d<Config.WORLD_DIMENSIONS; d++) argPos[d] += dvAtJump[d];

            for (int component : delta) {
                machineCodeLayout.put(Arrays.copyOf(argPos, argPos.length), new Symbol(Config.TYPE_DATA, component).toInt());
                for(int d=0; d<Config.WORLD_DIMENSIONS; d++) argPos[d] += dvAtJump[d];
            }
        }
    }

    private void resolveVectorPlaceholders(List<AnnotatedLine> annotatedLines) {
        for (VectorRequest request : vectorPlaceholderLocations) {
            int opcodeAddress = request.linearAddress();
            String targetLabel = request.labelName();

            int[] opcodeCoord = linearAddressToRelativeCoord.get(opcodeAddress);
            Integer targetLabelAddress = labelMap.get(targetLabel.toUpperCase());
            if (targetLabelAddress == null) {
                int lineNumber = findLineNumberForAddress(opcodeAddress, annotatedLines);
                throw new AssemblerException(programName, lineNumber, "Unbekanntes Label für Vektor-Zuweisung: " + targetLabel, findLineContentByNumber(lineNumber, annotatedLines));
            }

            int[] targetCoord = linearAddressToRelativeCoord.get(targetLabelAddress);

            int[] dvAtOpcode = getDvAtLinearAddress(opcodeAddress, annotatedLines);
            int[] argPos = Arrays.copyOf(opcodeCoord, opcodeCoord.length);
            for(int d=0; d<Config.WORLD_DIMENSIONS; d++) argPos[d] += dvAtOpcode[d]; // zum Register-Argument
            for(int d=0; d<Config.WORLD_DIMENSIONS; d++) argPos[d] += dvAtOpcode[d]; // zur ersten Vektor-Komponente

            for (int component : targetCoord) {
                machineCodeLayout.put(Arrays.copyOf(argPos, argPos.length), new Symbol(Config.TYPE_DATA, component).toInt());
                for(int d=0; d<Config.WORLD_DIMENSIONS; d++) argPos[d] += dvAtOpcode[d];
            }
        }
    }

    private int getTypeFromString(String typeName, AnnotatedLine line) {
        return switch (typeName.toUpperCase()) {
            case "CODE" -> Config.TYPE_CODE;
            case "DATA" -> Config.TYPE_DATA;
            case "ENERGY" -> Config.TYPE_ENERGY;
            case "STRUCTURE" -> Config.TYPE_STRUCTURE;
            default -> throw new AssemblerException(programName, line.originalLineNumber(), "Unbekannter Typ: " + typeName, line.content());
        };
    }

    private String findLineContentByNumber(int lineNumber, List<AnnotatedLine> annotatedLines) {
        return annotatedLines.stream()
                .filter(l -> l.originalLineNumber() == lineNumber)
                .findFirst()
                .map(AnnotatedLine::content)
                .orElse("");
    }

    private int findLineNumberForAddress(int targetAddress, List<AnnotatedLine> annotatedLines) {
        int currentAddress = 0;
        for (AnnotatedLine line : annotatedLines) {
            if(currentAddress == targetAddress) return line.originalLineNumber();
            String strippedLine = line.content().split("#")[0].strip();
            if (strippedLine.isEmpty() || strippedLine.startsWith(".") || strippedLine.endsWith(":")) continue;
            Integer opcodeId = Instruction.getInstructionIdByName(strippedLine.split("\\s+")[0].toUpperCase());
            if(opcodeId != null) currentAddress += Instruction.getInstructionLengthById(Config.TYPE_CODE | opcodeId);
        }
        return -1;
    }

    private int[] getDvAtLinearAddress(int targetAddress, List<AnnotatedLine> annotatedLines) {
        int[] dv = {1, 0};
        int currentAddress = 0;
        for (AnnotatedLine annotatedLine : annotatedLines) {
            if (currentAddress > targetAddress) break;
            String strippedLine = annotatedLine.content().split("#")[0].strip();
            if(strippedLine.toUpperCase().startsWith(".DIR")) {
                dv = Arrays.stream(strippedLine.split("\\s+")[1].split("\\|")).mapToInt(Integer::parseInt).toArray();
            }
            if (strippedLine.isEmpty() || strippedLine.startsWith(".") || strippedLine.endsWith(":")) continue;
            Integer opcodeId = Instruction.getInstructionIdByName(strippedLine.split("\\s+")[0].toUpperCase());
            if(opcodeId != null) currentAddress += Instruction.getInstructionLengthById(Config.TYPE_CODE | opcodeId);
        }
        return dv;
    }

    private String generateProgramId() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Integer> codeForHash = this.machineCodeLayout.values().stream().toList();
            ByteBuffer buffer = ByteBuffer.allocate(codeForHash.size() * 4);
            for (int code : codeForHash) buffer.putInt(code);
            byte[] hashBytes = digest.digest(buffer.array());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hashBytes, 12));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 nicht gefunden", e);
        }
    }
}