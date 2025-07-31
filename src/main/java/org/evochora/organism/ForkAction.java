// src/main/java/org/evochora/organism/actions/ForkAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;

public class ForkAction extends Action {
    private final int deltaReg;
    private final int energyReg;
    private final int dvReg;

    public ForkAction(Organism organism, int deltaReg, int energyReg, int dvReg) {
        super(organism);
        this.deltaReg = deltaReg;
        this.energyReg = energyReg;
        this.dvReg = dvReg;
    }

    public static Action plan(Organism organism, World world) {
        return new ForkAction(organism, organism.fetchArgument(world), organism.fetchArgument(world), organism.fetchArgument(world));
    }

    @Override
    public void execute(Simulation simulation) {
        Object delta = organism.getDr(deltaReg);
        Object energy = organism.getDr(energyReg);
        Object childDv = organism.getDr(dvReg);

        if (delta instanceof int[] d && energy instanceof Integer e && childDv instanceof int[] cDv) {
            int[] childIp = organism.getTargetCoordinate(organism.getDp(), d, simulation.getWorld());
            if (e > 0 && organism.getEr() > e) {
                organism.takeEr(e);
                organism.setForkRequestData(childIp, e, cDv);
            } else {
                organism.instructionFailed();
            }
        } else {
            organism.instructionFailed();
        }
    }
}