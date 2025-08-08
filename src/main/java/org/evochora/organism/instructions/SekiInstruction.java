package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SekiInstruction extends Instruction {

    public static final int LENGTH = 1 + Config.WORLD_DIMENSIONS;

    private final int[] vector;

    public SekiInstruction(Organism o, int[] vector, int fullOpcodeId) {
        super(o, fullOpcodeId);
        this.vector = vector;
    }

    @Override
    public void execute(Simulation simulation) {
        // KORREKTUR: Die fehlerhafte isUnitVector-Prüfung wurde entfernt.

        int[] targetDp = organism.getTargetCoordinate(organism.getDp(), vector, simulation.getWorld());

        if (simulation.getWorld().getSymbol(targetDp).isEmpty()) {
            organism.setDp(targetDp);
        } else {
            organism.instructionFailed("SEKI: Ziel-DP-Zelle ist nicht leer bei " + Arrays.toString(targetDp) + ".");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        int[] vector = new int[Config.WORLD_DIMENSIONS];
        int[] currentPos = organism.getIp();

        for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
            Organism.FetchResult valResult = organism.fetchSignedArgument(currentPos, world);
            vector[i] = valResult.value();
            currentPos = valResult.nextIp();
        }

        // KORREKTUR: Die fehlerhafte isUnitVector-Prüfung wurde auch hier entfernt.
        return new SekiInstruction(organism, vector, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) {
            throw new IllegalArgumentException("SEKI erwartet genau 1 Argument: X|Y");
        }

        String[] vectorComponents = args[0].split("\\|");
        if (vectorComponents.length != Config.WORLD_DIMENSIONS) {
            throw new IllegalArgumentException(String.format("Falsche Vektor-Dimensionalität in '%s' für SEKI.", args[0]));
        }

        List<Integer> machineCode = new ArrayList<>();
        for (String component : vectorComponents) {
            try {
                int value = Integer.parseInt(component.strip());
                machineCode.add(new Symbol(Config.TYPE_DATA, value).toInt());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Vektor-Komponente ist keine gültige Zahl: " + component);
            }
        }
        return new AssemblerOutput.CodeSequence(machineCode);
    }
}