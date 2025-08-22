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