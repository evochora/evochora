package org.evochora.assembler;

import org.evochora.Config;
import org.evochora.Messages;
import org.evochora.assembler.directives.DirDirectiveHandler;
import org.evochora.assembler.directives.IDirectiveHandler;
import org.evochora.assembler.directives.OrgDirectiveHandler;
import org.evochora.assembler.directives.PlaceDirectiveHandler;
import org.evochora.assembler.directives.RegDirectiveHandler;
import org.evochora.organism.Instruction;
import org.evochora.world.Symbol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PassManager {
    private final String programName;
    private final Map<String, String> defineMap;
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

    private final Map<String, IDirectiveHandler> directiveHandlers = new HashMap<>();

    public PassManager(String programName, Map<String, String> defineMap,
                       Map<String, DefinitionExtractor.ProcMeta> procMetaMap,
                       Map<String, String> importAliasToProcName) {
        this.programName = programName;
        this.defineMap = defineMap;
        this.procMetaMap = procMetaMap != null ? procMetaMap : Map.of();
        this.importAliasToProcName = importAliasToProcName != null ? importAliasToProcName : Map.of();

        directiveHandlers.put(".ORG", new OrgDirectiveHandler());
        directiveHandlers.put(".DIR", new DirDirectiveHandler());
        directiveHandlers.put(".REG", new RegDirectiveHandler());
        directiveHandlers.put(".PLACE", new PlaceDirectiveHandler());
    }

    public void runPasses(List<AnnotatedLine> processedCode) {
        performFirstPass(processedCode);
        performSecondPass(processedCode);
    }

    private void performFirstPass(List<AnnotatedLine> annotatedLines) {
        resetState();
        PassManagerContext context = new PassManagerContext(
                this.currentPos, this.currentDv, this.linearAddress,
                this.registerMap, this.registerIdToNameMap, this.initialWorldObjects,
                this.labelMap, this.labelAddressToNameMap, this.linearAddressToCoordMap,
                this.coordToLinearAddressMap, this.sourceMap, this.programName
        );

        for (AnnotatedLine line : annotatedLines) {
            String strippedLine = line.content().split("#", 2)[0].strip();
            if (strippedLine.isEmpty()) continue;

            String[] parts = strippedLine.split("\\s+");
            String directive = parts[0].toUpperCase();

            if (directive.startsWith(".")) {
                IDirectiveHandler handler = directiveHandlers.get(directive);
                if (handler != null) {
                    handler.handle(line, context);
                }
            } else if (strippedLine.endsWith(":")) {
                processLabel(strippedLine, line);
            } else {
                Integer opcodeId = Instruction.getInstructionIdByName(directive);
                if (opcodeId == null) throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("passManager.unknownInstruction", directive), line.content());

                int instructionLength = Instruction.getInstructionLengthById(opcodeId);
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

    private void performSecondPass(List<AnnotatedLine> annotatedLines) {
        resetState();
        for (AnnotatedLine line : annotatedLines) {
            String strippedLine = line.content().split("#", 2)[0].strip();
            if (strippedLine.isEmpty() || strippedLine.endsWith(":") || strippedLine.startsWith(".")) {
                String[] parts = strippedLine.isEmpty() ? new String[]{""} : strippedLine.split("\\s+");
                String head = parts[0].toUpperCase();
                if (head.equals(".ORG")) {
                    currentPos = Arrays.stream(parts[1].split("\\|")).mapToInt(NumericParser::parseInt).toArray();
                } else if (head.equals(".DIR")) {
                    currentDv = Arrays.stream(parts[1].split("\\|")).mapToInt(NumericParser::parseInt).toArray();
                }
                continue;
            }
            processInstructionSecondPass(line);
        }
    }

    private String findCurrentProcContext() {
        String currentContext = null;
        for (Map.Entry<Integer, String> entry : labelAddressToNameMap.entrySet()) {
            if (entry.getKey() > this.linearAddress) {
                break;
            }
            if (procMetaMap.containsKey(entry.getValue())) {
                currentContext = entry.getValue();
            }
        }
        return currentContext;
    }

    private Map<String, Integer> buildRegisterMapForCurrentContext() {
        String currentProcLabel = findCurrentProcContext();
        Map<String, Integer> regMap = new HashMap<>(this.registerMap);
        if (currentProcLabel != null) {
            DefinitionExtractor.ProcMeta cur = procMetaMap.get(currentProcLabel);
            if (cur != null) {
                if (cur.formalParams() != null) {
                    List<String> formals = cur.formalParams();
                    for (int i = 0; i < formals.size(); i++) {
                        regMap.put(formals.get(i).toUpperCase(), 2000 + i);
                    }
                }
                if (cur.pregAliases() != null) {
                    for (Map.Entry<String, Integer> e : cur.pregAliases().entrySet()) {
                        regMap.put(e.getKey().toUpperCase(), 1000 + e.getValue());
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

            // KORREKTUR: Akzeptiert "WITH" und ".WITH"
            int withIdx = -1;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equalsIgnoreCase("WITH") || args[i].equalsIgnoreCase(".WITH")) {
                    withIdx = i;
                    break;
                }
            }

            String target = (withIdx != -1) ? String.join(" ", Arrays.copyOfRange(args, 0, withIdx)) : String.join(" ", args);
            target = target.toUpperCase();

            java.util.function.Function<String, String> resolveTargetProc = (tok) ->
                    importAliasToProcName.getOrDefault(tok, tok);

            String procName = resolveTargetProc.apply(target);
            DefinitionExtractor.ProcMeta meta = procMetaMap.get(procName);
            boolean hasFormParams = meta != null && meta.formalParams() != null && !meta.formalParams().isEmpty();

            if (hasFormParams) {
                if (withIdx < 0) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("call.with.requiresWithProc"), line.content());
                }
                String[] actuals = Arrays.copyOfRange(args, withIdx + 1, args.length);
                int formalCount = meta.formalParams().size();
                if (actuals.length != formalCount) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(),
                            "CALL .WITH argument count mismatch: expected " + formalCount + " but got " + actuals.length, line.content());
                }
                int[] drIds = new int[formalCount];
                for (int i = 0; i < formalCount; i++) {
                    drIds[i] = resolveToRegisterId(actuals[i], line);
                }
                int[] callCoord = Arrays.copyOf(currentPos, currentPos.length);
                AssemblerOutput call = new AssemblerOutput.JumpInstructionRequest(target);
                emitInstruction(call, Instruction.getInstructionIdByName("CALL"), line);
                org.evochora.organism.instructions.ControlFlowInstruction.registerCallBindingForCoord(callCoord, drIds);
            } else {
                if (withIdx >= 0) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("call.with.usedOnDsProc"), line.content());
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
            AssemblerOutput output = Instruction.getAssemblerById(opcodeId).apply(args, regMapForAsm, labelMap);
            emitInstruction(output, opcodeId, line);

        } catch (IllegalArgumentException e) {
            throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), e.getMessage(), line.content());
        }
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

    private int resolveToRegisterId(Object operand, AnnotatedLine line) {
        if (operand instanceof Integer i) {
            return i;
        }
        String token = ((String) operand).toUpperCase();
        Map<String, Integer> regMap = buildRegisterMapForCurrentContext();
        Integer id = Instruction.resolveRegToken(token, regMap);
        if (id != null) return id;

        throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), "Invalid register token for .WITH: " + token, line.content());
    }

    private void placeSymbol(int value, AnnotatedLine line) {
        machineCodeLayout.put(Arrays.copyOf(currentPos, currentPos.length), value);
        linearAddressToCoordMap.put(linearAddress, Arrays.copyOf(currentPos, currentPos.length));
        coordToLinearAddressMap.put(Arrays.stream(currentPos).boxed().collect(Collectors.toList()), linearAddress);
        sourceMap.put(linearAddress, new SourceLocation(line.originalFileName(), line.originalLineNumber(), line.content()));

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