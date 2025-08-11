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

/**
 * Manages phases 3 & 4 of the assembler: the classic two-pass process
 * to calculate label addresses and generate machine code. This refactored
 * version uses the Strategy pattern to delegate directive handling.
 */
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
    private String currentProcLabel = null;

    // Strategy Pattern: Map of directive handlers
    private final Map<String, IDirectiveHandler> directiveHandlers = new HashMap<>();

    public PassManager(String programName, Map<String, String> defineMap,
                       Map<String, DefinitionExtractor.ProcMeta> procMetaMap,
                       Map<String, String> importAliasToProcName) {
        this.programName = programName;
        this.defineMap = defineMap;
        this.procMetaMap = procMetaMap != null ? procMetaMap : Map.of();
        this.importAliasToProcName = importAliasToProcName != null ? importAliasToProcName : Map.of();

        // Register all directive handlers for the first pass
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
                    currentPos = Arrays.stream(parts[1].split("\\|")).mapToInt(org.evochora.assembler.NumericParser::parseInt).toArray();
                } else if (head.equals(".DIR")) {
                    currentDv = Arrays.stream(parts[1].split("\\|")).mapToInt(org.evochora.assembler.NumericParser::parseInt).toArray();
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
                    // Resolve actual DR register IDs for VM-internal binding
                    String[] actuals = java.util.Arrays.copyOfRange(args, withIdx + 1, args.length);
                    int formalCount = meta.formalParams().size();
                    if (actuals.length != formalCount) {
                        throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(),
                                "CALL .WITH argument count mismatch: expected " + formalCount + " but got " + actuals.length, line.content());
                    }
                    int[] drIds = new int[formalCount];
                    for (int i = 0; i < formalCount; i++) {
                        drIds[i] = resolveToRegisterId(actuals[i], line);
                    }
                    // Capture CALL opcode coordinate before emission
                    int[] callCoord = java.util.Arrays.copyOf(currentPos, currentPos.length);
                    // Emit the actual call (no extra machine code for binding)
                    AssemblerOutput call = new AssemblerOutput.JumpInstructionRequest(target);
                    emitInstruction(call, Instruction.getInstructionIdByName("CALL"), line);
                    // Register binding for VM to use at runtime
                    org.evochora.organism.instructions.ControlFlowInstruction.registerCallBindingForCoord(callCoord, drIds);
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
        // Resolve destination and source to concrete register IDs
        int destId = resolveToRegisterId(dest, line);
        int srcId = resolveToRegisterId(src, line);

        // Emit SETR arguments directly to avoid assembler-side register token issues
        AssemblerOutput output = new AssemblerOutput.CodeSequence(java.util.List.of(
                new org.evochora.world.Symbol(org.evochora.Config.TYPE_DATA, destId).toInt(),
                new org.evochora.world.Symbol(org.evochora.Config.TYPE_DATA, srcId).toInt()
        ));
        emitInstruction(output, org.evochora.organism.Instruction.getInstructionIdByName("SETR"), line);
    }

    /**
     * Resolves a register operand (String token or Integer FPR ID) into a concrete register ID.
     * Supports %DRx, %PRx, %FPRn, bare formal names within the current PROC, and direct Integer IDs (e.g., FPR base + index).
     */
    private int resolveToRegisterId(Object operand, AnnotatedLine line) {
        if (operand instanceof Integer i) {
            return i;
        }
        String token = ((String) operand).toUpperCase();

        // Prefer the current context register map (handles %DRx, %PRx, and formals if provided)
        java.util.Map<String, Integer> regMap = buildRegisterMapForCurrentContext();
        Integer id = org.evochora.organism.Instruction.resolveRegToken(token, regMap);
        if (id != null) return id;

        // Fallback: bare formal name resolution within the current PROC
        if (currentProcLabel != null) {
            DefinitionExtractor.ProcMeta meta = procMetaMap.get(currentProcLabel);
            if (meta != null && meta.formalParams() != null) {
                java.util.List<String> formals = meta.formalParams();
                for (int i = 0; i < formals.size(); i++) {
                    if (formals.get(i).equalsIgnoreCase(token)) {
                        return 2000 + i; // FPR base + index
                    }
                }
            }
        }

        // Fallback: explicit %FPRn token
        if (token.startsWith("%FPR")) {
            try {
                int n = Integer.parseInt(token.substring(4));
                return 2000 + n;
            } catch (NumberFormatException ignored) {
                // fall through to error
            }
        }

        throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), "Invalid register for SETR.", line.content());
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

    // --- GETTERS ---
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