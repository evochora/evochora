// src/main/java/org/evochora/organism/actions/TurnAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;

public class TurnAction extends Action {
    private final int reg;

    public TurnAction(Organism organism, int reg) {
        super(organism);
        this.reg = reg;
    }

    public static Action plan(Organism organism, World world) {
        return new TurnAction(organism, organism.fetchArgument(world));
    }

    @Override
    public void execute(Simulation simulation) {
        Object newDv = organism.getDr(reg);
        if (newDv instanceof int[] v && v.length == organism.getDv().length) {
            organism.setDv(v);
        } else {
            organism.instructionFailed();
        }
    }
}