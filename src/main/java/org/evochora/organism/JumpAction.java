// src/main/java/org/evochora/organism/JumpAction.java
package org.evochora.organism;

import org.evochora.organism.Action;
import org.evochora.organism.Organism;
import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class JumpAction extends Action {
    private final int reg;
    public JumpAction(Organism o, int r) { super(o); this.reg = r; }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("JUMP erwartet 1 Argument: %REG_DELTA");
        return List.of(registerMap.get(args[0].toUpperCase()));
    }

    public static Action plan(Organism organism, World world) {
        int[] tempIp = Arrays.copyOf(organism.getIp(), organism.getIp().length);
        return new JumpAction(organism, organism.fetchArgument(tempIp, world));
    }

    @Override
    public void execute(Simulation simulation) {
        Object delta = organism.getDr(reg);
        if (delta instanceof int[] v) {
            int[] targetIp = organism.getTargetCoordinate(organism.getIpBeforeFetch(), v, simulation.getWorld());
            organism.setIp(targetIp);
            organism.setSkipIpAdvance(true);
        } else {
            organism.instructionFailed();
        }
    }
}