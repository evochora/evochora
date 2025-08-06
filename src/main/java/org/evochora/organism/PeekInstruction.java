// src/main/java/org/evochora/organism/PeekInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.assembler.ArgumentType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public class PeekInstruction extends Instruction implements IWorldModifyingInstruction {
    public static final int ID = 14;

    private final int targetReg, vecReg;
    private final int[] targetCoordinate;

    public PeekInstruction(Organism organism, int tr, int vr, int[] tc) {
        super(organism);
        this.targetReg = tr;
        this.vecReg = vr;
        this.targetCoordinate = tc;
    }

    static {
        Instruction.registerInstruction(PeekInstruction.class, ID, "PEEK", 3, PeekInstruction::plan, PeekInstruction::assemble);
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.REGISTER));
    }

    @Override
    public String getName() {
        return "PEEK";
    }

    @Override
    public int getLength() {
        return 3;
    }

    @Override
    protected int getFixedBaseCost() {
        return 1;
    }

    @Override
    public int getCost(Organism organism, World world, List<Integer> rawArguments) {
        int cost = getFixedBaseCost();
        Symbol targetSymbol = world.getSymbol(targetCoordinate);
        if (targetSymbol.type() == Config.TYPE_STRUCTURE) {
            cost += Math.abs(targetSymbol.toScalarValue());
        }
        return cost;
    }

    @Override
    public ArgumentType getArgumentType(int argIndex) {
        return switch (argIndex) {
            case 0, 1 -> ArgumentType.REGISTER;
            default -> throw new IllegalArgumentException("Ungültiger Argumentindex für PEEK: " + argIndex);
        };
    }

    public static Instruction plan(Organism organism, World world) {
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int targetReg = result1.value();

        Organism.FetchResult result2 = organism.fetchArgument(result1.nextIp(), world);
        int vecReg = result2.value();

        Object vec = organism.getDr(vecReg);
        if (vec instanceof int[] v) {
            if (!organism.isUnitVector(v)) {
                return new NopInstruction(organism);
            }
            int[] target = organism.getTargetCoordinate(organism.getDp(), v, world);
            return new PeekInstruction(organism, targetReg, vecReg, target);
        } else {
            organism.instructionFailed("PEEK: Invalid DR type for vector (Reg " + vec + "). Expected int[], found " + (vec != null ? vec.getClass().getSimpleName() : "null") + ".");
            return new NopInstruction(organism);
        }
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) {
            throw new IllegalArgumentException("PEEK erwartet 2 Register-Argumente: %REG_TARGET %REG_VEC");
        }

        Integer targetRegId = registerMap.get(args[0].toUpperCase());
        Integer vecRegId = registerMap.get(args[1].toUpperCase());

        if (targetRegId == null || vecRegId == null) {
            throw new IllegalArgumentException(String.format("Ungültiges Register-Argument. Target: '%s', Vec: '%s'", args[0], args[1]));
        }

        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, targetRegId).toInt(),
                new Symbol(Config.TYPE_DATA, vecRegId).toInt()
        ));
    }

    @Override
    public void execute(Simulation simulation) {
        World world = simulation.getWorld();
        Object vecObj = organism.getDr(vecReg);
        if (!(vecObj instanceof int[] v) || !organism.isUnitVector(v)) {
            return;
        }

        Symbol s = world.getSymbol(targetCoordinate);

        if (this.getConflictStatus() == ConflictResolutionStatus.WON_EXECUTION || this.getConflictStatus() == ConflictResolutionStatus.NOT_APPLICABLE) {
            if (s.isEmpty()) {
                organism.instructionFailed("PEEK: Target cell is empty at " + Arrays.toString(targetCoordinate) + ". Cannot extract value/energy.");
                this.setConflictStatus(ConflictResolutionStatus.LOST_TARGET_EMPTY);
                return;
            }

            if (s.type() == Config.TYPE_ENERGY) {
                int energyToTake = Math.min(s.toScalarValue(), Config.MAX_ORGANISM_ENERGY - organism.getEr());
                organism.addEr(energyToTake);
                world.setSymbol(new Symbol(Config.TYPE_ENERGY, s.toScalarValue() - energyToTake), targetCoordinate);

                if (!organism.setDr(targetReg, new Symbol(Config.TYPE_ENERGY, energyToTake).toInt())) {
                    organism.instructionFailed("PEEK: Failed to set taken energy to DR " + targetReg + ". Possible invalid register index.");
                }

            } else {
                if (!organism.setDr(targetReg, s.toInt())) {
                    organism.instructionFailed("PEEK: Failed to set value to DR " + targetReg + ". Possible invalid register index.");
                }
                world.setSymbol(new Symbol(Config.TYPE_CODE, 0), targetCoordinate);
            }
        }
    }

    @Override
    public List<int[]> getTargetCoordinates() {
        return List.of(targetCoordinate);
    }
}