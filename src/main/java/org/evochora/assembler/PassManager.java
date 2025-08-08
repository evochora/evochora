package org.evochora.assembler;

import org.evochora.Config;
import org.evochora.Messages;
import org.evochora.organism.Instruction;
import org.evochora.world.Symbol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manages phases 3 &amp; 4 of the assembler: the classic two-pass process
 * to calculate label addresses and generate machine code.
 */
public class PassManager {
    private final String programName;

    // Results
    private final Map<String, Integer> registerMap = new HashMap<>();
    private final Map<Integer, String> registerIdToNameMap = new HashMap<>();
    private final Map<int[], Symbol> initialWorldObjects = new HashMap<>();
    private final Map<String, Integer> labelMap = new HashMap<>();
    private final Map<Integer, String> labelAddressToNameMap = new LinkedHashMap<>();
    private final Map<Integer, int[]> linearAddressToCoordMap = new LinkedHashMap<>();
    private final Map<List<Integer>, Integer> coordToLinearAddressMap = new LinkedHashMap<>();
    private final Map<int[], Integer> machineCodeLayout = new LinkedHashMap<>();
    private final Map<Integer, SourceLocation> sourceMap = new HashMap<>();
    private final List<PlaceholderResolver.JumpPlaceholder> jumpPlaceholders = new ArrayList<>();
    private final List<PlaceholderResolver.VectorPlaceholder> vectorPlaceholders = new ArrayList<>();

    // Internal state
    private int linearAddress;
    private int[] currentPos;
    private int[] currentDv;

    public PassManager(String programName) {
        this.programName = programName;
    }

    public void runPasses(List<AnnotatedLine> processedCode) {
        performFirstPass(processedCode);
        performSecondPass(processedCode);
    }

    private void performFirstPass(List<AnnotatedLine> annotatedLines) {
        resetState();
        for (AnnotatedLine line : annotatedLines) {
            String strippedLine = line.content().split("#", 2)[0].strip();
            if (strippedLine.isEmpty()) continue;

            String[] parts = strippedLine.split("\\s+");
            String directive = parts[0].toUpperCase();

            if (directive.startsWith(".")) {
                processDirectiveFirstPass(directive, parts, line);
            } else if (strippedLine.endsWith(":")) {
                processLabel(strippedLine, line);
            } else {
                Integer opcodeId = Instruction.getInstructionIdByName(directive);
                if (opcodeId == null) throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("passManager.unknownInstruction", directive), line.content());

                int instructionLength = Instruction.getInstructionLengthById(Config.TYPE_CODE | opcodeId);
                for (int j = 0; j < instructionLength; j++) {
                    linearAddressToCoordMap.put(linearAddress, Arrays.copyOf(currentPos, currentPos.length));
                    coordToLinearAddressMap.put(Arrays.stream(currentPos).boxed().collect(Collectors.toList()), linearAddress);
                    sourceMap.put(linearAddress, new SourceLocation(line.originalFileName(), line.originalLineNumber(), line.content()));
                    linearAddress++;
                    for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) {
                        currentPos[d] += currentDv[d];
                    }
                }
            }
        }
    }

    private void performSecondPass(List<AnnotatedLine> annotatedLines) {
        resetState();
        for (AnnotatedLine line : annotatedLines) {
            String strippedLine = line.content().split("#", 2)[0].strip();
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
            processInstructionSecondPass(line);
        }
    }

    private void processDirectiveFirstPass(String directive, String[] parts, AnnotatedLine line) {
        switch (directive) {
            case ".ORG" -> currentPos = Arrays.stream(parts[1].split("\\|")).map(String::strip).mapToInt(Integer::parseInt).toArray();
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
                registerIdToNameMap.put(Integer.parseInt(parts[2]), parts[1].toUpperCase());
            }
        }
    }

    private void processLabel(String strippedLine, AnnotatedLine line) {
        String label = strippedLine.substring(0, strippedLine.length() - 1).toUpperCase();
        if (labelMap.containsKey(label)) {
            throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("passManager.labelRedeclared", label), line.content());
        }
        if (Instruction.getInstructionIdByName(label) != null) {
            throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("passManager.labelCollidesWithInstruction", label), line.content());
        }
        labelMap.put(label, linearAddress);
        labelAddressToNameMap.put(linearAddress, label);
    }

    private void processInstructionSecondPass(AnnotatedLine line) {
        String strippedLine = line.content().split("#", 2)[0].strip();
        String[] parts = strippedLine.split("\\s+", 2);
        String command = parts[0].toUpperCase();
        String[] args = (parts.length > 1) ? parts[1].split("\\s+") : new String[0];

        try {
            Integer opcodeId = Instruction.getInstructionIdByName(command);
            if (opcodeId == null) throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("passManager.unknownInstruction", command), line.content());

            AssemblerOutput output = Instruction.getAssemblerById(Config.TYPE_CODE | opcodeId).apply(args, registerMap, labelMap);
            int instructionLength = Instruction.getInstructionLengthById(Config.TYPE_CODE | opcodeId);

            machineCodeLayout.put(Arrays.copyOf(currentPos, currentPos.length), Config.TYPE_CODE | opcodeId);
            int[] argPos = Arrays.copyOf(currentPos, currentPos.length);
            for(int d=0; d<Config.WORLD_DIMENSIONS; d++) argPos[d] += currentDv[d];

            switch (output) {
                case AssemblerOutput.CodeSequence code -> {
                    for (int val : code.machineCode()) {
                        machineCodeLayout.put(Arrays.copyOf(argPos, argPos.length), val);
                        for(int d=0; d<Config.WORLD_DIMENSIONS; d++) argPos[d] += currentDv[d];
                    }
                }
                case AssemblerOutput.JumpInstructionRequest req -> {
                    jumpPlaceholders.add(new PlaceholderResolver.JumpPlaceholder(linearAddress, req.labelName(), line));
                    fillWithPlaceholders(argPos, instructionLength - 1);
                }
                case AssemblerOutput.LabelToVectorRequest req -> {
                    vectorPlaceholders.add(new PlaceholderResolver.VectorPlaceholder(linearAddress, req.labelName(), req.registerId(), line));
                    machineCodeLayout.put(Arrays.copyOf(argPos, argPos.length), new Symbol(Config.TYPE_DATA, req.registerId()).toInt());
                    int[] nextArgPos = Arrays.copyOf(argPos, argPos.length);
                    for(int d=0; d<Config.WORLD_DIMENSIONS; d++) nextArgPos[d] += currentDv[d];
                    fillWithPlaceholders(nextArgPos, instructionLength - 2);
                }
                // CORRECTION: Added default case to cover all possibilities.
                default -> {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("passManager.unexpectedOutputTypeInPass2", output.getClass().getSimpleName()), line.content());
                }
            }

            // Update state for next instruction
            for (int i = 0; i < instructionLength; i++) {
                for(int d=0; d<Config.WORLD_DIMENSIONS; d++) currentPos[d] += currentDv[d];
                linearAddress++;
            }

        } catch (IllegalArgumentException e) {
            throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), e.getMessage(), line.content());
        }
    }

    private void fillWithPlaceholders(int[] startPos, int count) {
        int[] pos = Arrays.copyOf(startPos, startPos.length);
        for (int i = 0; i < count; i++) {
            machineCodeLayout.put(Arrays.copyOf(pos, pos.length), 0);
            for(int d=0; d<Config.WORLD_DIMENSIONS; d++) pos[d] += currentDv[d];
        }
    }

    private void resetState() {
        this.linearAddress = 0;
        this.currentPos = new int[Config.WORLD_DIMENSIONS];
        this.currentDv = new int[Config.WORLD_DIMENSIONS];
        this.currentDv[0] = 1;
    }

    private int getTypeFromString(String typeName, AnnotatedLine line) {
        return switch (typeName.toUpperCase()) {
            case "CODE" -> Config.TYPE_CODE;
            case "DATA" -> Config.TYPE_DATA;
            case "ENERGY" -> Config.TYPE_ENERGY;
            case "STRUCTURE" -> Config.TYPE_STRUCTURE;
            default -> throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("passManager.unknownType", typeName), line.content());
        };
    }

    // Getters for the results
    public Map<String, Integer> getRegisterMap() { return registerMap; }
    public Map<Integer, String> getRegisterIdToNameMap() { return registerIdToNameMap; }
    public Map<int[], Symbol> getInitialWorldObjects() { return initialWorldObjects; }
    public Map<String, Integer> getLabelMap() { return labelMap; }
    public Map<Integer, String> getLabelAddressToNameMap() { return labelAddressToNameMap; }
    public Map<Integer, int[]> getLinearAddressToCoordMap() { return linearAddressToCoordMap; }
    public Map<List<Integer>, Integer> getCoordToLinearAddressMap() { return coordToLinearAddressMap; }
    public Map<int[], Integer> getMachineCodeLayout() { return machineCodeLayout; }
    public Map<Integer, SourceLocation> getSourceMap() { return sourceMap; }
    public List<PlaceholderResolver.JumpPlaceholder> getJumpPlaceholders() { return jumpPlaceholders; }
    public List<PlaceholderResolver.VectorPlaceholder> getVectorPlaceholders() { return vectorPlaceholders; }
}