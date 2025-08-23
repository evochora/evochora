package org.evochora.server.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.api.DisassembledInstruction;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.internal.services.RuntimeDisassembler;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.server.contracts.CellState;
import org.evochora.server.contracts.OrganismState;
import org.evochora.server.contracts.WorldStateMessage;
import org.evochora.server.contracts.PreparedTickState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Adapter class to convert the internal state of a {@link Simulation} into a serializable
 * {@link WorldStateMessage} for persistence and visualization.
 */
public final class WorldStateAdapter {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates a {@link WorldStateMessage} snapshot from the current state of the simulation.
     * <p>
     * In performance mode, expensive state conversions (like disassembly and detailed register formatting)
     * are skipped to minimize overhead.
     *
     * @param simulation The simulation instance to snapshot.
     * @return A message containing the complete world state for the current tick.
     */
    public static WorldStateMessage fromSimulation(Simulation simulation) {
        long tick = simulation.getCurrentTick();
        long tsMicros = java.time.Instant.now().toEpochMilli() * 1000;
        boolean perfMode = simulation.isPerformanceMode();

        Map<String, ProgramArtifact> artifacts = simulation.getProgramArtifacts();

        List<OrganismState> organisms = new ArrayList<>();
        for (Organism o : simulation.getOrganisms()) {
            if (o.isDead()) continue;

            ProgramArtifact artifact = artifacts.get(o.getProgramId());

            // Expensive operations are skipped in performance mode
            String disassembledJson = perfMode ? "{}" : getDisassembledJson(o, artifact, simulation.getEnvironment());
            List<String> callStackNames = perfMode ? List.of() : getCallStackWithParams(o, artifact, tick);
            List<String> formattedFprs = perfMode ? List.of() : formatFprs(o, artifact);
            List<String> drs = perfMode ? List.of() : toFormattedList(o.getDrs());
            List<String> prs = perfMode ? List.of() : toFormattedList(o.getPrs());
            List<String> ds = perfMode ? List.of() : toFormattedList(o.getDataStack());
            List<String> fprsRaw = perfMode ? List.of() : toFormattedList(o.getFprs());

            // NEW: Capture Location Registers and Stack only in debug mode
            List<String> lrs = perfMode ? List.of() : toFormattedList(o.getLrs());
            List<String> ls = perfMode ? List.of() : toFormattedList(o.getLocationStack());

            List<List<Integer>> dpsAsLists = o.getDps().stream().map(WorldStateAdapter::toList).collect(Collectors.toList());

            // Return IP from top call stack frame if present
            List<Integer> retIp = List.of();
            if (!o.getCallStack().isEmpty() && o.getCallStack().peek().absoluteReturnIp != null) {
                retIp = toList(o.getCallStack().peek().absoluteReturnIp);
            }

            organisms.add(new OrganismState(
                    o.getId(), o.getProgramId(), o.getParentId(), o.getBirthTick(), o.getEr(),
                    toList(o.getIp()), dpsAsLists, toList(o.getDv()), retIp,
                    o.getIp()[0], o.getEr(),
                    drs, prs, ds, callStackNames, formattedFprs, fprsRaw,
                    disassembledJson,
                    lrs, ls // NEW: Pass new fields to constructor
            ));
        }

        List<CellState> cells = toCellStates(simulation);
        return new WorldStateMessage(tick, tsMicros, organisms, cells);
    }

    /**
     * New API: Produces a fully prepared tick state for the web debugger.
     * Does not emit any HTML; formats values according to the agreed rules.
     */
    public static PreparedTickState toPreparedState(Simulation simulation) {
        boolean perfMode = simulation.isPerformanceMode();
        String mode = perfMode ? "performance" : "debug";
        long tick = simulation.getCurrentTick();

        // World meta
        int[] shape = simulation.getEnvironment().getShape();
        PreparedTickState.WorldMeta meta = new PreparedTickState.WorldMeta(shape);

        // Cells
        List<PreparedTickState.Cell> cells = new ArrayList<>();
        Environment env = simulation.getEnvironment();
        int dims = shape.length;
        int[] coord = new int[dims];
        Arrays.fill(coord, 0);
        iterate(shape, 0, coord, () -> {
            Molecule m = env.getMolecule(coord);
            int ownerId = env.getOwnerId(coord);
            if (m.toInt() != 0 || ownerId != 0) {
                int typeId = m.type() >>> Config.TYPE_SHIFT;
                String typeStr = typeIdToName(typeId);
                int value = m.toScalarValue();
                String opcodeName = null;
                if ("CODE".equals(typeStr) && value != 0) {
                    String name = org.evochora.runtime.isa.Instruction.getInstructionNameById(value);
                    opcodeName = name != null ? name : null;
                }
                cells.add(new PreparedTickState.Cell(toList(coord), typeStr, value, ownerId, opcodeName));
            }
        });

        // Organisms minimal list + details
        Map<String, ProgramArtifact> artifacts = simulation.getProgramArtifacts();
        List<PreparedTickState.OrganismBasic> orgBasics = new ArrayList<>();
        java.util.Map<String, PreparedTickState.OrganismDetails> details = new java.util.HashMap<>();

        for (Organism o : simulation.getOrganisms()) {
            if (o.isDead()) continue;
            List<List<Integer>> dps = o.getDps().stream().map(WorldStateAdapter::toList).collect(java.util.stream.Collectors.toList());
            orgBasics.add(new PreparedTickState.OrganismBasic(
                    o.getId(), o.getProgramId(), toList(o.getIp()), o.getEr(), dps, toList(o.getDv())));

            ProgramArtifact artifact = artifacts.get(o.getProgramId());

            PreparedTickState.BasicInfo bi = new PreparedTickState.BasicInfo(
                    o.getId(), o.getProgramId(), o.getParentId(), o.getBirthTick(),
                    o.getEr(), toList(o.getIp()), toList(o.getDv())
            );

            PreparedTickState.NextInstruction ni = buildNextInstruction(o, artifact, env);

            PreparedTickState.InternalState is = buildInternalState(o, artifact, perfMode);

            PreparedTickState.SourceView sv = null;
            if (!perfMode) {
                sv = buildSourceView(o, artifact, ni);
            }

            PreparedTickState.OrganismDetails od = new PreparedTickState.OrganismDetails(bi, ni, is, sv);
            details.put(String.valueOf(o.getId()), od);
        }

        PreparedTickState.WorldState ws = new PreparedTickState.WorldState(cells, orgBasics);
        return PreparedTickState.of(mode, tick, meta, ws, details);
    }

    private static PreparedTickState.NextInstruction buildNextInstruction(Organism o, ProgramArtifact artifact, Environment env) {
        try {
            if (artifact == null) return new PreparedTickState.NextInstruction("", null, null);
            ExecutionContext tempContext = new ExecutionContext(o, env, false);
            DisassembledInstruction di = RuntimeDisassembler.INSTANCE.disassemble(tempContext, artifact);
            if (di == null) return new PreparedTickState.NextInstruction("", null, null);
            StringBuilder sb = new StringBuilder();
            sb.append(di.opcodeName());
            if (di.arguments() != null && !di.arguments().isEmpty()) {
                sb.append(' ');
                List<String> argStrings = new ArrayList<>();
                for (var a : di.arguments()) {
                    if ("register".equalsIgnoreCase(a.type())) {
                        argStrings.add(a.name());
                    } else {
                        argStrings.add(expandTypePrefixes(a.fullDisplayValue()));
                    }
                }
                sb.append(String.join(" ", argStrings));
            }
            return new PreparedTickState.NextInstruction(sb.toString(), di.sourceFileName(), di.sourceLineNumber());
        } catch (Exception e) {
            return new PreparedTickState.NextInstruction("", null, null);
        }
    }

    private static PreparedTickState.InternalState buildInternalState(Organism o, ProgramArtifact artifact, boolean perfMode) {
        // DR/PR registers: show only values (no aliases)
        List<PreparedTickState.RegisterValue> drs = new ArrayList<>();
        List<PreparedTickState.RegisterValue> prs = new ArrayList<>();
        int drCount = o.getDrs().size();
        for (int i = 0; i < drCount; i++) {
            String id = String.valueOf(i);
            String value = toShortDisplay(o.getDr(i));
            drs.add(new PreparedTickState.RegisterValue(id, null, value));
        }
        int prCount = o.getPrs().size();
        for (int i = 0; i < prCount; i++) {
            String id = String.valueOf(i);
            String value = toShortDisplay(o.getPr(i));
            prs.add(new PreparedTickState.RegisterValue(id, null, value));
        }

        // Data stack
        List<String> dataStack = toFormattedList(o.getDataStack()).stream().map(WorldStateAdapter::shortenPrefixes).toList();

        // Call stack: in perf mode, names only; in debug mode, with param annotations
        List<String> callStack = new ArrayList<>();
        if (perfMode || artifact == null) {
            for (var f : o.getCallStack()) callStack.add(f.procName);
        } else {
            callStack = getCallStackWithParams(o, artifact, 0L).stream().map(WorldStateAdapter::shortenPrefixes).toList();
        }

        // Formal parameters (debug only)
        List<String> fprs = perfMode ? List.of() : formatFprs(o, artifact).stream().map(WorldStateAdapter::shortenPrefixes).toList();

        // Location registers/stacks (always include)
        List<String> lrs = toFormattedList(o.getLrs()).stream().map(WorldStateAdapter::shortenPrefixes).toList();
        List<String> ls = toFormattedList(o.getLocationStack()).stream().map(WorldStateAdapter::shortenPrefixes).toList();
        List<List<Integer>> dps = o.getDps().stream().map(WorldStateAdapter::toList).collect(java.util.stream.Collectors.toList());

        return new PreparedTickState.InternalState(drs, prs, dataStack, callStack, fprs, List.of(), lrs, ls, dps);
    }

    private static PreparedTickState.SourceView buildSourceView(Organism o, ProgramArtifact artifact, PreparedTickState.NextInstruction ni) {
        if (artifact == null || ni == null || ni.sourceFile() == null) return null;
        try {
            String file = ni.sourceFile();
            List<String> lines = artifact.sources().get(file);
            if (lines == null) {
                // Try basename
                String base = file.replace('\\', '/');
                int idx = base.lastIndexOf('/');
                if (idx >= 0) base = base.substring(idx + 1);
                lines = artifact.sources().get(base);
            }
            if (lines == null) return null;
            int current = ni.sourceLine() != null ? ni.sourceLine() : -1;
            List<PreparedTickState.SourceLine> outLines = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                int num = i + 1;
                boolean isCur = (num == current);
                outLines.add(new PreparedTickState.SourceLine(num, lines.get(i), isCur));
            }
            // Build inline annotations for current line using register aliases, canonical registers, defines, and jumps
            List<PreparedTickState.InlineSpan> spans = new ArrayList<>();
            if (current > 0 && current <= lines.size()) {
                String line = lines.get(current - 1);
                if (artifact != null && artifact.registerAliasMap() != null) {
                    for (Map.Entry<String, Integer> e : artifact.registerAliasMap().entrySet()) {
                        String alias = e.getKey();
                        int idxAlias = 0;
                        while (idxAlias >= 0) {
                            idxAlias = line.indexOf(alias, idxAlias);
                            if (idxAlias < 0) break;
                            // Resolve canonical name and value
                            int regId = e.getValue();
                            String canonical;
                            Object valObj;
                            if (regId < Instruction.PR_BASE) {
                                canonical = "%DR" + regId;
                                valObj = o.getDr(regId);
                            } else if (regId < Instruction.FPR_BASE) {
                                canonical = "%PR" + (regId - Instruction.PR_BASE);
                                valObj = o.getPr(regId - Instruction.PR_BASE);
                            } else {
                                canonical = "%FPR" + (regId - Instruction.FPR_BASE);
                                valObj = o.getFpr(regId - Instruction.FPR_BASE);
                            }
                            String value = toShortDisplay(valObj);
                            String text = canonical + "=" + value;
                            // Insert after alias token
                            int startColumn = idxAlias + alias.length();
                            spans.add(new PreparedTickState.InlineSpan(current, startColumn + 1, 0, text, "reg"));
                            idxAlias = idxAlias + alias.length();
                        }
                    }
                }
                // Annotate canonical register tokens %DRn/%PRn/%FPRn
                java.util.regex.Pattern regTok = java.util.regex.Pattern.compile("%(?:DR|PR|FPR)(?:\\d+)");
                java.util.regex.Matcher m = regTok.matcher(line);
                while (m.find()) {
                    String token = m.group();
                    int start = m.end();
                    String text;
                    try {
                        int id;
                        if (token.startsWith("%FPR")) id = Instruction.FPR_BASE + Integer.parseInt(token.substring(4));
                        else if (token.startsWith("%PR")) id = Instruction.PR_BASE + Integer.parseInt(token.substring(3));
                        else id = Integer.parseInt(token.substring(3));
                        Object val = o.readOperand(id);
                        text = shortenPrefixes(Molecule.fromInt((val instanceof Integer i)? i : 0).toString());
                    } catch (Exception ex) {
                        text = "";
                    }
                    if (!text.isEmpty()) spans.add(new PreparedTickState.InlineSpan(current, start + 1, 0, text, "reg"));
                }
                // Annotate macro-like defines by pairing tokens with disassembled arguments
                try {
                    ExecutionContext tempContext = new ExecutionContext(o, o.getSimulation().getEnvironment(), false);
                    DisassembledInstruction di = RuntimeDisassembler.INSTANCE.disassemble(tempContext, artifact);
                    if (di != null && di.arguments() != null && !di.arguments().isEmpty()) {
                        String noLeading = line.replaceFirst("^\\s*", "");
                        String[] parts = noLeading.split("\\s+", 2);
                        String rest = parts.length > 1 ? parts[1] : "";
                        java.util.List<String> tokens = java.util.Arrays.stream(rest.split("\\s+")).filter(s -> !s.isBlank()).toList();
                        int argIdx = 0;
                        for (int ti = 0; ti < tokens.size() && argIdx < di.arguments().size(); ti++) {
                            var a = di.arguments().get(argIdx);
                            String tok = tokens.get(ti);
                            if (a.type().equalsIgnoreCase("register")) {
                                // will be annotated by register logic
                            } else {
                                // annotate define/constant token with value
                                int idx = line.indexOf(tok);
                                if (idx >= 0) {
                                    spans.add(new PreparedTickState.InlineSpan(current, idx + tok.length() + 1, 0, shortenPrefixes(a.fullDisplayValue()), "define"));
                                    argIdx++;
                                }
                            }
                        }
                        // Annotate jumps/calls with destination
                        if (di.opcodeName() != null && (di.opcodeName().startsWith("JMP") || di.opcodeName().equals("CALL"))) {
                            for (var a : di.arguments()) {
                                if ("label".equalsIgnoreCase(a.type()) || "vector".equalsIgnoreCase(a.type())) {
                                    String dest = shortenPrefixes(a.fullDisplayValue());
                                    // place after label token only (avoid end-of-line duplication)
                                    String labelTok = a.name() != null ? a.name() : "";
                                    int idx = !labelTok.isEmpty() ? line.indexOf(labelTok) : -1;
                                    int col = idx >= 0 ? idx + labelTok.length() + 1 : line.length() + 1;
                                    spans.add(new PreparedTickState.InlineSpan(current, col, 0, dest, di.opcodeName().equals("CALL") ? "callJump" : "jump"));
                                    break;
                                }
                            }
                        }
                        // Pre-call parameter: if previous line is PUSH (or equivalent), attach a callParam kind
                        try {
                            if (di.opcodeName() != null && di.opcodeName().equals("CALL") && current > 1) {
                                String prev = lines.get(current - 2);
                                if (prev.trim().toUpperCase().startsWith("PUSH")) {
                                    int insertAt = prev.length() + 1;
                                    spans.add(new PreparedTickState.InlineSpan(current - 1, insertAt, 0, "PARAM", "callParam"));
                                }
                            }
                        } catch (Exception ignore) {}
                    }
                } catch (Exception ignore) {}
            }
            return new PreparedTickState.SourceView(file, current, outLines, spans);
        } catch (Exception e) {
            return null;
        }
    }

    private static String resolveAlias(ProgramArtifact artifact, String regCanonicalName) {
        if (artifact == null || artifact.registerAliasMap() == null) return null;
        for (var e : artifact.registerAliasMap().entrySet()) {
            if (regCanonicalName.equals(e.getKey())) return e.getKey();
        }
        // artifact.registerAliasMap maps alias -> regId (int). We need reverse lookup.
        try {
            for (var e : artifact.registerAliasMap().entrySet()) {
                String alias = e.getKey();
                Integer id = e.getValue();
                String canonical;
                if (id < Instruction.PR_BASE) canonical = "%DR" + id;
                else if (id < Instruction.FPR_BASE) canonical = "%PR" + (id - Instruction.PR_BASE);
                else canonical = "%FPR" + (id - Instruction.FPR_BASE);
                if (canonical.equals(regCanonicalName)) return alias;
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static String typeIdToName(int typeId) {
        if (typeId == (Config.TYPE_CODE >>> Config.TYPE_SHIFT)) return "CODE";
        if (typeId == (Config.TYPE_DATA >>> Config.TYPE_SHIFT)) return "DATA";
        if (typeId == (Config.TYPE_ENERGY >>> Config.TYPE_SHIFT)) return "ENERGY";
        if (typeId == (Config.TYPE_STRUCTURE >>> Config.TYPE_SHIFT)) return "STRUCTURE";
        // Fallback: map unknown to DATA
        return "DATA";
    }

    private static String expandTypePrefixes(String value) {
        if (value == null) return null;
        return value
                .replace("C:", "CODE:")
                .replace("D:", "DATA:")
                .replace("E:", "ENERGY:")
                .replace("S:", "STRUCTURE:");
    }

    private static String shortenPrefixes(String value) {
        if (value == null) return null;
        return value
                .replace("CODE:", "C:")
                .replace("DATA:", "D:")
                .replace("ENERGY:", "E:")
                .replace("STRUCTURE:", "S:");
    }

    private static String toShortDisplay(Object obj) {
        if (obj instanceof int[] vec) {
            return Arrays.stream(vec).mapToObj(String::valueOf).collect(Collectors.joining("|", "[", "]"));
        }
        if (obj instanceof Integer i) {
            String full = Molecule.fromInt(i).toString();
            return shortenPrefixes(full);
        }
        return obj != null ? obj.toString() : "null";
    }

    private static String getDisassembledJson(Organism o, ProgramArtifact artifact, Environment env) {
        if (artifact == null) return "{}";
        try {
            ExecutionContext tempContext = new ExecutionContext(o, env, false);
            DisassembledInstruction disassembled = RuntimeDisassembler.INSTANCE.disassemble(tempContext, artifact);
            if (disassembled != null) {
                return objectMapper.writeValueAsString(disassembled);
            }
        } catch (Exception e) { /* Ignore */ }
        return "{}";
    }

    private static List<String> getCallStackWithParams(Organism organism, ProgramArtifact artifact, long tick) {
        if (artifact == null) {
            return organism.getCallStack().stream().map(f -> f.procName).collect(Collectors.toList());
        }
        List<Organism.ProcFrame> frames = new ArrayList<>(organism.getCallStack());

        List<String> out = new ArrayList<>();

        java.util.function.BiFunction<Integer, Integer, Integer> resolveBoundRegId = (frameIdx, fprIndex) -> {
            int idx = frameIdx + 1;
            int currentRegId = Instruction.FPR_BASE + fprIndex;
            while (idx < frames.size()) {
                Organism.ProcFrame fr = frames.get(idx);
                Integer mapped = fr.fprBindings.get(currentRegId);
                if (mapped == null) break;
                currentRegId = mapped;
                if (currentRegId < Instruction.FPR_BASE) break;
                int nextFprIdx = currentRegId - Instruction.FPR_BASE;
                idx++;
                currentRegId = Instruction.FPR_BASE + nextFprIdx;
            }
            return currentRegId;
        };

        for (int fi = frames.size() - 1; fi >= 0; fi--) {
            Organism.ProcFrame frame = frames.get(fi);
            String procName = frame.procName;
            List<String> paramNames = artifact.procNameToParamNames().get(procName != null ? procName.toUpperCase() : null);
            if ((paramNames == null || paramNames.isEmpty()) && procName != null) {
                int dot = procName.lastIndexOf('.');
                if (dot > 0 && dot < procName.length() - 1) {
                    String shortName = procName.substring(dot + 1).toUpperCase();
                    paramNames = artifact.procNameToParamNames().get(shortName);
                }
            }
            if (paramNames == null || paramNames.isEmpty()) {
                out.add(procName);
                continue;
            }
            StringBuilder sb = new StringBuilder(procName);
            for (int pi = 0; pi < paramNames.size(); pi++) {
                Integer boundRegId = frame.fprBindings.get(Instruction.FPR_BASE + pi);
                int finalRegId;
                if (boundRegId == null) {
                    finalRegId = resolveBoundRegId.apply(fi, pi);
                } else {
                    finalRegId = boundRegId;
                }
                if (finalRegId >= Instruction.FPR_BASE) {
                    finalRegId = resolveBoundRegId.apply(fi, finalRegId - Instruction.FPR_BASE);
                }
                String boundName;
                Object value;
                if (finalRegId < Instruction.PR_BASE) {
                    boundName = "%DR" + finalRegId;
                    value = organism.readOperand(finalRegId);
                } else if (finalRegId < Instruction.FPR_BASE) {
                    boundName = "%PR" + (finalRegId - Instruction.PR_BASE);
                    value = organism.readOperand(finalRegId);
                } else {
                    int fidx = finalRegId - Instruction.FPR_BASE;
                    boundName = "%FPR" + fidx;
                    value = organism.getFpr(fidx);
                }
                sb.append(' ').append(paramNames.get(pi)).append('[').append(boundName).append("=").append(formatObject(value)).append(']');
            }
            out.add(0, sb.toString());
        }
        return out;
    }

    private static String formatObject(Object obj) {
        if (obj instanceof int[] vec) {
            return Arrays.stream(vec).mapToObj(String::valueOf).collect(Collectors.joining("|", "[", "]"));
        }
        if (obj instanceof Integer i) {
            return Molecule.fromInt(i).toString();
        }
        return obj != null ? obj.toString() : "null";
    }

    private static List<String> toFormattedList(Iterable<?> iterable) {
        List<String> list = new ArrayList<>();
        for (Object o : iterable) {
            list.add(formatObject(o));
        }
        return list;
    }

    private static List<String> formatFprs(Organism organism, ProgramArtifact artifact) {
        if (organism.getCallStack().isEmpty() || artifact == null) {
            return List.of();
        }

        java.util.Deque<Organism.ProcFrame> stack = organism.getCallStack();
        Organism.ProcFrame topFrame = stack.peek();
        String procName = topFrame.procName;

        if (procName == null || "UNKNOWN".equals(procName)) {
            return List.of();
        }

        List<String> paramNames = artifact.procNameToParamNames().get(procName != null ? procName.toUpperCase() : null);
        if ((paramNames == null || paramNames.isEmpty()) && procName != null) {
            int dot = procName.lastIndexOf('.');
            if (dot > 0 && dot < procName.length() - 1) {
                String shortName = procName.substring(dot + 1).toUpperCase();
                paramNames = artifact.procNameToParamNames().get(shortName);
            }
        }
        if (paramNames == null || paramNames.isEmpty()) {
            return List.of();
        }

        // Build a random-access list of frames, with index 0 = top frame, increasing = older
        List<Organism.ProcFrame> frames = new ArrayList<>(stack);

        // Helper to resolve FPR chains forward through older frames until DR/PR
        java.util.function.BiFunction<Integer, Integer, Integer> resolveBoundRegId = (startFrameIdx, fprIndex) -> {
            int idx = startFrameIdx + 1; // search in older frames
            int currentRegId = Instruction.FPR_BASE + fprIndex;
            while (idx < frames.size()) {
                Organism.ProcFrame fr = frames.get(idx);
                Integer mapped = fr.fprBindings.get(currentRegId);
                if (mapped == null) break;
                currentRegId = mapped;
                if (currentRegId < Instruction.FPR_BASE) break; // reached DR/PR
                int nextFprIdx = currentRegId - Instruction.FPR_BASE;
                idx++;
                currentRegId = Instruction.FPR_BASE + nextFprIdx;
            }
            return currentRegId;
        };

        List<String> formatted = new ArrayList<>();
        for (int i = 0; i < paramNames.size(); i++) {
            String paramName = paramNames.get(i);
            Integer boundRegId = topFrame.fprBindings.get(Instruction.FPR_BASE + i);
            int finalRegId;
            if (boundRegId == null) {
                finalRegId = resolveBoundRegId.apply(0, i);
            } else {
                finalRegId = boundRegId;
            }
            if (finalRegId >= Instruction.FPR_BASE) {
                finalRegId = resolveBoundRegId.apply(0, finalRegId - Instruction.FPR_BASE);
            }

            Object value;
            String boundName;
            if (finalRegId < Instruction.PR_BASE) {
                boundName = "%DR" + finalRegId;
                value = organism.readOperand(finalRegId);
            } else if (finalRegId < Instruction.FPR_BASE) {
                boundName = "%PR" + (finalRegId - Instruction.PR_BASE);
                value = organism.readOperand(finalRegId);
            } else {
                int fidx = finalRegId - Instruction.FPR_BASE;
                boundName = "%FPR" + fidx;
                value = organism.getFpr(fidx);
            }
            formatted.add(String.format("%s[%s]=%s", paramName, boundName, formatObject(value)));
        }
        return formatted;
    }

    private static List<CellState> toCellStates(Simulation simulation) {
        Environment env = simulation.getEnvironment();
        int[] shape = env.getShape();
        List<CellState> out = new ArrayList<>();
        java.util.Set<java.util.List<Integer>> seen = new java.util.HashSet<>();
        int dims = shape.length;
        int[] coord = new int[dims];
        Arrays.fill(coord, 0);
        iterate(shape, 0, coord, () -> {
            Molecule m = env.getMolecule(coord);
            int ownerId = env.getOwnerId(coord);
            // Include cell if non-empty OR has an owner; ensures ownership is visible at tick 0
            if (m.toInt() != 0 || ownerId != 0) {
                int typeId = m.type() >>> Config.TYPE_SHIFT;
                int value = m.toScalarValue();
                java.util.List<Integer> pos = toList(coord);
                out.add(new CellState(pos, typeId, value, ownerId));
                seen.add(pos);
            }
        });

        // Ensure organism footprints (machine code and initial world objects) appear with ownership even if env owner grid not yet set
        Map<String, ProgramArtifact> artifacts = simulation.getProgramArtifacts();
        if (artifacts != null && !artifacts.isEmpty()) {
            for (var o : simulation.getOrganisms()) {
                ProgramArtifact pa = artifacts.get(o.getProgramId());
                if (pa == null) continue;
                int[] origin = o.getInitialPosition();
                // Machine code layout
                for (var e : pa.machineCodeLayout().entrySet()) {
                    int[] rel = e.getKey();
                    int[] abs = new int[dims];
                    for (int i = 0; i < dims; i++) abs[i] = origin[i] + rel[i];
                    java.util.List<Integer> pos = toList(abs);
                    if (seen.contains(pos)) continue;
                    Molecule m = env.getMolecule(abs);
                    int typeId = m.type() >>> Config.TYPE_SHIFT;
                    int value = m.toScalarValue();
                    int ownerId = env.getOwnerId(abs);
                    if (ownerId == 0) ownerId = o.getId();
                    out.add(new CellState(pos, typeId, value, ownerId));
                    seen.add(pos);
                }
                // Initial world objects
                for (var e : pa.initialWorldObjects().entrySet()) {
                    int[] rel = e.getKey();
                    int[] abs = new int[dims];
                    for (int i = 0; i < dims; i++) abs[i] = origin[i] + rel[i];
                    java.util.List<Integer> pos = toList(abs);
                    if (seen.contains(pos)) continue;
                    Molecule m = env.getMolecule(abs);
                    int typeId = m.type() >>> Config.TYPE_SHIFT;
                    int value = m.toScalarValue();
                    int ownerId = env.getOwnerId(abs);
                    if (ownerId == 0) ownerId = o.getId();
                    out.add(new CellState(pos, typeId, value, ownerId));
                    seen.add(pos);
                }
            }
        }
        return out;
    }

    private static void iterate(int[] shape, int dim, int[] coord, Runnable visitor) {
        if (dim == shape.length) {
            visitor.run();
            return;
        }
        for (int i = 0; i < shape[dim]; i++) {
            coord[dim] = i;
            iterate(shape, dim + 1, coord, visitor);
        }
    }

    private static List<Integer> toList(int[] arr) {
        List<Integer> list = new ArrayList<>(arr.length);
        for (int v : arr) list.add(v);
        return list;
    }
}