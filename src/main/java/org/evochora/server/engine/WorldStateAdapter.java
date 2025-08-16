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

        Map<String, ProgramArtifact> artifacts = simulation.getProgramArtifacts();

        List<OrganismState> organisms = new ArrayList<>();
        for (Organism o : simulation.getOrganisms()) {
            if (o.isDead()) continue;

            String disassembledJson = "{}";
            try {
                ProgramArtifact artifact = artifacts.get(o.getProgramId());
                DisassembledInstruction disassembled = RuntimeDisassembler.INSTANCE.disassemble(o, artifact, simulation.getEnvironment());
                if (disassembled != null) {
                    disassembledJson = objectMapper.writeValueAsString(disassembled);
                }
            } catch (Exception e) {
                // Fehler ignorieren
            }

            // NEU: Call Stack und FPRs aufbereiten
            List<String> callStackNames = o.getCallStack().stream()
                    .map(frame -> frame.procName)
                    .collect(Collectors.toList());

            List<String> formattedFprs = formatFprs(o, artifacts.get(o.getProgramId()));


            organisms.add(new OrganismState(
                    o.getId(),
                    o.getProgramId(),
                    o.getParentId(),
                    o.getBirthTick(),
                    o.getEr(),
                    toList(o.getIp()),
                    toList(o.getDp()),
                    toList(o.getDv()),
                    o.getIp()[0], // ip als int ist redundant, bleibt für Kompatibilität
                    o.getEr(), // er als int ist redundant, bleibt für Kompatibilität
                    toFormattedList(o.getDrs()),
                    toFormattedList(o.getPrs()),
                    toFormattedList(o.getDataStack()),
                    callStackNames,
                    formattedFprs,
                    disassembledJson
            ));
        }

        List<CellState> cells = toCellStates(simulation.getEnvironment());
        return new WorldStateMessage(tick, tsMicros, organisms, cells);
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

        // Finde Prozedur-Metadaten im Artefakt (benötigt Anpassung, wenn Metadaten vorhanden)
        // HINWEIS: ProgramArtifact enthält keine ProcMetaMap, dies ist ein vereinfachter Ansatz.
        // Wir können die Parameteranzahl aus den Bindungen ableiten.
        if (procName == null || procName.equals("UNKNOWN") || topFrame.fprBindings.isEmpty()) {
            return List.of();
        }

        List<String> formatted = new ArrayList<>();
        int arity = topFrame.fprBindings.size();

        for (int i = 0; i < arity; i++) {
            Object value = organism.getFpr(i);
            Integer boundRegId = topFrame.fprBindings.get(Instruction.FPR_BASE + i);
            String boundName = "??";
            if (boundRegId != null) {
                if (boundRegId < Instruction.PR_BASE) {
                    boundName = "%DR" + boundRegId;
                } else if (boundRegId < Instruction.FPR_BASE) {
                    boundName = "%PR" + (boundRegId - Instruction.PR_BASE);
                } else {
                    boundName = "%FPR" + (boundRegId - Instruction.FPR_BASE);
                }
            }
            formatted.add(String.format("%%FPR%d[%s]=%s", i, boundName, formatObject(value)));
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