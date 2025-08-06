// src/main/java/org/evochora/assembler/Assembler.java
package org.evochora.assembler;

import org.evochora.Config;
import org.evochora.organism.Instruction;
import org.evochora.organism.JmpInstruction;
import org.evochora.organism.JmprInstruction;
import org.evochora.world.Symbol;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

public class Assembler {

    private record MacroDefinition(List<String> parameters, List<String> body) {}

    // NEU: Hält eine Zeile und die dazugehörige Originalzeilennummer
    private record AnnotatedLine(String content, int originalLineNumber) {}

    // --- Instanzvariablen ---
    private String programName;
    private boolean isDebugMode;
    private int macroExpansionCounter = 0;
    private Map<String, MacroDefinition> macroMap;
    private Map<int[], Integer> machineCodeLayout;
    private Map<int[], Symbol> initialWorldObjects;
    private Map<String, Integer> registerMap;
    private Map<String, Integer> labelMap;
    private Map<Integer, int[]> linearAddressToRelativeCoord;
    private Map<List<Integer>, Integer> relativeCoordToLinearAddress;
    private List<AbstractMap.SimpleEntry<Integer, String>> jumpPlaceholderLocations;
    private Map<Integer, String> registerIdToName;
    private Map<Integer, String> labelAddressToName;

    public ProgramMetadata assemble(String assemblyCode, String programName, boolean isDebugMode) {
        this.programName = programName;
        this.isDebugMode = isDebugMode;
        this.macroExpansionCounter = 0;
        this.macroMap = new HashMap<>();
        this.registerMap = new HashMap<>();
        this.labelMap = new HashMap<>();
        this.initialWorldObjects = new HashMap<>();
        this.machineCodeLayout = new LinkedHashMap<>();
        this.linearAddressToRelativeCoord = new LinkedHashMap<>();
        this.relativeCoordToLinearAddress = new LinkedHashMap<>();
        this.jumpPlaceholderLocations = new ArrayList<>();
        this.registerIdToName = new HashMap<>();
        this.labelAddressToName = new LinkedHashMap<>();

        String[] initialLines = assemblyCode.split("\\r?\\n");
        List<AnnotatedLine> expandedLines = performMacroPass(initialLines);
        performFirstPass(expandedLines);
        performSecondPass(expandedLines);
        resolveJumpPlaceholders(expandedLines);

        String programId;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Integer> codeForHash = this.machineCodeLayout.values().stream().toList();
            ByteBuffer buffer = ByteBuffer.allocate(codeForHash.size() * 4);
            for (int code : codeForHash) buffer.putInt(code);
            byte[] hashBytes = digest.digest(buffer.array());
            programId = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hashBytes, 12));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 nicht gefunden", e);
        }

        return new ProgramMetadata(programId, machineCodeLayout, initialWorldObjects, null, registerMap,
                registerIdToName, labelMap, labelAddressToName, linearAddressToRelativeCoord, relativeCoordToLinearAddress);
    }

    // GEÄNDERT: Die Methode wurde umgeschrieben, um Rekursion und Loop-Erkennung zu unterstützen
    private List<AnnotatedLine> performMacroPass(String[] lines) {
        List<AnnotatedLine> codeWithoutMacroDefs = new ArrayList<>();

        // Erster Durchlauf: Makro-Definitionen finden und entfernen
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].split("#", 2)[0].strip();
            if (line.isEmpty()) {
                codeWithoutMacroDefs.add(new AnnotatedLine(lines[i], i + 1));
                continue;
            }

            String[] parts = line.split("\\s+");
            if (parts[0].equalsIgnoreCase(".MACRO")) {
                if (parts.length < 2 || !parts[1].startsWith("$")) {
                    throw new AssemblerException(programName, i + 1, String.format("Ungültige Makro-Definition. Erwartet: .MACRO $NAME [param1] [param2] ..."));
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
                    throw new AssemblerException(programName, i + 1, "Fehlendes .ENDM für Makro-Definition.");
                }
                macroMap.put(macroName, new MacroDefinition(params, body));
                i = j;
            } else {
                codeWithoutMacroDefs.add(new AnnotatedLine(lines[i], i + 1));
            }
        }

        if (isDebugMode && !macroMap.isEmpty()) {
            System.out.printf("[DEBUG: %s] --- Makro-Definitionen gefunden ---\n", programName);
            macroMap.forEach((name, def) -> System.out.printf("[DEBUG: %s]   - %s mit Parametern %s\n", programName, name, def.parameters()));
        }

        // Zweiter Durchlauf (rekursiv): Makro-Aufrufe expandieren
        Deque<String> macroCallStack = new ArrayDeque<>();
        return expandMacrosRecursively(codeWithoutMacroDefs, macroCallStack);
    }

    private List<AnnotatedLine> expandMacrosRecursively(List<AnnotatedLine> lines, Deque<String> macroCallStack) {
        List<AnnotatedLine> expandedCode = new ArrayList<>();

        for (AnnotatedLine annotatedLine : lines) {
            String line = annotatedLine.content();
            int originalLineNumber = annotatedLine.originalLineNumber();

            String strippedLine = line.split("#", 2)[0].strip();
            if (strippedLine.isEmpty()) {
                expandedCode.add(new AnnotatedLine(line, originalLineNumber));
                continue;
            }

            String[] parts = strippedLine.split("\\s+");
            String potentialMacroName = parts[0];

            if (potentialMacroName.startsWith("$") && macroMap.containsKey(potentialMacroName)) {
                // NEU: Endlosschleife erkennen
                if (macroCallStack.contains(potentialMacroName)) {
                    String loopTrace = String.join(" -> ", macroCallStack) + " -> " + potentialMacroName;
                    throw new AssemblerException(programName, originalLineNumber, "Endlose Makro-Rekursion erkannt: " + loopTrace);
                }

                macroCallStack.push(potentialMacroName);
                this.macroExpansionCounter++;
                MacroDefinition macro = macroMap.get(potentialMacroName);
                String[] args = (parts.length > 1) ? Arrays.copyOfRange(parts, 1, parts.length) : new String[0];

                if (macro.parameters().size() != args.length) {
                    throw new AssemblerException(programName, originalLineNumber, String.format("Falsche Anzahl an Argumenten für Makro %s. Erwartet %d, aber %d erhalten.", potentialMacroName, macro.parameters().size(), args.length));
                }

                List<AnnotatedLine> macroBodyWithArgs = new ArrayList<>();
                for (String bodyLine : macro.body()) {
                    String expandedLine = bodyLine;
                    for (int k = 0; k < args.length; k++) {
                        expandedLine = expandedLine.replace(macro.parameters().get(k), args[k]);
                    }
                    expandedLine = expandedLine.replace("@@", "_M" + this.macroExpansionCounter + "_");
                    macroBodyWithArgs.add(new AnnotatedLine(expandedLine, originalLineNumber));
                }

                // Rekursiver Aufruf, um verschachtelte Makros zu expandieren
                List<AnnotatedLine> recursivelyExpanded = expandMacrosRecursively(macroBodyWithArgs, macroCallStack);
                expandedCode.addAll(recursivelyExpanded);

                macroCallStack.pop();
            } else {
                expandedCode.add(annotatedLine);
            }
        }

        return expandedCode;
    }

    private void performFirstPass(List<AnnotatedLine> annotatedLines) {
        int linearAddress = 0;
        int[] currentDv = {1, 0};
        int[] currentPos = new int[Config.WORLD_DIMENSIONS];

        for (AnnotatedLine annotatedLine : annotatedLines) {
            String line = annotatedLine.content();
            int lineNumber = annotatedLine.originalLineNumber();

            String strippedLine = line.split("#", 2)[0].strip();
            if (strippedLine.isEmpty()) continue;

            String[] parts = strippedLine.split("\\s+");
            String directive = parts[0].toUpperCase();

            // KORREKTUR: Direktiven, die die Position beeinflussen, MÜSSEN zuerst behandelt werden.
            if (directive.equals(".ORG")) {
                int[] parsedCoords = Arrays.stream(parts[1].split("\\|")).map(String::strip).mapToInt(Integer::parseInt).toArray();
                if (parsedCoords.length != Config.WORLD_DIMENSIONS) {
                    throw new AssemblerException(programName, lineNumber, String.format(".ORG-Direktive hat falsche Anzahl an Dimensionen. Erwartet: %d, erhalten: %d.", Config.WORLD_DIMENSIONS, parsedCoords.length));
                }
                currentPos = parsedCoords;
                continue;
            }
            if (directive.equals(".DIR")) {
                currentDv = Arrays.stream(parts[1].split("\\|")).map(String::strip).mapToInt(Integer::parseInt).toArray();
                continue; // .DIR belegt selbst keinen Platz.
            }
            if (directive.equals(".PLACE")) {
                String[] typeAndValue = parts[1].split(":");
                String typeName = typeAndValue[0].toUpperCase();
                int value = Integer.parseInt(typeAndValue[1]);
                int[] relativePos = Arrays.stream(parts[2].split("\\|")).map(String::strip).mapToInt(Integer::parseInt).toArray();
                int type = switch (typeName) {
                    case "ENERGY" -> Config.TYPE_ENERGY;
                    case "STRUCTURE" -> Config.TYPE_STRUCTURE;
                    case "DATA" -> Config.TYPE_DATA;
                    case "CODE" -> Config.TYPE_CODE;
                    default -> throw new AssemblerException(programName, lineNumber, String.format("Unbekannter Typ in .PLACE: %s", typeName));
                };
                initialWorldObjects.put(relativePos, new Symbol(type, value));
                continue;
            }
            if (directive.equals(".REG")) {
                registerMap.put(parts[1].toUpperCase(), Integer.parseInt(parts[2]));
                registerIdToName.put(Integer.parseInt(parts[2]), parts[1].toUpperCase());
                continue;
            }

            // Erst NACH den Direktiven wird die aktuelle Position für die Adress-Map gespeichert.
            this.linearAddressToRelativeCoord.put(linearAddress, Arrays.copyOf(currentPos, currentPos.length));
            this.relativeCoordToLinearAddress.put(Arrays.stream(currentPos).boxed().collect(Collectors.toList()), linearAddress);

            if (strippedLine.endsWith(":")) {
                String label = strippedLine.substring(0, strippedLine.length() - 1).toUpperCase();
                if (labelMap.containsKey(label)) {
                    throw new AssemblerException(programName, lineNumber, String.format("Label '%s' wurde mehrfach vergeben.", label));
                }
                labelMap.put(label, linearAddress);
                labelAddressToName.put(linearAddress, label);
            } else { // Annahme: Ein Befehl
                int instructionLength;
                if (directive.equals("JUMP")) {
                    String arg = parts[1].toUpperCase();
                    if (arg.startsWith("%")) {
                        instructionLength = Instruction.getInstructionLengthById(Config.TYPE_CODE | JmpInstruction.ID);
                    } else {
                        instructionLength = Instruction.getInstructionLengthById(Config.TYPE_CODE | JmprInstruction.ID);
                    }
                } else {
                    Integer opcodeId = Instruction.getInstructionIdByName(directive);
                    if (opcodeId == null) {
                        throw new AssemblerException(programName, lineNumber, String.format("Unbekannter Befehl: %s", directive));
                    }
                    instructionLength = Instruction.getInstructionLengthById(Config.TYPE_CODE | opcodeId);
                }

                linearAddress += instructionLength;
                for (int j = 0; j < instructionLength; j++) {
                    for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) {
                        currentPos[d] += currentDv[d];
                    }
                }
            }
        }

        if (isDebugMode) {
            System.out.printf("[DEBUG: %s] --- Pass 1 abgeschlossen ---\n", programName);
            System.out.printf("[DEBUG: %s] Gefundene Labels:\n", programName);
            labelMap.forEach((name, addr) -> {
                int[] coord = linearAddressToRelativeCoord.get(addr);
                System.out.printf("[DEBUG: %s]   - %-15s: Adresse=%-4d Koordinate=%s\n", programName, name, addr, Arrays.toString(coord));
            });
        }
    }

    private void performSecondPass(List<AnnotatedLine> annotatedLines) {
        int linearAddress = 0;
        int[] currentPos = new int[Config.WORLD_DIMENSIONS];
        int[] currentDv = {1,0};

        for (AnnotatedLine annotatedLine : annotatedLines) {
            String line = annotatedLine.content();
            int lineNumber = annotatedLine.originalLineNumber();
            String strippedLine = line.split("#", 2)[0].strip();

            if (strippedLine.isEmpty()) continue;

            String[] parts = strippedLine.split("\\s+", 2);
            String directive = parts[0].toUpperCase();

            // KORREKTUR: Die Logik zur Behandlung von Direktiven MUSS exakt
            // der von performFirstPass entsprechen.
            if (directive.equals(".ORG")) {
                currentPos = Arrays.stream(parts[1].split("\\|")).map(String::strip).mapToInt(Integer::parseInt).toArray();
                continue;
            }
            if (directive.equals(".DIR")) {
                currentDv = Arrays.stream(parts[1].split("\\|")).map(String::strip).mapToInt(Integer::parseInt).toArray();
                continue;
            }
            // Wir überspringen nur noch Labels und Direktiven, die keinen Code generieren
            // und keine Position/Richtung ändern.
            if (strippedLine.endsWith(":") || directive.equals(".REG") || directive.equals(".PLACE")) {
                continue;
            }

            int currentOpcodeLinearAddress = linearAddress;
            String[] args = (parts.length > 1) ? parts[1].split("\\s+") : new String[0];

            Integer opcodeId;
            AssemblerOutput assemblerOutput;

            try {
                if (directive.equals("JUMP")) {
                    String arg = args[0].toUpperCase();
                    if (labelMap.containsKey(arg)) {
                        opcodeId = JmprInstruction.ID;
                        assemblerOutput = new AssemblerOutput.JumpInstructionRequest(arg);
                    } else if (registerMap.containsKey(arg)) {
                        opcodeId = JmpInstruction.ID;
                        assemblerOutput = Instruction.getAssemblerById(Config.TYPE_CODE | opcodeId).apply(args, registerMap, labelMap);
                    } else {
                        throw new IllegalArgumentException(String.format("JUMP erwartet ein Register oder Label. Gefunden: %s", arg));
                    }
                } else {
                    opcodeId = Instruction.getInstructionIdByName(directive);
                    if (opcodeId == null) {
                        throw new IllegalArgumentException(String.format("Unbekannter Befehl: %s", directive));
                    }
                    assemblerOutput = Instruction.getAssemblerById(Config.TYPE_CODE | opcodeId).apply(args, registerMap, labelMap);
                }
            } catch (IllegalArgumentException e) {
                throw new AssemblerException(programName, lineNumber, e.getMessage());
            }


            machineCodeLayout.put(Arrays.copyOf(currentPos, currentPos.length), Config.TYPE_CODE | opcodeId);
            linearAddress++;
            for(int d=0; d<Config.WORLD_DIMENSIONS; d++) currentPos[d] += currentDv[d];

            switch (assemblerOutput) {
                case AssemblerOutput.CodeSequence code -> {
                    for (int argVal : code.machineCode()) {
                        machineCodeLayout.put(Arrays.copyOf(currentPos, Config.WORLD_DIMENSIONS), argVal);
                        linearAddress++;
                        for(int d=0; d<Config.WORLD_DIMENSIONS; d++) currentPos[d] += currentDv[d];
                    }
                }
                case AssemblerOutput.JumpInstructionRequest req -> {
                    jumpPlaceholderLocations.add(new AbstractMap.SimpleEntry<>(currentOpcodeLinearAddress, req.labelName()));
                    int len = Instruction.getInstructionLengthById(Config.TYPE_CODE | JmprInstruction.ID);
                    for (int j = 0; j < len - 1; j++) {
                        machineCodeLayout.put(Arrays.copyOf(currentPos, Config.WORLD_DIMENSIONS), 0);
                        linearAddress++;
                        for(int d=0; d<Config.WORLD_DIMENSIONS; d++) currentPos[d] += currentDv[d];
                    }
                }
                default -> {}
            }
        }
    }

    private void resolveJumpPlaceholders(List<AnnotatedLine> annotatedLines) {
        if (isDebugMode && !jumpPlaceholderLocations.isEmpty()) {
            System.out.printf("[DEBUG: %s] --- Sprung-Auflösung ---\n", programName);
        }

        for (AbstractMap.SimpleEntry<Integer, String> entry : jumpPlaceholderLocations) {
            int jumpOpcodeLinearAddress = entry.getKey();
            String targetLabelName = entry.getValue();

            int[] jumpOpcodeRelativeCoord = this.linearAddressToRelativeCoord.get(jumpOpcodeLinearAddress);
            if (jumpOpcodeRelativeCoord == null) {
                throw new IllegalStateException(String.format("Interner Assembler Fehler: Keine Koordinate für lineare Adresse %d gefunden. Dies deutet auf eine Asynchronität zwischen Pass 1 und 2 hin.", jumpOpcodeLinearAddress));
            }

            Integer targetLabelLinearAddress = this.labelMap.get(targetLabelName);
            if (targetLabelLinearAddress == null) {
                throw new IllegalStateException("Interner Fehler: Label '" + targetLabelName + "' nicht in der Label-Map gefunden.");
            }

            int[] targetLabelRelativeCoord = this.linearAddressToRelativeCoord.get(targetLabelLinearAddress);
            int[] delta = new int[Config.WORLD_DIMENSIONS];
            for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                delta[i] = targetLabelRelativeCoord[i] - jumpOpcodeRelativeCoord[i];
            }

            if (isDebugMode) {
                System.out.printf("[DEBUG: %s]   - JUMP zu '%-10s' (von %s nach %s): Delta berechnet als %s\n", programName, targetLabelName, Arrays.toString(jumpOpcodeRelativeCoord), Arrays.toString(targetLabelRelativeCoord), Arrays.toString(delta));
            }

            int[] dvAtJump = getDvAtLinearAddress(jumpOpcodeLinearAddress, annotatedLines);
            int[] currentArgPos = Arrays.copyOf(jumpOpcodeRelativeCoord, jumpOpcodeRelativeCoord.length);

            for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                for(int d=0; d<Config.WORLD_DIMENSIONS; d++) currentArgPos[d] += dvAtJump[d];
                machineCodeLayout.put(Arrays.copyOf(currentArgPos, Config.WORLD_DIMENSIONS), new Symbol(Config.TYPE_DATA, delta[i]).toInt());
            }
        }
    }

    private int[] getDvAtLinearAddress(int targetAddress, List<AnnotatedLine> annotatedLines) {
        int[] dv = {1,0};
        int currentAddress = 0;
        for (AnnotatedLine annotatedLine : annotatedLines) {
            String strippedLine = annotatedLine.content().split("#")[0].strip();
            if (currentAddress > targetAddress) break;

            if(strippedLine.toUpperCase().startsWith(".DIR")) {
                dv = Arrays.stream(strippedLine.split("\\s+")[1].split("\\|")).map(String::strip).mapToInt(Integer::parseInt).toArray();
            }
            if (strippedLine.isEmpty() || strippedLine.endsWith(":") || strippedLine.startsWith(".")) {
                continue;
            }

            String directive = strippedLine.split("\\s+")[0].toUpperCase();
            String[] parts = strippedLine.split("\\s+");
            int instructionLength;
            if (directive.equals("JUMP")) {
                String arg = parts[1].toUpperCase();
                if (arg.startsWith("%")) {
                    instructionLength = Instruction.getInstructionLengthById(Config.TYPE_CODE | JmpInstruction.ID);
                } else {
                    instructionLength = Instruction.getInstructionLengthById(Config.TYPE_CODE | JmprInstruction.ID);
                }
            } else {
                Integer opcodeId = Instruction.getInstructionIdByName(directive);
                if (opcodeId == null) continue;
                instructionLength = Instruction.getInstructionLengthById(Config.TYPE_CODE | opcodeId);
            }
            currentAddress += instructionLength;
        }
        return dv;
    }
}