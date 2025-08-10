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

                // Track label-defined proc context: if label equals a known proc name, enter its context
                if (strippedLine.endsWith(":")) {
                    String label = strippedLine.substring(0, strippedLine.length() - 1).toUpperCase();
                    currentProcLabel = (procMetaMap != null && procMetaMap.containsKey(label)) ? label : currentProcLabel;
                } else if (head.equals(".ORG")) {
                    currentPos = Arrays.stream(parts[1].split("\\|")).mapToInt(Integer::parseInt).toArray();
                } else if (head.equals(".DIR")) {
                    currentDv = Arrays.stream(parts[1].split("\\|")).mapToInt(Integer::parseInt).toArray();
                } else if (head.equals(".PROC")) {
                    // Enter PROC context by name in .PROC <name> [WITH ...]
                    // parts: [".PROC", "<name>", "WITH", ...]
                    if (parts.length >= 2) {
                        String procName = parts[1].toUpperCase();
                        if (procMetaMap != null && procMetaMap.containsKey(procName)) {
                            currentProcLabel = procName;
                        }
                    }
                } else if (head.equals(".ENDP") || head.equals(".ENP")) {
                    // Leave PROC context
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

    private void processInstructionSecondPass(AnnotatedLine line) {
        String strippedLine = line.content().split("#", 2)[0].strip();
        String[] parts = strippedLine.split("\\s+", 2);
        String command = parts[0].toUpperCase();
        String[] args = (parts.length > 1) ? parts[1].split("\\s+") : new String[0];

        for (int i = 0; i < args.length; i++) {
            args[i] = defineMap.getOrDefault(args[i].toUpperCase(), args[i]);
        }

        // Special handling for CALL (register-ABI enforcement and .WITH adapters)
        if ("CALL".equals(command) && args.length > 0) {
            int withIdx = -1;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equalsIgnoreCase(".WITH")) { withIdx = i; break; }
            }

            // Helper: resolve a CALL target token to a procName by alias or direct kernel label
            java.util.function.Function<String, String> resolveTargetProc = (tok) -> {
                String t = tok.toUpperCase();
                // Alias mapping
                if (importAliasToProcName != null && importAliasToProcName.containsKey(t)) {
                    return importAliasToProcName.get(t);
                }
                // Direct kernel label
                if (procMetaMap != null && procMetaMap.containsKey(t)) {
                    return t;
                }
                return null;
            };

            // If no .WITH: enforce it for register-ABI procs (declared WITH), for aliases or direct kernel names
            if (withIdx < 0) {
                String target = args[0].toUpperCase();
                String procName = resolveTargetProc.apply(target);
                if (procName != null) {
                    DefinitionExtractor.ProcMeta meta = procMetaMap != null ? procMetaMap.get(procName) : null;
                    List<String> formals = (meta != null && meta.formalParams() != null) ? meta.formalParams() : List.of();
                    if (!formals.isEmpty()) {
                        // Target has WITH formals → .WITH binding is required
                        String msg = Messages.get("call.with.requiresWithProc") + " (requires .WITH)";
                        throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), msg, line.content());
                    }
                }
                // Fall through to normal CALL emission for DS-ABI or non-proc calls
            }

            if (withIdx >= 0) {
                if (withIdx == 0) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("call.with.arityMismatch"), line.content());
                }
                String target = args[0].toUpperCase();
                String procName = resolveTargetProc.apply(target);
                if (procName == null) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("import.procNotFound", target), line.content());
                }
                DefinitionExtractor.ProcMeta meta = procMetaMap != null ? procMetaMap.get(procName) : null;
                if (meta == null) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("import.procNotFound", procName), line.content());
                }
                List<String> formals = meta.formalParams() != null ? meta.formalParams() : List.of();
                String[] actuals = Arrays.copyOfRange(args, withIdx + 1, args.length);
                if (formals.isEmpty()) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("call.with.usedOnDsProc"), line.content());
                }
                if (actuals.length != formals.size()) {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("call.with.arityMismatch"), line.content());
                }
                for (String a : actuals) {
                    boolean ok = a.startsWith("%");
                    if (!ok && currentProcLabel != null && procMetaMap != null) {
                        DefinitionExtractor.ProcMeta cur = procMetaMap.get(currentProcLabel);
                        if (cur != null && cur.formalParams() != null) {
                            ok = cur.formalParams().stream().anyMatch(f -> f.equalsIgnoreCase(a));
                        }
                    }
                    if (!ok) {
                        throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("call.with.nonRegisterArg", a), line.content());
                    }
                }

                // Optional warning if duplicate actuals are used (aliasing): last formal wins
                {
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    java.util.Set<String> dups = new java.util.HashSet<>();
                    for (String a : actuals) {
                        String key = a.toUpperCase();
                        if (!seen.add(key)) dups.add(key);
                    }
                    if (!dups.isEmpty()) {
                        System.out.println(Messages.get("call.with.duplicateActuals",
                                target, String.join(", ", dups)));
                    }
                }

                // Helper lambdas to resolve register tokens
                java.util.function.Function<String,Integer> resolveActual = (tok) -> {
                    String u = tok.toUpperCase();
                    // Accept %PR0 / %PR1 directly (PR actuals)
                    if ("%PR0".equals(u)) return 1000;
                    if ("%PR1".equals(u)) return 1001;

                    // Accept %DRN
                    if (u.startsWith("%DR")) {
                        try {
                            int idx = Integer.parseInt(u.substring(3));
                            return idx;
                        } catch (NumberFormatException ignore) {
                            // fall-through
                        }
                    }

                    // Accept .REG aliases (DR) from registerMap
                    Integer id = registerMap.get(u);
                    if (id != null) return id;

                    // Accept PROC formals (e.g., X) in current PROC → map to DR slot
                    if (currentProcLabel != null && procMetaMap != null) {
                        DefinitionExtractor.ProcMeta cur = procMetaMap.get(currentProcLabel);
                        if (cur != null) {
                            // .PREG aliases local to current PROC (PR0/PR1)
                            if (cur.pregAliases() != null) {
                                Integer prIdx = cur.pregAliases().get(u);
                                if (prIdx != null) return 1000 + prIdx;
                            }
                            // Formal names → DR slot indices
                            if (cur.formalParams() != null) {
                                for (int i = 0; i < cur.formalParams().size(); i++) {
                                    if (cur.formalParams().get(i).equalsIgnoreCase(u)) {
                                        return i; // DR slot of this formal
                                    }
                                }
                            }
                        }
                    }

                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("call.with.nonRegisterArg", tok), line.content());
                };
                java.util.function.Function<Integer,Integer> asDataWord = (id) -> new Symbol(Config.TYPE_DATA, id).toInt();

                // Helper to emit a single instruction by opcode full id and data args,
                // while also updating linearAddress<->coord/source mappings for each emitted cell.
                java.util.function.BiConsumer<Integer, java.util.List<Integer>> emitCodeSeq = (opcodeFullId, dataWords) -> {
                    // Map and place opcode at currentPos
                    linearAddressToCoordMap.put(linearAddress, Arrays.copyOf(currentPos, currentPos.length));
                    coordToLinearAddressMap.put(Arrays.stream(currentPos).boxed().collect(java.util.stream.Collectors.toList()), linearAddress);
                    sourceMap.put(linearAddress, new SourceLocation(line.originalFileName(), line.originalLineNumber(), line.content()));
                    machineCodeLayout.put(Arrays.copyOf(currentPos, currentPos.length), opcodeFullId);

                    // Prepare arg position (next cell along DV)
                    int[] argPos = Arrays.copyOf(currentPos, currentPos.length);
                    for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) argPos[d] += currentDv[d];

                    // Advance linearAddress/currentPos for the opcode we just placed
                    for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) currentPos[d] += currentDv[d];
                    linearAddress++;

                    // Map and place each argument
                    for (int val : dataWords) {
                        linearAddressToCoordMap.put(linearAddress, Arrays.copyOf(argPos, argPos.length));
                        coordToLinearAddressMap.put(Arrays.stream(argPos).boxed().collect(java.util.stream.Collectors.toList()), linearAddress);
                        sourceMap.put(linearAddress, new SourceLocation(line.originalFileName(), line.originalLineNumber(), line.content()));
                        machineCodeLayout.put(Arrays.copyOf(argPos, argPos.length), val);
                        // advance argPos to next cell
                        for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) argPos[d] += currentDv[d];
                        // advance state
                        for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) currentPos[d] += currentDv[d];
                        linearAddress++;
                    }
                };

                // Copy-in: SETR formal[%DRi] := actual[i] (elide no-ops)
                int setrFull = Config.TYPE_CODE | Instruction.getInstructionIdByName("SETR");
                for (int i = 0; i < formals.size(); i++) {
                    int destFormalReg = i; // %DRi
                    int srcActualId = resolveActual.apply(actuals[i]);
                    // If actual already lives in the same physical slot as the formal, skip the move
                    if (srcActualId != destFormalReg) {
                        emitCodeSeq.accept(setrFull, java.util.List.of(asDataWord.apply(destFormalReg), asDataWord.apply(srcActualId)));
                    }
                }

                // Emit CALL to the kernel entry (procName) directly, not the alias
                {
                    Integer callId = Instruction.getInstructionIdByName("CALL");
                    AssemblerOutput out = Instruction.getAssemblerById(Config.TYPE_CODE | callId).apply(new String[]{procName}, registerMap, labelMap);
                    int instructionLength = Instruction.getInstructionLengthById(Config.TYPE_CODE | callId);

                    // Remember opcode linear address for placeholder source
                    int callStartLinear = linearAddress;

                    // Map and place CALL opcode
                    linearAddressToCoordMap.put(linearAddress, Arrays.copyOf(currentPos, currentPos.length));
                    coordToLinearAddressMap.put(Arrays.stream(currentPos).boxed().collect(java.util.stream.Collectors.toList()), linearAddress);
                    sourceMap.put(linearAddress, new SourceLocation(line.originalFileName(), line.originalLineNumber(), line.content()));
                    machineCodeLayout.put(Arrays.copyOf(currentPos, currentPos.length), Config.TYPE_CODE | callId);

                    // Prepare arg position and advance state for opcode
                    int[] argPos = Arrays.copyOf(currentPos, currentPos.length);
                    for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) argPos[d] += currentDv[d];
                    for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) currentPos[d] += currentDv[d];
                    linearAddress++;

                    switch (out) {
                        case AssemblerOutput.CodeSequence code -> {
                            // Map and place each arg from code
                            for (int val : code.machineCode()) {
                                linearAddressToCoordMap.put(linearAddress, Arrays.copyOf(argPos, argPos.length));
                                coordToLinearAddressMap.put(Arrays.stream(argPos).boxed().collect(java.util.stream.Collectors.toList()), linearAddress);
                                sourceMap.put(linearAddress, new SourceLocation(line.originalFileName(), line.originalLineNumber(), line.content()));
                                machineCodeLayout.put(Arrays.copyOf(argPos, argPos.length), val);
                                for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) argPos[d] += currentDv[d];
                                for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) currentPos[d] += currentDv[d];
                                linearAddress++;
                            }
                        }
                        case AssemblerOutput.JumpInstructionRequest req -> {
                            // Map and place placeholder zeros for the CALL arguments (as DATA)
                            int argsToPlace = instructionLength - 1;
                            for (int i = 0; i < argsToPlace; i++) {
                                linearAddressToCoordMap.put(linearAddress, Arrays.copyOf(argPos, argPos.length));
                                coordToLinearAddressMap.put(Arrays.stream(argPos).boxed().collect(java.util.stream.Collectors.toList()), linearAddress);
                                sourceMap.put(linearAddress, new SourceLocation(line.originalFileName(), line.originalLineNumber(), line.content()));
                                machineCodeLayout.put(Arrays.copyOf(argPos, argPos.length), new Symbol(Config.TYPE_DATA, 0).toInt());
                                for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) argPos[d] += currentDv[d];
                                for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) currentPos[d] += currentDv[d];
                                linearAddress++;
                            }
                            // Register jump placeholder with the CALL opcode linear address
                            jumpPlaceholders.add(new PlaceholderResolver.JumpPlaceholder(callStartLinear, req.labelName(), line));
                        }
                        default -> {
                            throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("passManager.unexpectedOutputTypeInPass2", out.getClass().getSimpleName()), line.content());
                        }
                    }
                }

                // Copy-back: SETR actual[i] := formal[%DRi] (elide no-ops)
                for (int i = 0; i < formals.size(); i++) {
                    int srcFormalReg = i; // %DRi
                    int destActualId = resolveActual.apply(actuals[i]);
                    // If actual lives in the same physical slot as the formal, skip the move
                    if (destActualId != srcFormalReg) {
                        emitCodeSeq.accept(setrFull, java.util.List.of(asDataWord.apply(destActualId), asDataWord.apply(srcFormalReg)));
                    }
                }

                // Done handling this line
                return;
            }
        }

        try {
            Integer opcodeId = Instruction.getInstructionIdByName(command);
            if (opcodeId == null) throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("passManager.unknownInstruction", command), line.content());

            // Build effective register map for this line:
            // - start with global .REG map
            // - add current PROC formals (A,B,...) -> DR slot indices (0,1,...)
            // - add current PROC .PREG aliases (%NAME) -> PR pseudo ids (1000+idx)
            Map<String, Integer> regMapForAsm = new HashMap<>(registerMap);
            if (currentProcLabel != null && procMetaMap != null) {
                DefinitionExtractor.ProcMeta cur = procMetaMap.get(currentProcLabel);
                if (cur != null) {
                    List<String> formals = cur.formalParams() != null ? cur.formalParams() : List.of();
                    for (int i = 0; i < formals.size(); i++) {
                        regMapForAsm.put(formals.get(i).toUpperCase(), i);
                    }
                    if (cur.pregAliases() != null) {
                        for (Map.Entry<String, Integer> e : cur.pregAliases().entrySet()) {
                            regMapForAsm.put(e.getKey().toUpperCase(), 1000 + e.getValue());
                        }
                    }
                }
            }

            AssemblerOutput output = Instruction.getAssemblerById(Config.TYPE_CODE | opcodeId).apply(args, regMapForAsm, labelMap);
            int instructionLength = Instruction.getInstructionLengthById(Config.TYPE_CODE | opcodeId);

            // Save opcode linear address for placeholders
            int opcodeLinear = linearAddress;

            // Map and place opcode
            linearAddressToCoordMap.put(linearAddress, Arrays.copyOf(currentPos, currentPos.length));
            coordToLinearAddressMap.put(Arrays.stream(currentPos).boxed().collect(java.util.stream.Collectors.toList()), linearAddress);
            sourceMap.put(linearAddress, new SourceLocation(line.originalFileName(), line.originalLineNumber(), line.content()));
            machineCodeLayout.put(Arrays.copyOf(currentPos, currentPos.length), Config.TYPE_CODE | opcodeId);

            // Prepare arg position and advance state for opcode
            int[] argPos = Arrays.copyOf(currentPos, currentPos.length);
            for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) argPos[d] += currentDv[d];
            for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) currentPos[d] += currentDv[d];
            linearAddress++;

            switch (output) {
                case AssemblerOutput.CodeSequence code -> {
                    for (int val : code.machineCode()) {
                        // Map and place each argument
                        linearAddressToCoordMap.put(linearAddress, Arrays.copyOf(argPos, argPos.length));
                        coordToLinearAddressMap.put(Arrays.stream(argPos).boxed().collect(java.util.stream.Collectors.toList()), linearAddress);
                        sourceMap.put(linearAddress, new SourceLocation(line.originalFileName(), line.originalLineNumber(), line.content()));
                        machineCodeLayout.put(Arrays.copyOf(argPos, argPos.length), val);
                        // advance argPos and state
                        for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) argPos[d] += currentDv[d];
                        for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) currentPos[d] += currentDv[d];
                        linearAddress++;
                    }
                }
                case AssemblerOutput.JumpInstructionRequest req -> {
                    // Place and map zero placeholders for arguments (as DATA)
                    int argsToPlace = instructionLength - 1;
                    for (int i = 0; i < argsToPlace; i++) {
                        linearAddressToCoordMap.put(linearAddress, Arrays.copyOf(argPos, argPos.length));
                        coordToLinearAddressMap.put(Arrays.stream(argPos).boxed().collect(java.util.stream.Collectors.toList()), linearAddress);
                        sourceMap.put(linearAddress, new SourceLocation(line.originalFileName(), line.originalLineNumber(), line.content()));
                        machineCodeLayout.put(Arrays.copyOf(argPos, argPos.length), new Symbol(Config.TYPE_DATA, 0).toInt());
                        // advance argPos and state
                        for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) argPos[d] += currentDv[d];
                        for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) currentPos[d] += currentDv[d];
                        linearAddress++;
                    }
                    // Register placeholder at the opcode address
                    jumpPlaceholders.add(new PlaceholderResolver.JumpPlaceholder(opcodeLinear, req.labelName(), line));
                }
                case AssemblerOutput.LabelToVectorRequest req -> {
                    // First data word: register id argument
                    linearAddressToCoordMap.put(linearAddress, Arrays.copyOf(argPos, argPos.length));
                    coordToLinearAddressMap.put(Arrays.stream(argPos).boxed().collect(java.util.stream.Collectors.toList()), linearAddress);
                    sourceMap.put(linearAddress, new SourceLocation(line.originalFileName(), line.originalLineNumber(), line.content()));
                    machineCodeLayout.put(Arrays.copyOf(argPos, argPos.length), new Symbol(Config.TYPE_DATA, req.registerId()).toInt());
                    // advance argPos/state
                    for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) argPos[d] += currentDv[d];
                    for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) currentPos[d] += currentDv[d];
                    linearAddress++;

                    // Remaining placeholders (instructionLength - 2), write DATA zeros
                    int remaining = instructionLength - 2;
                    for (int i = 0; i < remaining; i++) {
                        linearAddressToCoordMap.put(linearAddress, Arrays.copyOf(argPos, argPos.length));
                        coordToLinearAddressMap.put(Arrays.stream(argPos).boxed().collect(java.util.stream.Collectors.toList()), linearAddress);
                        sourceMap.put(linearAddress, new SourceLocation(line.originalFileName(), line.originalLineNumber(), line.content()));
                        machineCodeLayout.put(Arrays.copyOf(argPos, argPos.length), new Symbol(Config.TYPE_DATA, 0).toInt());
                        for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) argPos[d] += currentDv[d];
                        for (int d = 0; d < Config.WORLD_DIMENSIONS; d++) currentPos[d] += currentDv[d];
                        linearAddress++;
                    }

                    vectorPlaceholders.add(new PlaceholderResolver.VectorPlaceholder(opcodeLinear, req.labelName(), req.registerId(), line));
                }
                default -> {
                    throw new AssemblerException(programName, line.originalFileName(), line.originalLineNumber(), Messages.get("passManager.unexpectedOutputTypeInPass2", output.getClass().getSimpleName()), line.content());
                }
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