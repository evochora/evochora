// src/main/java/org/evochora/organism/ForkInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.World;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.assembler.ArgumentType;
import org.evochora.world.Symbol;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

public class ForkInstruction extends Instruction {
    public static final int ID = 18;

    private final int deltaReg;
    private final int energyReg;
    private final int dvReg;

    public ForkInstruction(Organism o, int dr, int er, int dvr) {
        super(o);
        this.deltaReg = dr;
        this.energyReg = er;
        this.dvReg = dvr;
    }

    static {
        Instruction.registerInstruction(ForkInstruction.class, ID, "FORK", 4, ForkInstruction::plan, ForkInstruction::assemble);
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.REGISTER, 2, ArgumentType.REGISTER));
    }

    @Override
    public String getName() {
        return "FORK";
    }

    @Override
    public int getLength() {
        return 4;
    }

    @Override
    protected int getFixedBaseCost() {
        return 10;
    }

    @Override
    public int getCost(Organism organism, World world, List<Integer> rawArguments) {
        int cost = getFixedBaseCost();

        Object energyValObj = organism.getDr(energyReg);
        if (energyValObj instanceof Integer childEnergy) {
            cost += childEnergy;
        }

        return cost;
    }

    @Override
    public ArgumentType getArgumentType(int argIndex) {
        return switch (argIndex) {
            case 0, 1, 2 -> ArgumentType.REGISTER;
            default -> throw new IllegalArgumentException("Ungültiger Argumentindex für FORK: " + argIndex);
        };
    }

    public static Instruction plan(Organism organism, World world) {
        // GEÄNDERT: Neue Logik mit FetchResult für drei Argumente
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int dr = result1.value();

        Organism.FetchResult result2 = organism.fetchArgument(result1.nextIp(), world);
        int er = result2.value();

        Organism.FetchResult result3 = organism.fetchArgument(result2.nextIp(), world);
        int dvr = result3.value();

        return new ForkInstruction(organism, dr, er, dvr);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 3) {
            throw new IllegalArgumentException("FORK erwartet 3 Argumente: %REG_DELTA %REG_ENERGY %REG_DV");
        }

        Integer deltaRegId = registerMap.get(args[0].toUpperCase());
        if (deltaRegId == null) {
            throw new IllegalArgumentException("Ungültiges Register-Argument für Delta: " + args[0]);
        }
        Integer energyRegId = registerMap.get(args[1].toUpperCase());
        if (energyRegId == null) {
            throw new IllegalArgumentException("Ungültiges Register-Argument für Energie: " + args[1]);
        }
        Integer dvRegId = registerMap.get(args[2].toUpperCase());
        if (dvRegId == null) {
            throw new IllegalArgumentException("Ungültiges Register-Argument für DV: " + args[2]);
        }


        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, deltaRegId).toInt(),
                new Symbol(Config.TYPE_DATA, energyRegId).toInt(),
                new Symbol(Config.TYPE_DATA, dvRegId).toInt()
        ));
    }

    @Override
    public void execute(Simulation simulation) {
        Object deltaObj = organism.getDr(deltaReg);
        Object energyObj = organism.getDr(energyReg);
        Object dvObj = organism.getDr(dvReg);

        if (!(deltaObj instanceof int[] delta)) {
            organism.instructionFailed("FORK: Delta (Reg " + deltaReg + ") muss ein Vektor sein (Found: " + (deltaObj != null ? deltaObj.getClass().getSimpleName() : "null") + ").");
            return;
        }

        if (!(energyObj instanceof Integer energy)) {
            organism.instructionFailed("FORK: Energie (Reg " + energyReg + ") muss ein Integer sein (Found: " + (energyObj != null ? energyObj.getClass().getSimpleName() : "null") + ").");
            return;
        }

        if (!(dvObj instanceof int[] childDv)) {
            organism.instructionFailed("FORK: DV (Reg " + dvReg + ") muss ein Vektor sein (Found: " + (dvObj != null ? dvObj.getClass().getSimpleName() : "null") + ").");
            return;
        }

        int totalCost = getCost(organism, simulation.getWorld(), null);

        if (energy > 0 && organism.getEr() >= totalCost) {
            int[] childIp = organism.getTargetCoordinate(organism.getIpBeforeFetch(), delta, simulation.getWorld());

            organism.takeEr(totalCost);

            Organism child = Organism.create(simulation, childIp, energy, organism.getLogger());
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
    }
}