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

public class PokiInstruction extends Instruction implements IWorldModifyingInstruction {

    public static final int LENGTH = 2 + Config.WORLD_DIMENSIONS;

    private final int srcReg;
    private final int[] vector;
    private final int[] targetCoordinate;

    public PokiInstruction(Organism organism, int sr, int[] vec, int[] tc, int fullOpcodeId) {
        super(organism, fullOpcodeId);
        this.srcReg = sr;
        this.vector = vec;
        this.targetCoordinate = tc;
    }

    @Override
    public void execute(Simulation simulation) {
        World world = simulation.getWorld();
        Object valObj = organism.getDr(srcReg);
        if (valObj instanceof int[]) {
            organism.instructionFailed("POKI: Vektoren können nicht in die Welt geschrieben werden.");
            return;
        }
        if (!organism.isUnitVector(vector)) {
            return;
        }
        if (valObj instanceof Integer i) {
            Symbol sourceSymbol = Symbol.fromInt(i);
            if (getConflictStatus() == ConflictResolutionStatus.WON_EXECUTION || getConflictStatus() == ConflictResolutionStatus.NOT_APPLICABLE) {
                if (world.getSymbol(targetCoordinate).isEmpty()) {
                    world.setSymbol(sourceSymbol, targetCoordinate);
                } else {
                    organism.instructionFailed("POKI: Zielzelle ist nicht leer bei " + Arrays.toString(targetCoordinate) + ".");
                    setConflictStatus(ConflictResolutionStatus.LOST_TARGET_OCCUPIED);
                }
            }
        } else {
            organism.instructionFailed("POKI: Ungültiger DR-Typ für Quellwert (Reg " + srcReg + ").");
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
        int srcReg = result1.value();
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
        return new PokiInstruction(organism, srcReg, vector, target, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("POKI erwartet 2 Argumente: %REG_SRC X|Y");
        Integer srcRegId = registerMap.get(args[0].toUpperCase());
        if (srcRegId == null) throw new IllegalArgumentException("Ungültiges Register-Argument: " + args[0]);
        String[] vectorComponents = args[1].split("\\|");
        if (vectorComponents.length != Config.WORLD_DIMENSIONS) throw new IllegalArgumentException("Falsche Vektor-Dimensionalität in '" + args[1] + "'.");
        List<Integer> machineCode = new ArrayList<>();
        machineCode.add(new Symbol(Config.TYPE_DATA, srcRegId).toInt());
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