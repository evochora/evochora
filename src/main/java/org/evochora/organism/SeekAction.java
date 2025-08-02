// src/main/java/org/evochora/organism/actions/SeekAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.List;
import java.util.Map;

public class SeekAction extends Action {
    private final int reg;
    public SeekAction(Organism o, int r) { super(o); this.reg = r; }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("SEEK erwartet 1 Argument: %REG_VEC");
        return List.of(registerMap.get(args[0].toUpperCase()));
    }

    public static Action plan(Organism organism, World world) {
        return new SeekAction(organism, organism.fetchArgument(world));
    }

    @Override
    public void execute(Simulation simulation) {
        Object vec = organism.getDr(reg);
        if (vec instanceof int[] v) {
            int[] targetDp = organism.getTargetCoordinate(organism.getDp(), v, simulation.getWorld());
            if (simulation.getWorld().getSymbol(targetDp).isEmpty()) {
                organism.setDp(targetDp);
            } else {
                organism.instructionFailed();
            }
        } else {
            organism.instructionFailed();
        }
    }
}