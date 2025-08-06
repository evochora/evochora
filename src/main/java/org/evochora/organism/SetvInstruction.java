// src/main/java/org/evochora/organism/SetvInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.assembler.ArgumentType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class SetvInstruction extends Instruction {
    public static final int ID = 3;

    private final int destReg;
    private final int[] values;

    public SetvInstruction(Organism o, int d, int[] v) {
        super(o);
        this.destReg = d;
        this.values = v;
    }

    static {
        Instruction.registerInstruction(SetvInstruction.class, ID, "SETV", 2 + Config.WORLD_DIMENSIONS, SetvInstruction::plan, SetvInstruction::assemble);

        Map<Integer, ArgumentType> argumentTypes = new HashMap<>();
        argumentTypes.put(0, ArgumentType.REGISTER);
        for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
            argumentTypes.put(1 + i, ArgumentType.COORDINATE);
        }
        Instruction.registerArgumentTypes(ID, argumentTypes);
    }

    @Override
    public String getName() {
        return "SETV";
    }

    @Override
    public int getLength() {
        return 2 + Config.WORLD_DIMENSIONS;
    }

    @Override
    protected int getFixedBaseCost() {
        return 1;
    }

    @Override
    public int getCost(Organism organism, World world, List<Integer> rawArguments) {
        return getFixedBaseCost();
    }

    @Override
    public ArgumentType getArgumentType(int argIndex) {
        if (argIndex == 0) {
            return ArgumentType.REGISTER;
        } else if (argIndex >= 1 && argIndex <= Config.WORLD_DIMENSIONS) {
            return ArgumentType.COORDINATE;
        }
        throw new IllegalArgumentException("Ungültiger Argumentindex für SETV: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        Organism.FetchResult regResult = organism.fetchArgument(organism.getIp(), world);
        int dest = regResult.value();

        int[] vals = new int[Config.WORLD_DIMENSIONS];
        int[] currentPos = regResult.nextIp();

        for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
            Organism.FetchResult valResult = organism.fetchSignedArgument(currentPos, world);
            vals[i] = valResult.value();
            currentPos = valResult.nextIp();
        }

        return new SetvInstruction(organism, dest, vals);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) {
            throw new IllegalArgumentException("SETV erwartet 2 Argumente: %REG (WERT1|WERT2|... | LABEL)");
        }
        List<Integer> machineCode = new ArrayList<>();

        Integer regId = registerMap.get(args[0].toUpperCase());
        if (regId == null) {
            throw new IllegalArgumentException("Ungültiges Register-Argument: " + args[0]);
        }

        String vectorArg = args[1];

        // NEU: Prüfen, ob das zweite Argument ein Label ist.
        // Das Label darf nicht von | umgeben sein, um es von Vektoren zu unterscheiden.
        if (!vectorArg.contains("|") && labelMap.containsKey(vectorArg.toUpperCase())) {
            return new AssemblerOutput.LabelToVectorRequest(vectorArg, regId);
        }

        // Bestehende Logik zum Parsen eines Vektor-Literals
        String[] vectorComponents = vectorArg.split("\\|");
        if (vectorComponents.length != Config.WORLD_DIMENSIONS) {
            throw new IllegalArgumentException(String.format("Falsche Vektor-Dimensionalität in '%s'. Erwartet %d, gefunden %d.", vectorArg, Config.WORLD_DIMENSIONS, vectorComponents.length));
        }

        machineCode.add(new Symbol(Config.TYPE_DATA, regId).toInt());
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

    @Override
    public void execute(Simulation simulation) {
        if (!organism.setDr(destReg, values)) {
            organism.instructionFailed("SETV: Failed to set vector " + Arrays.toString(values) + " to DR " + destReg + ". Possible invalid register index.");
        }
    }
}