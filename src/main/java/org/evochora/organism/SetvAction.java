// src/main/java/org/evochora/organism/actions/SetvAction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.World;

public class SetvAction extends Action {
    private final int destReg;
    private final int[] values;

    public SetvAction(Organism organism, int destReg, int[] values) {
        super(organism);
        this.destReg = destReg;
        this.values = values;
    }

    public static Action plan(Organism organism, World world) {
        int reg = organism.fetchArgument(world);
        int[] vals = new int[Config.WORLD_DIMENSIONS];
        for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
            vals[i] = organism.fetchArgument(world);
        }
        return new SetvAction(organism, reg, vals);
    }

    @Override
    public void execute(Simulation simulation) {
        if (!organism.setDr(destReg, values)) {
            organism.instructionFailed();
        }
    }
}