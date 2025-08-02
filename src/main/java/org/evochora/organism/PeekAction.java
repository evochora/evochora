// src/main/java/org/evochora/organism/PeekAction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.organism.Action;
import org.evochora.organism.Organism;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class PeekAction extends Action {
    private final int targetReg, vecReg;
    public PeekAction(Organism o, int tr, int vr) { super(o); this.targetReg = tr; this.vecReg = vr; }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("PEEK erwartet 2 Argumente: %REG_TARGET %REG_VEC");
        return List.of(registerMap.get(args[0].toUpperCase()), registerMap.get(args[1].toUpperCase()));
    }

    public static Action plan(Organism organism, World world) {
        int[] tempIp = Arrays.copyOf(organism.getIp(), organism.getIp().length);
        return new PeekAction(organism, organism.fetchArgument(tempIp, world), organism.fetchArgument(tempIp, world));
    }

    @Override
    public void execute(Simulation simulation) {
        World world = simulation.getWorld();
        Object vec = organism.getDr(vecReg);
        if (vec instanceof int[] v) {
            // NEUE PRÜFUNG: Ist es ein gültiger Einheitsvektor?
            if (!organism.isUnitVector(v)) {
                organism.instructionFailed();
                return;
            }

            int[] target = organism.getTargetCoordinate(organism.getDp(), v, world);
            Symbol s = world.getSymbol(target);
            if (s.isEmpty()) { organism.instructionFailed(); return; }

            if (s.type() == Config.TYPE_ENERGY) {
                int energyToTake = Math.min(s.value(), Config.MAX_ORGANISM_ENERGY - organism.getEr());
                organism.addEr(energyToTake);
                world.setSymbol(new Symbol(Config.TYPE_ENERGY, s.value() - energyToTake), target);
            } else {
                organism.setDr(targetReg, s.toInt());
                world.setSymbol(new Symbol(Config.TYPE_DATA, 0), target);
            }
        } else {
            organism.instructionFailed();
        }
    }
}