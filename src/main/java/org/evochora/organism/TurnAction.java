// src/main/java/org/evochora/organism/actions/TurnAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.List;
import java.util.Map;

public class TurnAction extends Action {
    private final int reg;
    public TurnAction(Organism o, int r) { super(o); this.reg = r; }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("TURN erwartet 1 Argument: %REG_VEC");
        return List.of(registerMap.get(args[0].toUpperCase()));
    }

    public static Action plan(Organism organism, World world) {
        return new TurnAction(organism, organism.fetchArgument(world));
    }

    @Override
    public void execute(Simulation simulation) {
        Object newDv = organism.getDr(reg);
        if (newDv instanceof int[] v && v.length == organism.getDv().length) {
            organism.setDv(v);
        } else {
            organism.instructionFailed();
        }
    }
}