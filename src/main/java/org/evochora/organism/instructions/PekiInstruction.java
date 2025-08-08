package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.IWorldModifyingInstruction;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class PekiInstruction extends Instruction implements IWorldModifyingInstruction {

    public static final int LENGTH = 2 + Config.WORLD_DIMENSIONS;

    private final int targetReg;
    private final int[] vector;
    private final int[] targetCoordinate;

    public PekiInstruction(Organism organism, int tr, int[] vec, int[] tc, int fullOpcodeId) {
        super(organism, fullOpcodeId);
        this.targetReg = tr;
        this.vector = vec;
        this.targetCoordinate = tc;
    }

    @Override
    public void execute(Simulation simulation) {
        World world = simulation.getWorld();
        if (!organism.isUnitVector(vector)) return;
        Symbol s = world.getSymbol(targetCoordinate);
        if (getConflictStatus() == ConflictResolutionStatus.WON_EXECUTION || getConflictStatus() == ConflictResolutionStatus.NOT_APPLICABLE) {
            if (s.isEmpty()) {
                organism.instructionFailed("PEKI: Target cell is empty at " + Arrays.toString(targetCoordinate));
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
        int[] vector = new int[Config.WORLD_DIMENSIONS];
        int[] currentPos = result1.nextIp();
        for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
            Organism.FetchResult valResult = organism.fetchSignedArgument(currentPos, world);
            vector[i] = valResult.value();
            currentPos = valResult.nextIp();
        }
        if (!organism.isUnitVector(vector)) {
            // KORREKTUR: Übergibt die fullOpcodeId an den NopInstruction Konstruktor
            return new NopInstruction(organism, fullOpcodeId);
        }
        int[] target = organism.getTargetCoordinate(organism.getDp(), vector, world);
        return new PekiInstruction(organism, targetReg, vector, target, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("PEKI erwartet 2 Argumente: %REG_TARGET X|Y");
        Integer targetRegId = registerMap.get(args[0].toUpperCase());
        if (targetRegId == null) throw new IllegalArgumentException("Ungültiges Register-Argument: " + args[0]);
        String[] vectorComponents = args[1].split("\\|");
        if (vectorComponents.length != Config.WORLD_DIMENSIONS) throw new IllegalArgumentException("Falsche Vektor-Dimensionalität in '" + args[1] + "'.");
        List<Integer> machineCode = new ArrayList<>();
        machineCode.add(new Symbol(Config.TYPE_DATA, targetRegId).toInt());
        for (String component : vectorComponents) {
            try {
                machineCode.add(new Symbol(Config.TYPE_DATA, Integer.parseInt(component.strip())).toInt());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Vektor-Komponente ist keine gültige Zahl: " + component);
            }
        }
        return new AssemblerOutput.CodeSequence(machineCode);
    }

    @Override
    public List<int[]> getTargetCoordinates() {
        return List.of(targetCoordinate);
    }
}