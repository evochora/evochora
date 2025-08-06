// src/main/java/org/evochora/assembler/Assembler.java
package org.evochora.assembler;

import org.evochora.Config;
import org.evochora.organism.Instruction;
import org.evochora.world.Symbol;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

public class Assembler {

    // Interne Datenstrukturen für den Assemblierungsprozess
    private record MacroDefinition(List<String> parameters, List<String> body) {}
    private record AnnotatedLine(String content, int originalLineNumber) {}
    private record VectorRequest(int linearAddress, String labelName, int registerId) {}
    private record Placeholder(int linearAddress, String value, int originalLineNumber, String lineContent) {}


    private String programName;
    private boolean isDebugMode;
    private int macroExpansionCounter = 0;

    // Zustandsvariablen für die Assemblierung
    private Map<String, MacroDefinition> macroMap;
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

        String[] initialLines = assemblyCode.split("\\r?\\n");
        List<AnnotatedLine> expandedLines = performMacroPass(initialLines);

        performFirstPass(expandedLines);
        performSecondPass(expandedLines);

        resolveJumpPlaceholders(expandedLines);
        resolveVectorPlaceholders(expandedLines); // KORRIGIERTE METHODE WIRD HIER GERUFEN

        String programId = generateProgramId();

        return new ProgramMetadata(programId, machineCodeLayout, initialWorldObjects, null, registerMap,
                registerIdToName, labelMap, labelAddressToName, linearAddressToRelativeCoord, relativeCoordToLinearAddress);
    }

    /**
     * Initialisiert alle Zustandsvariablen für einen neuen Assemblierungs-Durchlauf.
     */
    private void initializeState(String programName, boolean isDebugMode) {
        this.programName = programName;
        this.isDebugMode = isDebugMode;
        this.macroExpansionCounter = 0;
        this.macroMap = new HashMap<>();
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

    // =========================================================================================
    // === Makro-Verarbeitung (Pass 0)
    // =========================================================================================

    private List<AnnotatedLine> performMacroPass(String[] lines) {
        List<AnnotatedLine> codeWithoutMacroDefs = extractMacroDefinitions(lines);
        if (isDebugMode && !macroMap.isEmpty()) {
            System.out.printf("[DEBUG: %s] --- Makro-Definitionen gefunden ---\n", programName);
            macroMap.forEach((name, def) -> System.out.printf("[DEBUG: %s]   - %s mit Parametern %s\n", programName, name, def.parameters()));
        }
        return expandMacrosRecursively(codeWithoutMacroDefs, new ArrayDeque<>());
    }

    private List<AnnotatedLine> extractMacroDefinitions(String[] lines) {
        List<AnnotatedLine> codeWithoutMacroDefs = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String strippedLine = line.split("#", 2)[0].strip();
            if (strippedLine.isEmpty()) {
                codeWithoutMacroDefs.add(new AnnotatedLine(line, i + 1));
                continue;
            }
            String[] parts = strippedLine.split("\\s+");
            if (parts[0].equalsIgnoreCase(".MACRO")) {
                if (parts.length < 2 || !parts[1].startsWith("$")) {
                    throw new AssemblerException(programName, i + 1, "Ungültige Makro-Definition. Erwartet: .MACRO $NAME [param1] ...", line);
                }
                String macroName = parts[1];
                List<String> params = Arrays.asList(parts).subList(2, parts.length);
                List<String> body = new ArrayList<>();
                int j = i + 1;
                while (j < lines.length && !lines[j].strip().equalsIgnoreCase(".ENDM")) {
                    body.add(lines[j]);
                    j++;
                }
                if (j == lines.length) {
                    throw new AssemblerException(programName, i + 1, "Fehlendes .ENDM für Makro-Definition '" + macroName + "'.", line);
                }
                macroMap.put(macroName, new MacroDefinition(params, body));
                i = j;
            } else {
                codeWithoutMacroDefs.add(new AnnotatedLine(line, i + 1));
            }
        }
        return codeWithoutMacroDefs;
    }

    private List<AnnotatedLine> expandMacrosRecursively(List<AnnotatedLine> lines, Deque<String> callStack) {
        List<AnnotatedLine> expandedCode = new ArrayList<>();
        for (AnnotatedLine annotatedLine : lines) {
            String strippedLine = annotatedLine.content().split("#", 2)[0].strip();
            if (strippedLine.isEmpty()) {
                expandedCode.add(annotatedLine);
                continue;
            }
            String[] parts = strippedLine.split("\\s+");
            String potentialMacroName = parts[0];
            if (potentialMacroName.startsWith("$") && macroMap.containsKey(potentialMacroName)) {
                if (callStack.contains(potentialMacroName)) {
                    String loopTrace = String.join(" -> ", callStack) + " -> " + potentialMacroName;
                    throw new AssemblerException(programName, annotatedLine.originalLineNumber(), "Endlose Makro-Rekursion erkannt: " + loopTrace, annotatedLine.content());
                }
                callStack.push(potentialMacroName);
                this.macroExpansionCounter++;
                MacroDefinition macro = macroMap.get(potentialMacroName);
                String[] args = (parts.length > 1) ? Arrays.copyOfRange(parts, 1, parts.length) : new String[0];
                if (macro.parameters().size() != args.length) {
                    throw new AssemblerException(programName, annotatedLine.originalLineNumber(), String.format("Falsche Anzahl an Argumenten für Makro %s. Erwartet %d, aber %d erhalten.", potentialMacroName, macro.parameters().size(), args.length), annotatedLine.content());
                }
                List<AnnotatedLine> expandedBody = new ArrayList<>();
                for (String bodyLine : macro.body()) {
                    String expandedLine = bodyLine;
                    for (int k = 0; k < args.length; k++) {
                        expandedLine = expandedLine.replace(macro.parameters().get(k), args[k]);
                    }
                    expandedLine = expandedLine.replace("@@", "_M" + this.macroExpansionCounter + "_");
                    expandedBody.add(new AnnotatedLine(expandedLine, annotatedLine.originalLineNumber()));
                }
                expandedCode.addAll(expandMacrosRecursively(expandedBody, callStack));
                callStack.pop();
            } else {
                expandedCode.add(annotatedLine);
            }
        }
        return expandedCode;
    }


    // =========================================================================================
    // === Erster Durchlauf: Adressen berechnen, Labels und Register sammeln
    // =========================================================================================

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
                currentPos = processDirective(directive, parts, currentPos, currentDv, annotatedLine);
            } else if (strippedLine.endsWith(":")) {
                processLabel(strippedLine, linearAddress, currentPos, annotatedLine);
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

    private int[] processDirective(String directive, String[] parts, int[] currentPos, int[] currentDv, AnnotatedLine line) {
        int[] newPos = Arrays.copyOf(currentPos, currentPos.length);
        switch (directive) {
            case ".ORG" -> {
                int[] parsedCoords = Arrays.stream(parts[1].split("\\|")).map(String::strip).mapToInt(Integer::parseInt).toArray();
                if (parsedCoords.length != Config.WORLD_DIMENSIONS) {
                    throw new AssemblerException(programName, line.originalLineNumber(), ".ORG-Direktive hat falsche Anzahl an Dimensionen.", line.content());
                }
                newPos = parsedCoords;
            }
            case ".DIR" -> currentDv = Arrays.stream(parts[1].split("\\|")).map(String::strip).mapToInt(Integer::parseInt).toArray();
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
        return newPos;
    }

    private void processLabel(String strippedLine, int linearAddress, int[] currentPos, AnnotatedLine line) {
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


    // =========================================================================================
    // === Zweiter Durchlauf: Maschinencode generieren, Platzhalter für Sprünge setzen
    // =========================================================================================

    private void performSecondPass(List<AnnotatedLine> annotatedLines) {
        int linearAddress = 0;
        int[] currentPos = new int[Config.WORLD_DIMENSIONS];
        int[] currentDv = {1, 0};

        for (AnnotatedLine annotatedLine : annotatedLines) {
            String strippedLine = annotatedLine.content().split("#", 2)[0].strip();
            if (strippedLine.isEmpty() || strippedLine.endsWith(":") || strippedLine.startsWith(".")) {
                if(strippedLine.toUpperCase().startsWith(".ORG")) currentPos = Arrays.stream(strippedLine.split("\\s+")[1].split("\\|")).mapToInt(Integer::parseInt).toArray();
                if(strippedLine.toUpperCase().startsWith(".DIR")) currentDv = Arrays.stream(strippedLine.split("\\s+")[1].split("\\|")).mapToInt(Integer::parseInt).toArray();
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


    // =========================================================================================
    // === Auflösungs-Phase: Sprünge und Vektoren finalisieren
    // =========================================================================================

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

            // KORREKTUR: Hier wird die ABSOLUTE Koordinate des Labels verwendet, kein Delta.
            int[] targetCoord = linearAddressToRelativeCoord.get(targetLabelAddress);

            if (isDebugMode) {
                System.out.printf("[DEBUG: %s]   - SETV zu '%-10s' an Koordinate %s -> Vektor %s\n", programName, targetLabel, Arrays.toString(opcodeCoord), Arrays.toString(targetCoord));
            }

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


    // =========================================================================================
    // === Hilfsmethoden
    // =========================================================================================

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