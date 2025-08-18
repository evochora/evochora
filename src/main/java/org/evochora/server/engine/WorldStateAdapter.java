package org.evochora.server.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.api.DisassembledInstruction;
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

final class WorldStateAdapter {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    static WorldStateMessage fromSimulation(Simulation simulation) {
        long tick = simulation.getCurrentTick();
        long tsMicros = java.time.Instant.now().toEpochMilli() * 1000;
        boolean perfMode = simulation.isPerformanceMode();

        Map<String, ProgramArtifact> artifacts = simulation.getProgramArtifacts();

        List<OrganismState> organisms = new ArrayList<>();
        for (Organism o : simulation.getOrganisms()) {
            if (o.isDead()) continue;

            // Im Performance-Modus werden alle teuren Operationen übersprungen
            String disassembledJson = perfMode ? "{}" : getDisassembledJson(o, artifacts, simulation.getEnvironment());
            List<String> callStackNames = perfMode ? List.of() : getCallStackWithParams(o, artifacts.get(o.getProgramId()));
            List<String> formattedFprs = perfMode ? List.of() : formatFprs(o, artifacts.get(o.getProgramId()));
            List<String> drs = perfMode ? List.of() : toFormattedList(o.getDrs());
            List<String> prs = perfMode ? List.of() : toFormattedList(o.getPrs());
            List<String> ds = perfMode ? List.of() : toFormattedList(o.getDataStack());
            List<String> fprsRaw = perfMode ? List.of() : toFormattedList(o.getFprs());

            organisms.add(new OrganismState(
                    o.getId(), o.getProgramId(), o.getParentId(), o.getBirthTick(), o.getEr(),
                    toList(o.getIp()), toList(o.getDp()), toList(o.getDv()),
                    o.getIp()[0], o.getEr(),
                    drs, prs, ds, callStackNames, formattedFprs, fprsRaw,
                    disassembledJson
            ));
        }

        List<CellState> cells = toCellStates(simulation.getEnvironment());
        return new WorldStateMessage(tick, tsMicros, organisms, cells);
    }

    private static String getDisassembledJson(Organism o, Map<String, ProgramArtifact> artifacts, Environment env) {
        try {
            ProgramArtifact artifact = artifacts.get(o.getProgramId());
            DisassembledInstruction disassembled = RuntimeDisassembler.INSTANCE.disassemble(o, artifact, env);
            if (disassembled != null) {
                return objectMapper.writeValueAsString(disassembled);
            }
        } catch (Exception e) { /* Ignorieren */ }
        return "{}";
    }

    private static List<String> getCallStackWithParams(Organism organism, ProgramArtifact artifact) {
        if (artifact == null) {
            return organism.getCallStack().stream().map(f -> f.procName).collect(Collectors.toList());
        }
        List<String> out = new ArrayList<>();
        for (Organism.ProcFrame frame : organism.getCallStack()) {
            String procName = frame.procName;
            List<String> paramNames = artifact.procNameToParamNames().get(procName != null ? procName.toUpperCase() : null);
            if (paramNames == null || paramNames.isEmpty()) {
                out.add(procName);
                continue;
            }
            StringBuilder sb = new StringBuilder(procName);
            for (int i = 0; i < paramNames.size(); i++) {
                Integer boundRegId = frame.fprBindings.get(Instruction.FPR_BASE + i);
                String boundName;
                Object value;
                if (boundRegId == null) {
                    boundName = "%FPR" + i;
                    // Werte aus FPR lesen, aber Anzeige priorisiert DR/PR Namensbindung
                    value = organism.getFpr(i);
                } else if (boundRegId < Instruction.PR_BASE) {
                    boundName = "%DR" + boundRegId;
                    value = organism.readOperand(boundRegId);
                } else if (boundRegId < Instruction.FPR_BASE) {
                    boundName = "%PR" + (boundRegId - Instruction.PR_BASE);
                    value = organism.readOperand(boundRegId);
                } else {
                    boundName = "%FPR" + (boundRegId - Instruction.FPR_BASE);
                    value = organism.getFpr(boundRegId - Instruction.FPR_BASE);
                }
                // Duplikate verhindern: Parameternamen pro Frame nur einmal
                sb.append(' ').append(paramNames.get(i)).append('[').append(boundName).append("=").append(formatObject(value)).append(']');
            }
            out.add(sb.toString());
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

        Organism.ProcFrame topFrame = organism.getCallStack().peek();
        String procName = topFrame.procName;

        if (procName == null || "UNKNOWN".equals(procName)) {
            return List.of();
        }

        List<String> paramNames = artifact.procNameToParamNames().get(procName.toUpperCase());
        if (paramNames == null || paramNames.isEmpty()) {
            return List.of();
        }

        List<String> formatted = new ArrayList<>();
        for (int i = 0; i < paramNames.size(); i++) {
            String paramName = paramNames.get(i);
            Integer boundRegId = topFrame.fprBindings.get(Instruction.FPR_BASE + i);
            Object value = (boundRegId != null) ? organism.readOperand(boundRegId) : organism.getFpr(i);
            String boundName;
            if (boundRegId == null) {
                // Kein DR/PR gebunden – zeige FPR explizit (%FPRi)
                boundName = "%FPR" + i;
            } else if (boundRegId < Instruction.PR_BASE) {
                // DR gebunden
                boundName = "%DR" + boundRegId;
            } else if (boundRegId < Instruction.FPR_BASE) {
                // PR gebunden
                boundName = "%PR" + (boundRegId - Instruction.PR_BASE);
            } else {
                // FPR gebunden (eher theoretisch)
                boundName = "%FPR" + (boundRegId - Instruction.FPR_BASE);
            }
            formatted.add(String.format("%s[%s]=%s", paramName, boundName, formatObject(value)));
        }
        return formatted;
    }

    private static List<CellState> toCellStates(Environment env) {
        int[] shape = env.getShape();
        List<CellState> out = new ArrayList<>();
        int dims = shape.length;
        int[] coord = new int[dims];
        Arrays.fill(coord, 0);
        iterate(shape, 0, coord, () -> {
            Molecule m = env.getMolecule(coord);
            if (m.toInt() != 0) {
                int typeId = m.type() >>> Config.TYPE_SHIFT;
                int value = m.toScalarValue();
                int ownerId = env.getOwnerId(coord);
                out.add(new CellState(toList(coord), typeId, value, ownerId));
            }
        });
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
