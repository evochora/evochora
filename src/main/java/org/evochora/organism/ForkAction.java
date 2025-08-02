// src/main/java/org/evochora/organism/ForkAction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.organism.Action;
import org.evochora.organism.Organism;
import org.evochora.Simulation;
import org.evochora.world.World;

import java.util.List;
import java.util.Map;
import java.util.Arrays;

public class ForkAction extends Action {
    private final int deltaReg;
    private final int energyReg;
    private final int dvReg;

    public ForkAction(Organism o, int dr, int er, int dvr) {
        super(o);
        this.deltaReg = dr;
        this.energyReg = er;
        this.dvReg = dvr;
    }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 3) throw new IllegalArgumentException("FORK erwartet 3 Argumente: %REG_DELTA %REG_ENERGY %REG_DV");
        return List.of(registerMap.get(args[0].toUpperCase()), registerMap.get(args[1].toUpperCase()), registerMap.get(args[2].toUpperCase()));
    }

    public static Action plan(Organism organism, World world) {
        int[] tempIp = Arrays.copyOf(organism.getIp(), organism.getIp().length);
        return new ForkAction(organism, organism.fetchArgument(tempIp, world), organism.fetchArgument(tempIp, world), organism.fetchArgument(tempIp, world));
    }

    @Override
    public void execute(Simulation simulation) {
        Object d = organism.getDr(deltaReg);
        Object e = organism.getDr(energyReg);
        Object dv = organism.getDr(dvReg);

        if (d instanceof int[] delta && e instanceof Integer energy && dv instanceof int[] childDv) {
            int totalCost = energy + Config.OPCODE_COSTS.get(Config.OP_FORK);
            if (energy > 0 && organism.getEr() > totalCost) {

                int[] childIp = organism.getTargetCoordinate(organism.getIpBeforeFetch(), delta, simulation.getWorld());

                organism.takeEr(totalCost);

                // KORRIGIERT: Erstelle zuerst das Organismus-Objekt
                Organism child = Organism.create(simulation, childIp, energy);
                child.setDv(childDv); // Setze den DV des Kindes

                // Füge das fertige Objekt hinzu
                simulation.addNewOrganism(child);

                organism.setSkipIpAdvance(true);
            } else {
                organism.instructionFailed();
            }
        } else {
            organism.instructionFailed();
        }
    }
}