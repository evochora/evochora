// src/main/java/org/evochora/organism/PokeInstruction.java
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

public class PokeInstruction extends Instruction implements IWorldModifyingInstruction {
    public static final int ID = 15;

    private final int srcReg;
    private final int vecReg;
    private final int[] targetCoordinate;

    public PokeInstruction(Organism organism, int srcReg, int vecReg, int[] targetCoordinate) {
        super(organism);
        this.srcReg = srcReg;
        this.vecReg = vecReg;
        this.targetCoordinate = targetCoordinate;
    }

    static {
        Instruction.registerInstruction(PokeInstruction.class, ID, "POKE", 3, PokeInstruction::plan, PokeInstruction::assemble);
    }

    @Override
    public String getName() {
        return "POKE";
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
        Object valueToPokeObj = organism.getDr(srcReg);

        if (valueToPokeObj instanceof Integer i) {
            Symbol sourceSymbol = Symbol.fromInt(i);
            switch (sourceSymbol.type()) {
                case Config.TYPE_ENERGY, Config.TYPE_STRUCTURE -> cost += Math.abs(sourceSymbol.toScalarValue());
            }
        }
        return cost;
    }

    @Override
    public ArgumentType getArgumentType(int argIndex) {
        return switch (argIndex) {
            case 0, 1 -> ArgumentType.REGISTER;
            default -> throw new IllegalArgumentException("Ungültiger Argumentindex für POKE: " + argIndex);
        };
    }

    public static Instruction plan(Organism organism, World world) {
        // GEÄNDERT: Neue Logik mit FetchResult
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int src = result1.value();

        Organism.FetchResult result2 = organism.fetchArgument(result1.nextIp(), world);
        int vec = result2.value();

        // Die nachfolgende Logik bleibt erhalten
        Object v = organism.getDr(vec);
        if (v instanceof int[] vi) {
            if (!organism.isUnitVector(vi)) {
                return new NopInstruction(organism);
            }
            int[] target = organism.getTargetCoordinate(organism.getDp(), vi, world);
            return new PokeInstruction(organism, src, vec, target);
        } else {
            organism.instructionFailed("POKE: Invalid DR type for vector (Reg " + vec + "). Expected int[], found " + (v != null ? v.getClass().getSimpleName() : "null") + ".");
            return new NopInstruction(organism);
        }
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("POKE erwartet 2 Argumente: %REG_SRC %REG_VEC");

        int srcRegId = registerMap.get(args[0].toUpperCase());
        int vecRegId = registerMap.get(args[1].toUpperCase());

        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, srcRegId).toInt(),
                new Symbol(Config.TYPE_DATA, vecRegId).toInt()
        ));
    }

    @Override
    public void execute(Simulation simulation) {
        World world = simulation.getWorld();
        Object vecObj = organism.getDr(vecReg);
        Object valObj = organism.getDr(srcReg);

        if (valObj instanceof int[]) {
            organism.instructionFailed("POKE: Vektoren können nicht in die Welt geschrieben werden (Quellregister " + srcReg + " enthält einen Vektor).");
            return;
        }

        if (!(vecObj instanceof int[] v) || !organism.isUnitVector(v)) {
            return;
        }

        if (valObj instanceof Integer i) {
            Symbol sourceSymbol = Symbol.fromInt(i);

            if (this.getConflictStatus() == ConflictResolutionStatus.WON_EXECUTION || this.getConflictStatus() == ConflictResolutionStatus.NOT_APPLICABLE) {
                if (world.getSymbol(targetCoordinate).isEmpty()) {
                    world.setSymbol(new Symbol(sourceSymbol.type(), sourceSymbol.toScalarValue()), targetCoordinate);
                } else {
                    organism.instructionFailed("POKE: Zielzelle ist nicht leer bei " + Arrays.toString(targetCoordinate) + ". Aktueller Inhalt: " + world.getSymbol(targetCoordinate).toInt() + ".");
                    this.setConflictStatus(ConflictResolutionStatus.LOST_TARGET_OCCUPIED);
                }
            }
        } else {
            organism.instructionFailed("POKE: Ungültiger DR-Typ für Quellwert (Reg " + srcReg + "). Erwartet Integer, gefunden " + (valObj != null ? valObj.getClass().getSimpleName() : "null") + ".");
        }
    }

    @Override
    public List<int[]> getTargetCoordinates() {
        return List.of(targetCoordinate);
    }
}