// src/main/java/org/evochora/organism/actions/SeekAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;

public class SeekAction extends Action {
    private final int reg;

    public SeekAction(Organism organism, int reg) {
        super(organism);
        this.reg = reg;
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
                organism.instructionFailed(); // Zielzelle nicht leer
            }
        } else {
            organism.instructionFailed();
        }
    }
}