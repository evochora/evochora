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
 * Manages phases 3 & 4 of the assembler: the classic two-pass process
 * to calculate label addresses and generate machine code.
 */
public class PassManager {
    private final String programName;
    private final Map<String, String> defineMap;

    // New: proc metadata and import alias mapping (alias -> proc name)
    private final Map<String, DefinitionExtractor.ProcMeta> procMetaMap;
    private final Map<String, String> importAliasToProcName;

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

    public PassManager(String programName, Map<String, String> defineMap) {
        this(programName, defineMap, Map.of(), Map.of());
    }

    public PassManager(String programName, Map<String, String> defineMap,
                       Map<String, DefinitionExtractor.ProcMeta> procMetaMap,
                       Map<String, String> importAliasToProcName) {
        this.programName = programName;
        this.defineMap = defineMap;
        this.procMetaMap = procMetaMap != null ? procMetaMap : Map.of();
        this.importAliasToProcName = importAliasToProcName != null ? importAliasToProcName : Map.of();
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

    private String currentProcLabel = null;

    private void performSecondPass(List<AnnotatedLine> annotatedLines) {
        resetState();
        currentProcLabel = null;
        for (AnnotatedLine line : annotatedLines) {
            String strippedLine = line.content().split("#", 2)[0].strip();
            if (strippedLine.isEmpty() || strippedLine.endsWith(":") || strippedLine.startsWith(".")) {
                String[] parts = strippedLine.isEmpty() ? new String[]{""} : strippedLine.split("\\s+");
                String head = parts[0].toUpperCase();

                if (strippedLine.endsWith(":")) {
                    String label = strippedLine.substring(0, strippedLine.length() - 1).toUpperCase();
                    currentProcLabel = (procMetaMap != null && procMetaMap.containsKey(label)) ? label : currentProcLabel;
                } else if (head.equals(".ORG")) {
                    currentPos = Arrays.stream(parts[1].split("\\|")).mapToInt(Integer::parseInt).toArray();
                } else if (head.equals(".DIR")) {
                    currentDv = Arrays.stream(parts[1].split("\\|")).mapToInt(Integer::parseInt).toArray();
                } else if (head.equals(".PROC")) {
                    if (parts.length >= 2) {
                        String procName = parts[1].toUpperCase();
                        if (procMetaMap != null && procMetaMap.containsKey(procName)) {
                            currentProcLabel = procName;
                        }
                    }
                } else if (head.equals(".ENDP") || head.equals(".ENP")) {
                    currentProcLabel = null;
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

    // KORRIGIERT: Neue Hilfsmethode zur Erstellung einer kontextabhängigen Register-Map.
    private Map<String, Integer> buildRegisterMapForCurrentContext() {
        Map<String, Integer> regMap = new HashMap<>(this.registerMap);
        if (currentProcLabel != null && procMetaMap != null) {
            DefinitionExtractor.ProcMeta cur = procMetaMap.get(currentProcLabel);
            if (cur != null) {
                if (cur.formalParams() != null) {
                    List<String> formals = cur.formalParams();
                    for (int i = 0; i < formals.size(); i++) {
                        regMap.put(formals.get(i).toUpperCase(), 2000 + i); // FPR_BASE + index
                    }
                }
                if (cur.pregAliases() != null) {
                    for (Map.Entry<String, Integer> e : cur.pregAliases().entrySet()) {
                        regMap.put(e.getKey().toUpperCase(), 1000 + e.getValue()); // PR_BASE + index
                    }
                }
            }
        }
        return regMap;
    }

    private void processInstructionSecondPass(AnnotatedLine line) {
        String strippedLine = line.content().split("#", 2)[0].strip();
        String[] parts = strippedLine.split("\\s+", 2);
        String command = parts[0].toUpperCase();
        String[] args = (parts.length > 1) ? parts[1].split("\\s+") : new String[0];

        for (int i = 0; i < args.length; i++) {
            args[i] = defineMap.getOrDefault(args[i].toUpperCase(), args[i]);
        }

        if ("CALL".equals(command)) {
            if (args.length == 0) throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), "CALL instruction requires an argument.", line.content());

            int withIdx = -1;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equalsIgnoreCase(".WITH")) { withIdx = i; break; }
            }

            java.util.function.Function<String, String> resolveTargetProc = (tok) -> {
                String t = tok.toUpperCase();
                if (importAliasToProcName != null && importAliasToProcName.containsKey(t)) return importAliasToProcName.get(t);
                if (procMetaMap != null && procMetaMap.containsKey(t)) return t;
                return null;
            };

            String target = args[0].toUpperCase();
            String procName = resolveTargetProc.apply(target);

            if (procName != null) {
                DefinitionExtractor.ProcMeta meta = procMetaMap.get(procName);
                boolean hasFormParams = meta != null && meta.formalParams() != null && !meta.formalParams().isEmpty();

                if (hasFormParams) {
                    if (withIdx < 0) {
                        throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("call.with.requiresWithProc"), line.content());
                    }
                    handleCallWith(args, withIdx, line, resolveTargetProc);
                } else {
                    if (withIdx >= 0) {
                        throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("call.with.usedOnDsProc"), line.content());
                    }
                    AssemblerOutput output = new AssemblerOutput.JumpInstructionRequest(target);
                    emitInstruction(output, Instruction.getInstructionIdByName("CALL"), line);
                }
            } else {
                if (withIdx >= 0) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), ".WITH can only be used when calling a defined PROC.", line.content());
                }
                AssemblerOutput output = new AssemblerOutput.JumpInstructionRequest(target);
                emitInstruction(output, Instruction.getInstructionIdByName("CALL"), line);
            }
            return;
        }

        try {
            Integer opcodeId = Instruction.getInstructionIdByName(command);
            if (opcodeId == null) throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("passManager.unknownInstruction", command), line.content());

            Map<String, Integer> regMapForAsm = buildRegisterMapForCurrentContext();

            AssemblerOutput output = Instruction.getAssemblerById(Config.TYPE_CODE | opcodeId).apply(args, regMapForAsm, labelMap);
            emitInstruction(output, Config.TYPE_CODE | opcodeId, line);

        } catch (IllegalArgumentException e) {
            throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), e.getMessage(), line.content());
        }
    }

    private void handleCallWith(String[] args, int withIdx, AnnotatedLine line, java.util.function.Function<String, String> resolveTargetProc) {
        String target = args[0].toUpperCase();
        String[] actuals = Arrays.copyOfRange(args, withIdx + 1, args.length);

        String procName = resolveTargetProc.apply(target);
        if (procName == null) throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("import.procNotFound", target), line.content());

        DefinitionExtractor.ProcMeta meta = procMetaMap.get(procName);
        List<String> formals = meta.formalParams();
        if (actuals.length != formals.size()) {
            throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("call.with.arityMismatch"), line.content());
        }

        for (int i = 0; i < actuals.length; i++) {
            String actual = actuals[i];
            int fprIndex = 2000 + i;
            emitSetr(fprIndex, actual, line);
        }

        AssemblerOutput callOutput = new AssemblerOutput.JumpInstructionRequest(procName);
        emitInstruction(callOutput, Instruction.getInstructionIdByName("CALL"), line);

        for (int i = 0; i < actuals.length; i++) {
            String actual = actuals[i];
            int fprIndex = 2000 + i;
            emitSetr(actual, fprIndex, line);
        }
    }

    private void emitSetr(Object dest, Object src, AnnotatedLine line) {
        Map<String, Integer> regMapForAsm = buildRegisterMapForCurrentContext();

        String destStr = (dest instanceof String) ? (String) dest : "%FPR" + ((int)dest - 2000);
        String srcStr = (src instanceof String) ? (String) src : "%FPR" + ((int)src - 2000);

        AssemblerOutput output = Instruction.getAssemblerById(Instruction.getInstructionIdByName("SETR"))
                .apply(new String[]{destStr, srcStr}, regMapForAsm, labelMap);
        emitInstruction(output, Instruction.getInstructionIdByName("SETR"), line);
    }

    private void emitInstruction(AssemblerOutput output, int fullOpcodeId, AnnotatedLine line) {
        int instructionLength = Instruction.getInstructionLengthById(fullOpcodeId);
        int opcodeLinear = linearAddress;

        placeSymbol(fullOpcodeId, line);

        switch (output) {
            case AssemblerOutput.CodeSequence code -> {
                for (int val : code.machineCode()) {
                    placeSymbol(val, line);
                }
            }
            case AssemblerOutput.JumpInstructionRequest req -> {
                for (int i = 0; i < instructionLength - 1; i++) {
                    placeSymbol(new Symbol(Config.TYPE_DATA, 0).toInt(), line);
                }
                jumpPlaceholders.add(new PlaceholderResolver.JumpPlaceholder(opcodeLinear, req.labelName(), line));
            }
            case AssemblerOutput.LabelToVectorRequest req -> {
                placeSymbol(new Symbol(Config.TYPE_DATA, req.registerId()).toInt(), line);
                for (int i = 0; i < instructionLength - 2; i++) {
                    placeSymbol(new Symbol(Config.TYPE_DATA, 0).toInt(), line);
                }
                vectorPlaceholders.add(new PlaceholderResolver.VectorPlaceholder(opcodeLinear, req.labelName(), req.registerId(), line));
            }
            default -> throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("passManager.unexpectedOutputTypeInPass2", output.getClass().getSimpleName()), line.content());
        }
    }

    private void placeSymbol(int value, AnnotatedLine line) {
        linearAddressToCoordMap.put(linearAddress, Arrays.copyOf(currentPos, currentPos.length));
        coordToLinearAddressMap.put(Arrays.stream(currentPos).boxed().collect(Collectors.toList()), linearAddress);
        sourceMap.put(linearAddress, new SourceLocation(line.originalFileName(), line.originalLineNumber(), line.content()));
        machineCodeLayout.put(Arrays.copyOf(currentPos, currentPos.length), value);

        for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) {
            currentPos[d] += currentDv[d];
        }
        linearAddress++;
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
