// src/main/java/org/evochora/organism/actions/SetlAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;

public class SetlAction extends Action {
    private final int registerIndex;
    private final int literalValue;

    public SetlAction(Organism organism, int registerIndex, int literalValue) {
        super(organism);
        this.registerIndex = registerIndex;
        this.literalValue = literalValue;
    }

    public static Action plan(Organism organism, World world) {
        int regIdx = organism.fetchArgument(world);
        int literal = organism.fetchArgument(world);
        return new SetlAction(organism, regIdx, literal);
    }

    @Override
    public void execute(Simulation simulation) {
        if (!organism.setDr(registerIndex, literalValue)) {
            organism.instructionFailed();
        }
    }
}