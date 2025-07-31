// src/main/java/org/evochora/organism/actions/NandAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;

public class NandAction extends Action {
    private final int reg1;
    private final int reg2;

    public NandAction(Organism organism, int reg1, int reg2) {
        super(organism);
        this.reg1 = reg1;
        this.reg2 = reg2;
    }

    public static Action plan(Organism organism, World world) {
        int r1 = organism.fetchArgument(world);
        int r2 = organism.fetchArgument(world);
        return new NandAction(organism, r1, r2);
    }

    @Override
    public void execute(Simulation simulation) {
        Object val1 = organism.getDr(reg1);
        Object val2 = organism.getDr(reg2);
        if (val1 instanceof Integer v1 && val2 instanceof Integer v2) {
            organism.setDr(reg1, ~(v1 & v2));
        } else {
            organism.instructionFailed();
        }
    }
}