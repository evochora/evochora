// src/main/java/org/evochora/organism/actions/PokeAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;

public class PokeAction extends Action {
    private final int srcReg;
    private final int vecReg;
    private final int[] targetCoordinate; // Wird in planTick berechnet

    public PokeAction(Organism organism, int srcReg, int vecReg, int[] targetCoordinate) {
        super(organism);
        this.srcReg = srcReg;
        this.vecReg = vecReg;
        this.targetCoordinate = targetCoordinate;
    }

    public static Action plan(Organism organism, World world) {
        int src = organism.fetchArgument(world);
        int vec = organism.fetchArgument(world);
        Object v = organism.getDr(vec);
        if (v instanceof int[]) {
            int[] target = organism.getTargetCoordinate(organism.getDp(), (int[])v, world);
            return new PokeAction(organism, src, vec, target);
        }
        organism.instructionFailed();
        return new NopAction(organism);
    }

    @Override
    public void execute(Simulation simulation) {
        World world = simulation.getWorld();
        Object val = organism.getDr(srcReg);
        if (val instanceof Integer i) {
            if (world.getSymbol(targetCoordinate).isEmpty()) {
                world.setSymbol(Symbol.fromInt(i), targetCoordinate);
            } else {
                organism.instructionFailed(); // Zielzelle nicht leer
            }
        } else {
            organism.instructionFailed();
        }
    }

    public int[] getTargetCoordinate() {
        return targetCoordinate;
    }
}