package org.evochora.server.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.api.DisassembledInstruction;
import org.evochora.runtime.internal.services.RuntimeDisassembler;
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
                // Disassembliere nur die EINE nächste Instruktion am aktuellen IP
                DisassembledInstruction disassembled = RuntimeDisassembler.INSTANCE.disassemble(o, artifact, simulation.getEnvironment());
                if (disassembled != null) {
                    disassembledJson = objectMapper.writeValueAsString(disassembled);
                }
            } catch (Exception e) {
                // Fehler ignorieren
            }

            organisms.add(new OrganismState(
                    o.getId(),
                    o.getProgramId(),
                    o.getParentId(),
                    o.getBirthTick(),
                    o.getEr(),
                    toList(o.getIp()),
                    toList(o.getDp()),
                    toList(o.getDv()),
                    o.getIp()[0],
                    o.getEr(),
                    toIntArray(o.getDrs(), Config.NUM_DATA_REGISTERS),
                    toIntArray(o.getPrs(), Config.NUM_PROC_REGISTERS),
                    toIntegerList(o.getDataStack()),
                    new ArrayList<>(),
                    disassembledJson
            ));
        }

        List<CellState> cells = toCellStates(simulation.getEnvironment());
        return new WorldStateMessage(tick, tsMicros, organisms, cells);
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

    private static List<Integer> toIntegerList(Iterable<?> iterable) {
        List<Integer> list = new ArrayList<>();
        for (Object o : iterable) {
            if (o instanceof Integer i) list.add(i);
            else if (o instanceof int[]) list.add(0);
        }
        return list;
    }

    private static int[] toIntArray(List<Object> regs, int expectedSize) {
        int[] out = new int[expectedSize];
        for (int i = 0; i < Math.min(expectedSize, regs.size()); i++) {
            Object v = regs.get(i);
            out[i] = (v instanceof Integer iv) ? iv : 0;
        }
        return out;
    }
}