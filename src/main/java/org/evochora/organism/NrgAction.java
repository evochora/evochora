// src/main/java/org/evochora/organism/actions/NrgAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;

public class NrgAction extends Action {
    private final int targetReg;

    public NrgAction(Organism organism, int targetReg) {
        super(organism);
        this.targetReg = targetReg;
    }

    public static Action plan(Organism organism, World world) {
        return new NrgAction(organism, organism.fetchArgument(world));
    }

    @Override
    public void execute(Simulation simulation) {
        organism.setDr(targetReg, organism.getEr());
    }
}