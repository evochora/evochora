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

/**
 * Diese Klasse enthält die gesamte Logik für den 2-Pass-Assembler.
 * Sie wandelt einen Assembly-Code-String in ein ProgramMetadata-Objekt um,
 * das den Maschinencode und alle relevanten Metadaten enthält.
 */
public class Assembler {

    // --- Instanzvariablen für den Zustand während der Assemblierung ---
    private Map<int[], Integer> machineCodeLayout;
    private Map<int[], Symbol> initialWorldObjects;
    private Map<String, Integer> registerMap;
    private Map<String, Integer> labelMap;
    private int[] finalAssemblyDv;

    private Map<Integer, int[]> linearAddressToRelativeCoord;
    private Map<List<Integer>, Integer> relativeCoordToLinearAddress;
    private List<AbstractMap.SimpleEntry<Integer, String>> jumpPlaceholderLocations;
    private Map<Integer, String> registerIdToName;
    private Map<Integer, String> labelAddressToName;

    public ProgramMetadata assemble(String assemblyCode) {
        this.registerMap = new HashMap<>();
        this.labelMap = new HashMap<>();
        this.initialWorldObjects = new HashMap<>();
        this.machineCodeLayout = new LinkedHashMap<>();
        this.linearAddressToRelativeCoord = new LinkedHashMap<>();
        this.relativeCoordToLinearAddress = new LinkedHashMap<>();
        this.jumpPlaceholderLocations = new ArrayList<>();
        this.registerIdToName = new HashMap<>();
        this.labelAddressToName = new LinkedHashMap<>();

        String[] lines = assemblyCode.split("\\r?\\n");

        // --- Pass 1: Sammeln von Metadaten und Erstellen des Layouts ---
        int linearAddress = 0;
        int[] currentDv = new int[Config.WORLD_DIMENSIONS];
        currentDv[0] = 1;
        int[] currentPos = new int[Config.WORLD_DIMENSIONS];

        for (String line : lines) {
            line = line.split("#")[0].strip();
            if (line.isEmpty()) continue;

            linearAddressToRelativeCoord.put(linearAddress, Arrays.copyOf(currentPos, currentPos.length));
            relativeCoordToLinearAddress.put(Arrays.stream(currentPos).boxed().collect(Collectors.toList()), linearAddress);

            String[] parts = line.split("\\s+");
            String directive = parts[0].toUpperCase();

            if (line.endsWith(":")) {
                String label = line.substring(0, line.length() - 1).toUpperCase();
                labelMap.put(label, linearAddress);
                labelAddressToName.put(linearAddress, label);
            } else if (directive.equals(".REG")) {
                registerMap.put(parts[1].toUpperCase(), Integer.parseInt(parts[2]));
                registerIdToName.put(Integer.parseInt(parts[2]), parts[1].toUpperCase());
            } else if (directive.equals(".PLACE")) {
                // GEÄNDERT: Logik zum Parsen von .PLACE an die TYPE:WERT-Syntax angepasst
                if (parts.length != 2 + Config.WORLD_DIMENSIONS) {
                    throw new IllegalArgumentException(".PLACE erwartet TYPE:WERT und " + Config.WORLD_DIMENSIONS + " Koordinaten.");
                }
                String[] typeAndValue = parts[1].split(":");
                if (typeAndValue.length != 2) {
                    throw new IllegalArgumentException(".PLACE erwartet das Format TYPE:WERT, erhalten: " + parts[1]);
                }

                String typeName = typeAndValue[0].toUpperCase();
                int value = Integer.parseInt(typeAndValue[1]);

                int[] relativePos = new int[Config.WORLD_DIMENSIONS];
                for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                    relativePos[i] = Integer.parseInt(parts[2 + i]);
                }

                int type = switch (typeName) {
                    case "ENERGY" -> Config.TYPE_ENERGY;
                    case "STRUCTURE" -> Config.TYPE_STRUCTURE;
                    case "DATA" -> Config.TYPE_DATA;
                    case "CODE" -> Config.TYPE_CODE;
                    default -> throw new IllegalArgumentException("Unbekannter Typ in .PLACE: " + typeName);
                };
                initialWorldObjects.put(relativePos, new Symbol(type, value));
            } else if (directive.equals(".DIR")) {
                String[] vectorComponents = parts[1].split("\\|");
                if (vectorComponents.length != Config.WORLD_DIMENSIONS) throw new IllegalArgumentException(".DIR erwartet einen " + Config.WORLD_DIMENSIONS + "D Vektor.");
                for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                    currentDv[i] = Integer.parseInt(vectorComponents[i].strip());
                }
            } else {
                int instructionLength;
                if (directive.equals("JUMP")) {
                    String[] jumpArgs = line.split("\\s+")[1].split("\\s+");
                    if (labelMap.containsKey(jumpArgs[0].toUpperCase())) {
                        instructionLength = 1 + Config.WORLD_DIMENSIONS; // JMPR
                    } else {
                        instructionLength = 2; // JMP
                    }
                } else {
                    Integer opcodeId = Instruction.getInstructionIdByName(directive);
                    if (opcodeId == null) throw new IllegalArgumentException("Unbekannter Befehl: " + directive);
                    instructionLength = Instruction.getInstructionLengthById(Config.TYPE_CODE | opcodeId);
                }

                linearAddress += instructionLength;
                for(int i = 0; i < instructionLength; i++) {
                    for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) {
                        currentPos[d] += currentDv[d];
                    }
                }
            }
        }
        this.linearAddressToRelativeCoord.put(linearAddress, Arrays.copyOf(currentPos, currentPos.length));
        this.relativeCoordToLinearAddress.put(Arrays.stream(currentPos).boxed().collect(Collectors.toList()), linearAddress);


        // --- Pass 2: Maschinencode generieren ---
        linearAddress = 0;
        Arrays.fill(currentPos, 0);
        currentDv = new int[Config.WORLD_DIMENSIONS];
        currentDv[0] = 1;

        for (String line : lines) {
            line = line.split("#")[0].strip();
            if (line.isEmpty() || line.endsWith(":")) continue;

            String[] parts = line.split("\\s+", 2);
            String directive = parts[0].toUpperCase();

            if (directive.equals(".REG") || directive.equals(".PLACE") || directive.equals(".DIR")) {
                if (directive.equals(".DIR")) {
                    String[] vectorComponents = parts[1].split("\\|");
                    for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                        currentDv[i] = Integer.parseInt(vectorComponents[i].strip());
                    }
                }
                continue;
            }

            int currentOpcodeLinearAddress = linearAddress;
            String argsStr = (parts.length > 1) ? parts[1] : "";
            String[] args = argsStr.isEmpty() ? new String[0] : argsStr.split("\\s+");

            Integer opcodeId;
            AssemblerOutput assemblerOutput;

            if (directive.equals("JUMP")) {
                String arg = args[0].toUpperCase();
                if (labelMap.containsKey(arg)) {
                    opcodeId = JmprInstruction.ID;
                    assemblerOutput = new AssemblerOutput.JumpInstructionRequest(arg);
                } else {
                    opcodeId = JmpInstruction.ID;
                    Instruction.AssemblerPlanner jmpAssembler = Instruction.getAssemblerById(Config.TYPE_CODE | opcodeId);
                    assemblerOutput = jmpAssembler.apply(args, registerMap, labelMap);
                }
            } else {
                opcodeId = Instruction.getInstructionIdByName(directive);
                if (opcodeId == null) {
                    throw new IllegalArgumentException("Unbekannter Befehl: " + directive);
                }
                Instruction.AssemblerPlanner assembler = Instruction.getAssemblerById(Config.TYPE_CODE | opcodeId);
                if (assembler == null) throw new IllegalArgumentException("Kein Assembler für Befehl gefunden: " + directive);
                assemblerOutput = assembler.apply(args, registerMap, labelMap);
            }

            switch (assemblerOutput) {
                case AssemblerOutput.CodeSequence codeSequence -> {
                    machineCodeLayout.put(Arrays.copyOf(currentPos, currentPos.length), Config.TYPE_CODE | opcodeId);
                    linearAddress++;
                    for(int i=0; i<Config.WORLD_DIMENSIONS; i++) currentPos[i] += currentDv[i];

                    for (int argValue : codeSequence.machineCode()) {
                        machineCodeLayout.put(Arrays.copyOf(currentPos, Config.WORLD_DIMENSIONS), argValue);
                        linearAddress++;
                        for(int j=0; j<Config.WORLD_DIMENSIONS; j++) currentPos[j] += currentDv[j];
                    }
                }
                case AssemblerOutput.JumpInstructionRequest jumpRequest -> {
                    machineCodeLayout.put(Arrays.copyOf(currentPos, currentPos.length), Config.TYPE_CODE | opcodeId);
                    jumpPlaceholderLocations.add(new AbstractMap.SimpleEntry<>(currentOpcodeLinearAddress, jumpRequest.labelName()));

                    linearAddress++;
                    for(int i=0; i<Config.WORLD_DIMENSIONS; i++) currentPos[i] += currentDv[i];

                    int instructionLength = Instruction.getInstructionLengthById(Config.TYPE_CODE | opcodeId);
                    for(int i = 0; i < instructionLength - 1; i++) {
                        machineCodeLayout.put(Arrays.copyOf(currentPos, Config.WORLD_DIMENSIONS), 0);
                        linearAddress++;
                        for(int j=0; j<Config.WORLD_DIMENSIONS; j++) currentPos[j] += currentDv[j];
                    }
                }
                default -> throw new IllegalArgumentException("Unerwarteter AssemblerOutput-Typ.");
            }
        }
        this.finalAssemblyDv = Arrays.copyOf(currentDv, currentDv.length);

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

        // --- Phase 3: Sprungplatzhalter auflösen ---
        for (AbstractMap.SimpleEntry<Integer, String> entry : jumpPlaceholderLocations) {
            int jumpOpcodeLinearAddress = entry.getKey();
            String targetLabelName = entry.getValue();
            int[] delta = resolveJumpDelta(jumpOpcodeLinearAddress, targetLabelName);
            int[] jumpOpcodeRelativeCoord = this.linearAddressToRelativeCoord.get(jumpOpcodeLinearAddress);
            int[] dvAtJump = getDvAtLinearAddress(jumpOpcodeLinearAddress, lines);
            int[] currentArgPos = Arrays.copyOf(jumpOpcodeRelativeCoord, jumpOpcodeRelativeCoord.length);

            for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                for(int d=0; d<Config.WORLD_DIMENSIONS; d++) currentArgPos[d] += dvAtJump[d];
                machineCodeLayout.put(Arrays.copyOf(currentArgPos, Config.WORLD_DIMENSIONS), Config.TYPE_DATA | (delta[i] & Config.VALUE_MASK));
            }
        }

        return new ProgramMetadata(programId, machineCodeLayout, initialWorldObjects, finalAssemblyDv, registerMap,
                registerIdToName, labelMap, labelAddressToName, linearAddressToRelativeCoord, relativeCoordToLinearAddress);
    }

    private int[] resolveJumpDelta(int jumpOpcodeLinearAddress, String targetLabelName) {
        int[] jumpOpcodeRelativeCoord = this.linearAddressToRelativeCoord.get(jumpOpcodeLinearAddress);
        Integer targetLabelLinearAddress = this.labelMap.get(targetLabelName);
        if (targetLabelLinearAddress == null) throw new IllegalArgumentException("Label nicht gefunden: " + targetLabelName);
        int[] targetLabelRelativeCoord = this.linearAddressToRelativeCoord.get(targetLabelLinearAddress);
        int[] delta = new int[Config.WORLD_DIMENSIONS];
        for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
            delta[i] = targetLabelRelativeCoord[i] - jumpOpcodeRelativeCoord[i];
        }
        return delta;
    }

    private int[] getDvAtLinearAddress(int address, String[] lines) {
        int[] dv = new int[Config.WORLD_DIMENSIONS];
        dv[0] = 1;
        int currentAddress = 0;
        for (String line : lines) {
            line = line.split("#")[0].strip();
            if (line.isEmpty()) continue;

            if (line.toUpperCase().startsWith(".DIR")) {
                int lineAddress = getLineAddress(line, lines);
                if (lineAddress <= address) {
                    String[] vectorComponents = line.split("\\s+")[1].split("\\|");
                    for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                        dv[i] = Integer.parseInt(vectorComponents[i].strip());
                    }
                }
            }
        }
        return dv;
    }

    private int getLineAddress(String lineToFind, String[] allLines) {
        int address = 0;
        for (String line : allLines) {
            if (line.equals(lineToFind)) {
                return address;
            }
            line = line.split("#")[0].strip();
            if (!line.isEmpty() && !line.startsWith(".") && !line.endsWith(":")) {
                String directive = line.split("\\s+")[0].toUpperCase();
                Integer opcodeId = Instruction.getInstructionIdByName(directive);
                if(opcodeId != null) {
                    address += Instruction.getInstructionLengthById(Config.TYPE_CODE | opcodeId);
                }
            }
        }
        return -1;
    }
}