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

public class SetvInstruction extends Instruction {

    public static final int LENGTH = 2 + Config.WORLD_DIMENSIONS;

    private final int destReg;
    private final int[] values;

    public SetvInstruction(Organism o, int d, int[] v, int fullOpcodeId) {
        super(o, fullOpcodeId);
        this.destReg = d;
        this.values = v;
    }

    @Override
    public void execute(Simulation simulation) {
        if (!organism.setDr(destReg, values)) {
            organism.instructionFailed("SETV: Failed to set vector " + Arrays.toString(values) + " to DR " + destReg + ".");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult regResult = organism.fetchArgument(organism.getIp(), world);
        int dest = regResult.value();
        int[] vals = new int[Config.WORLD_DIMENSIONS];
        int[] currentPos = regResult.nextIp();
        for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
            Organism.FetchResult valResult = organism.fetchSignedArgument(currentPos, world);
            vals[i] = valResult.value();
            currentPos = valResult.nextIp();
        }
        return new SetvInstruction(organism, dest, vals, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("SETV erwartet 2 Argumente: %REG (WERT1|WERT2... | LABEL)");
        Integer regId = registerMap.get(args[0].toUpperCase());
        if (regId == null) throw new IllegalArgumentException("Ungültiges Register-Argument: " + args[0]);
        String vectorArg = args[1];
        if (!vectorArg.contains("|") && labelMap.containsKey(vectorArg.toUpperCase())) {
            return new AssemblerOutput.LabelToVectorRequest(vectorArg, regId);
        }
        String[] vectorComponents = vectorArg.split("\\|");
        if (vectorComponents.length != Config.WORLD_DIMENSIONS) throw new IllegalArgumentException("Falsche Vektor-Dimensionalität in '" + vectorArg + "'.");
        List<Integer> machineCode = new ArrayList<>();
        machineCode.add(new Symbol(Config.TYPE_DATA, regId).toInt());
        for (String component : vectorComponents) {
            try {
                machineCode.add(new Symbol(Config.TYPE_DATA, Integer.parseInt(component.strip())).toInt());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Vektor-Komponente ist keine gültige Zahl: " + component);
            }
        }
        return new AssemblerOutput.CodeSequence(machineCode);
    }
}