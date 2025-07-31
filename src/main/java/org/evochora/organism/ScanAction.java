// src/main/java/org/evochora/organism/actions/ScanAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;

public class ScanAction extends Action {
    private final int targetReg;
    private final int vecReg;

    public ScanAction(Organism organism, int targetReg, int vecReg) {
        super(organism);
        this.targetReg = targetReg;
        this.vecReg = vecReg;
    }

    public static Action plan(Organism organism, World world) {
        return new ScanAction(organism, organism.fetchArgument(world), organism.fetchArgument(world));
    }

    @Override
    public void execute(Simulation simulation) {
        Object vec = organism.getDr(vecReg);
        if(vec instanceof int[] v) {
            int[] target = organism.getTargetCoordinate(organism.getDp(), v, simulation.getWorld());
            organism.setDr(targetReg, simulation.getWorld().getSymbol(target).toInt());
        } else {
            organism.instructionFailed();
        }
    }
}