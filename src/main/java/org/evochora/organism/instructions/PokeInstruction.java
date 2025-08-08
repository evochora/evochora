package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.IWorldModifyingInstruction;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class PokeInstruction extends Instruction implements IWorldModifyingInstruction {

    public static final int LENGTH = 3;

    private final int srcReg;
    private final int vecReg;
    private final int[] targetCoordinate;

    public PokeInstruction(Organism organism, int srcReg, int vecReg, int[] tc, int fullOpcodeId) {
        super(organism, fullOpcodeId);
        this.srcReg = srcReg;
        this.vecReg = vecReg;
        this.targetCoordinate = tc;
    }

    @Override
    public void execute(Simulation simulation) {
        World world = simulation.getWorld();
        Object vecObj = organism.getDr(vecReg);
        Object valObj = organism.getDr(srcReg);
        if (valObj instanceof int[]) {
            organism.instructionFailed("POKE: Vektoren können nicht in die Welt geschrieben werden.");
            return;
        }
        if (!(vecObj instanceof int[] v) || !organism.isUnitVector(v)) {
            return;
        }
        if (valObj instanceof Integer i) {
            Symbol sourceSymbol = Symbol.fromInt(i);
            if (getConflictStatus() == ConflictResolutionStatus.WON_EXECUTION || getConflictStatus() == ConflictResolutionStatus.NOT_APPLICABLE) {
                if (world.getSymbol(targetCoordinate).isEmpty()) {
                    world.setSymbol(sourceSymbol, targetCoordinate);
                } else {
                    organism.instructionFailed("POKE: Zielzelle ist nicht leer bei " + Arrays.toString(targetCoordinate) + ".");
                    setConflictStatus(ConflictResolutionStatus.LOST_TARGET_OCCUPIED);
                }
            }
        } else {
            organism.instructionFailed("POKE: Ungültiger DR-Typ für Quellwert (Reg " + srcReg + ").");
        }
    }

    @Override
    public int getCost(Organism organism, World world, List<Integer> rawArguments) {
        int cost = 1;
        Object valueToPokeObj = organism.getDr(srcReg);
        if (valueToPokeObj instanceof Integer i) {
            Symbol sourceSymbol = Symbol.fromInt(i);
            if (sourceSymbol.type() == Config.TYPE_ENERGY || sourceSymbol.type() == Config.TYPE_STRUCTURE) {
                cost += Math.abs(sourceSymbol.toScalarValue());
            }
        }
        return cost;
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int src = result1.value();
        Organism.FetchResult result2 = organism.fetchArgument(result1.nextIp(), world);
        int vec = result2.value();
        Object v = organism.getDr(vec);
        if (v instanceof int[] vi) {
            if (!organism.isUnitVector(vi)) {
                // KORREKTUR: Übergibt die fullOpcodeId an den NopInstruction Konstruktor
                return new NopInstruction(organism, fullOpcodeId);
            }
            int[] target = organism.getTargetCoordinate(organism.getDp(), vi, world);
            return new PokeInstruction(organism, src, vec, target, fullOpcodeId);
        } else {
            organism.instructionFailed("POKE: Invalid DR type for vector (Reg " + vec + ").");
            return new NopInstruction(organism, fullOpcodeId);
        }
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("POKE erwartet 2 Register-Argumente: %REG_SRC %REG_VEC");
        Integer srcRegId = registerMap.get(args[0].toUpperCase());
        Integer vecRegId = registerMap.get(args[1].toUpperCase());
        if (srcRegId == null || vecRegId == null) throw new IllegalArgumentException(String.format("Ungültiges Register-Argument. Src: '%s', Vec: '%s'", args[0], args[1]));
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, srcRegId).toInt(),
                new Symbol(Config.TYPE_DATA, vecRegId).toInt()
        ));
    }

    @Override
    public List<int[]> getTargetCoordinates() {
        return List.of(targetCoordinate);
    }
}