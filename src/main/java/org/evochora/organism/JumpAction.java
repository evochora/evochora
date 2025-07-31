// src/main/java/org/evochora/organism/actions/JumpAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;

public class JumpAction extends Action {
    private final int reg;

    public JumpAction(Organism organism, int reg) {
        super(organism);
        this.reg = reg;
    }

    public static Action plan(Organism organism, World world) {
        return new JumpAction(organism, organism.fetchArgument(world));
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