// src/main/java/org/evochora/organism/actions/NrgAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.List;
import java.util.Map;

public class NrgAction extends Action {
    private final int targetReg;
    public NrgAction(Organism o, int tr) { super(o); this.targetReg = tr; }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("NRG erwartet 1 Argument: %REG_TARGET");
        return List.of(registerMap.get(args[0].toUpperCase()));
    }

    public static Action plan(Organism organism, World world) {
        return new NrgAction(organism, organism.fetchArgument(world));
    }

    @Override
    public void execute(Simulation simulation) {
        if (!organism.setDr(targetReg, organism.getEr())) {
            organism.instructionFailed();
        }
    }
}