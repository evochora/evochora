package org.evochora.server.engine;

import org.evochora.runtime.Simulation;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.server.contracts.CellState;
import org.evochora.server.contracts.OrganismState;
import org.evochora.server.contracts.WorldStateMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds WorldStateMessage from the current Simulation state.
 */
final class WorldStateAdapter {

    static WorldStateMessage fromSimulation(Simulation simulation) {
        long tick = simulation.getCurrentTick();
        long tsMicros = Instant.now().toEpochMilli() * 1000;

        List<OrganismState> organisms = new ArrayList<>();
        for (Organism o : simulation.getOrganisms()) {
            if (o.isDead()) continue;
            List<Integer> position = toList(o.getIp());
            List<Integer> dp = toList(o.getDp());
            List<Integer> dv = toList(o.getDv());

            int[] drInts = toIntArray(o.getDrs(), Config.NUM_DATA_REGISTERS);
            int[] prInts = toIntArray(o.getPrs(), Config.NUM_PROC_REGISTERS);

            List<Integer> dataStack = toIntegerList(o.getDataStack());
            List<Integer> callStack = List.of(); // Simplified placeholder

            organisms.add(new OrganismState(
                    o.getId(),
                    o.getProgramId(),
                    o.getParentId(),
                    o.getBirthTick(),
                    o.getEr(),
                    position,
                    dp,
                    dv,
                    // state
                    // NOTE: ip is represented as flat? We expose only scalar ip index via 0 for now
                    // but keep ip field as the flat index of current primary dimension
                    o.getIp()[0],
                    0,
                    drInts,
                    prInts,
                    dataStack,
                    callStack
            ));
        }

        List<CellState> cells = toCellStates(simulation.getEnvironment());

        return new WorldStateMessage(tick, tsMicros, organisms, cells);
    }

    private static List<CellState> toCellStates(Environment env) {
        int[] shape = env.getShape();
        List<CellState> out = new ArrayList<>();
        // Iterate grid; currently Environment lacks direct grid iteration, so use nested loops
        int dims = shape.length;
        int[] coord = new int[dims];
        Arrays.fill(coord, 0);
        iterate(shape, 0, coord, () -> {
            Molecule m = env.getMolecule(coord);
            int packed = m.toInt();
            if (packed != 0) {
                int type = (packed & Config.TYPE_MASK) >>> Config.TYPE_SHIFT;
                int value = (packed & Config.VALUE_MASK);
                int ownerId = env.getOwnerId(coord);
                out.add(new CellState(toList(coord), type, value, ownerId));
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
            else if (o instanceof int[]) {
                // flatten vectors by adding their length as marker; this is a simplification
                list.add(0);
            }
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


