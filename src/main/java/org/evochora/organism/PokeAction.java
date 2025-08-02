// src/main/java/org/evochora/organism/actions/PokeAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import java.util.List;
import java.util.Map;

public class PokeAction extends Action {
    private final int srcReg, vecReg;
    private final int[] targetCoordinate;
    public PokeAction(Organism o, int sr, int vr, int[] tc) { super(o); this.srcReg = sr; this.vecReg = vr; this.targetCoordinate = tc; }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("POKE erwartet 2 Argumente: %REG_SRC %REG_VEC");
        return List.of(registerMap.get(args[0].toUpperCase()), registerMap.get(args[1].toUpperCase()));
    }

    public static Action plan(Organism organism, World world) {
        int src = organism.fetchArgument(world);
        int vec = organism.fetchArgument(world);
        Object v = organism.getDr(vec);
        if (v instanceof int[] vi) {
            int[] target = organism.getTargetCoordinate(organism.getDp(), vi, world);
            return new PokeAction(organism, src, vec, target);
        }
        organism.instructionFailed();
        return new NopAction(organism);
    }

    @Override
    public void execute(Simulation simulation) {
        World world = simulation.getWorld();
        Object val = organism.getDr(srcReg);
        if (val instanceof Integer i && world.getSymbol(targetCoordinate).isEmpty()) {
            world.setSymbol(Symbol.fromInt(i), targetCoordinate);
        } else {
            organism.instructionFailed();
        }
    }

    public int[] getTargetCoordinate() {
        return targetCoordinate;
    }
}