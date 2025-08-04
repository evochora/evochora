// src/main/java/org/evochora/organism/ForkAction.java
package org.evochora.organism;

import org.evochora.Config;
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
            if (energy > 0 && organism.getEr() >= totalCost) {

                int[] childIp = organism.getTargetCoordinate(organism.getIpBeforeFetch(), delta, simulation.getWorld());

                organism.takeEr(totalCost);

                // GEÄNDERT: Logger über Getter weitergeben
                Organism child = Organism.create(simulation, childIp, energy, organism.getLogger()); // Jetzt über Getter zugreifen
                child.setDv(childDv);

                simulation.addNewOrganism(child);

                organism.setSkipIpAdvance(true);
            } else {
                if (energy <= 0) {
                    organism.instructionFailed("FORK: Child energy must be positive (is " + energy + ").");
                } else {
                    organism.instructionFailed("FORK: Insufficient energy (" + organism.getEr() + ") for cost " + totalCost + " (child energy " + energy + " + base cost).");
                }
            }
        } else {
            organism.instructionFailed("FORK: Invalid DR types. Delta (Reg " + deltaReg + ") must be int[], Energy (Reg " + energyReg + ") must be Integer, DV (Reg " + dvReg + ") must be int[]. Found: Delta " + (d != null ? d.getClass().getSimpleName() : "null") + ", Energy " + (e != null ? e.getClass().getSimpleName() : "null") + ", DV " + (dv != null ? dv.getClass().getSimpleName() : "null") + ".");
        }
    }
}