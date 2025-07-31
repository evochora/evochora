// src/main/java/org/evochora/organism/actions/PeekAction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;

public class PeekAction extends Action {
    private final int targetReg;
    private final int vecReg;

    public PeekAction(Organism organism, int targetReg, int vecReg) {
        super(organism);
        this.targetReg = targetReg;
        this.vecReg = vecReg;
    }

    public static Action plan(Organism organism, World world) {
        return new PeekAction(organism, organism.fetchArgument(world), organism.fetchArgument(world));
    }

    @Override
    public void execute(Simulation simulation) {
        World world = simulation.getWorld();
        Object vec = organism.getDr(vecReg);
        if (vec instanceof int[] v) {
            int[] target = organism.getTargetCoordinate(organism.getDp(), v, world);
            Symbol s = world.getSymbol(target);

            if (s.type() == Config.TYPE_ENERGY) {
                int energyToTake = Math.min(s.value(), Config.MAX_ORGANISM_ENERGY - organism.getEr());
                organism.addEr(energyToTake);
                world.setSymbol(new Symbol(s.type(), s.value() - energyToTake), target);
            } else if (!s.isEmpty()) {
                organism.setDr(targetReg, s.toInt());
                world.setSymbol(new Symbol(Config.TYPE_DATA, 0), target); // Zelle leeren
            }
        } else {
            organism.instructionFailed();
        }
    }
}
