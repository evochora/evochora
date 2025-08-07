// src/main/java/org/evochora/organism/CallInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.ArgumentType;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

public class CallInstruction extends Instruction {

    public static final int ID = 34;

    private final int[] delta;

    public CallInstruction(Organism o, int[] delta) {
        super(o);
        this.delta = delta;
    }

    static {
        Instruction.registerInstruction(CallInstruction.class, ID, "CALL", 1 + Config.WORLD_DIMENSIONS, CallInstruction::plan, CallInstruction::assemble);
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.LABEL, 1, ArgumentType.LABEL));
    }


    @Override
    public String getName() {
        return "CALL";
    }

    @Override
    public int getLength() {
        return 1 + Config.WORLD_DIMENSIONS;
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
        if (argIndex >= 0 && argIndex < Config.WORLD_DIMENSIONS) {
            return ArgumentType.COORDINATE;
        }
        throw new IllegalArgumentException("Ungültiger Argumentindex für CALL: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        int[] delta = new int[Config.WORLD_DIMENSIONS];
        int[] currentPos = organism.getIp();

        for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
            Organism.FetchResult result = organism.fetchSignedArgument(currentPos, world);
            delta[i] = result.value();
            currentPos = result.nextIp();
        }
        return new CallInstruction(organism, delta);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) {
            throw new IllegalArgumentException("CALL erwartet genau 1 Argument (ein Label).");
        }
        String arg = args[0].toUpperCase();

        if (labelMap.containsKey(arg)) {
            return new AssemblerOutput.JumpInstructionRequest(arg);
        } else {
            throw new IllegalArgumentException(String.format("Argument für CALL ist kein bekanntes Label: '%s'", arg));
        }
    }

    @Override
    public void execute(Simulation simulation) {
        // 1. Berechne die Rücksprungadresse (IP + Länge des CALL-Befehls)
        int[] currentIp = organism.getIpBeforeFetch();
        int currentLength = this.getLength();
        int[] returnIp = currentIp;
        for (int i = 0; i < currentLength; i++) {
            returnIp = organism.getNextInstructionPosition(returnIp, simulation.getWorld(), organism.getDvBeforeFetch());
        }

        // 2. Schiebe die Rücksprungadresse auf den Stack (als Vektor)
        organism.getDataStack().push(returnIp);

        // 3. Berechne die Zielkoordinate für den Sprung
        int[] targetIp = organism.getTargetCoordinate(organism.getIpBeforeFetch(), this.delta, simulation.getWorld());

        // 4. Setze den IP auf die Zielkoordinate
        organism.setIp(targetIp);
        organism.setSkipIpAdvance(true);
    }
}