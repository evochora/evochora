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

public class PeekInstruction extends Instruction implements IWorldModifyingInstruction {

    public static final int LENGTH = 3;

    private final int targetReg, vecReg;
    private final int[] targetCoordinate;

    public PeekInstruction(Organism organism, int tr, int vr, int[] tc, int fullOpcodeId) {
        super(organism, fullOpcodeId);
        this.targetReg = tr;
        this.vecReg = vr;
        this.targetCoordinate = tc;
    }

    @Override
    public void execute(Simulation simulation) {
        World world = simulation.getWorld();
        Object vecObj = organism.getDr(vecReg);
        if (!(vecObj instanceof int[] v) || !organism.isUnitVector(v)) {
            return;
        }

        Symbol s = world.getSymbol(targetCoordinate);

        if (getConflictStatus() == ConflictResolutionStatus.WON_EXECUTION || getConflictStatus() == ConflictResolutionStatus.NOT_APPLICABLE) {
            if (s.isEmpty()) {
                organism.instructionFailed("PEEK: Target cell is empty at " + Arrays.toString(targetCoordinate));
                setConflictStatus(ConflictResolutionStatus.LOST_TARGET_EMPTY);
                return;
            }

            if (s.type() == Config.TYPE_ENERGY) {
                int energyToTake = Math.min(s.toScalarValue(), Config.MAX_ORGANISM_ENERGY - organism.getEr());
                organism.addEr(energyToTake);
                organism.setDr(targetReg, new Symbol(Config.TYPE_ENERGY, energyToTake).toInt());
            } else {
                organism.setDr(targetReg, s.toInt());
            }
            world.setSymbol(new Symbol(Config.TYPE_CODE, 0), targetCoordinate);
        }
    }

    @Override
    public int getCost(Organism organism, World world, List<Integer> rawArguments) {
        int cost = 1;
        Symbol targetSymbol = world.getSymbol(targetCoordinate);
        if (targetSymbol.type() == Config.TYPE_STRUCTURE) {
            cost += Math.abs(targetSymbol.toScalarValue());
        }
        return cost;
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int targetReg = result1.value();
        Organism.FetchResult result2 = organism.fetchArgument(result1.nextIp(), world);
        int vecReg = result2.value();

        Object vec = organism.getDr(vecReg);
        if (vec instanceof int[] v) {
            if (!organism.isUnitVector(v)) {
                // KORREKTUR: Übergibt die fullOpcodeId an den NopInstruction Konstruktor
                return new NopInstruction(organism, fullOpcodeId);
            }
            int[] target = organism.getTargetCoordinate(organism.getDp(), v, world);
            return new PeekInstruction(organism, targetReg, vecReg, target, fullOpcodeId);
        } else {
            organism.instructionFailed("PEEK: Invalid DR type for vector (Reg " + vecReg + ").");
            return new NopInstruction(organism, fullOpcodeId);
        }
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("PEEK erwartet 2 Register-Argumente: %REG_TARGET %REG_VEC");
        Integer targetRegId = registerMap.get(args[0].toUpperCase());
        Integer vecRegId = registerMap.get(args[1].toUpperCase());
        if (targetRegId == null || vecRegId == null) throw new IllegalArgumentException(String.format("Ungültiges Register-Argument. Target: '%s', Vec: '%s'", args[0], args[1]));
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, targetRegId).toInt(),
                new Symbol(Config.TYPE_DATA, vecRegId).toInt()
        ));
    }

    @Override
    public List<int[]> getTargetCoordinates() {
        return List.of(targetCoordinate);
    }
}