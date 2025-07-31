// src/main/java/org/evochora/organism/actions/SetrAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;

public class SetrAction extends Action {
    private final int destReg;
    private final int srcReg;

    public SetrAction(Organism organism, int destReg, int srcReg) {
        super(organism);
        this.destReg = destReg;
        this.srcReg = srcReg;
    }

    public static Action plan(Organism organism, World world) {
        int dest = organism.fetchArgument(world);
        int src = organism.fetchArgument(world);
        return new SetrAction(organism, dest, src);
    }

    @Override
    public void execute(Simulation simulation) {
        Object value = organism.getDr(srcReg);
        if (value == null || !organism.setDr(destReg, value)) {
            organism.instructionFailed();
        }
    }
}