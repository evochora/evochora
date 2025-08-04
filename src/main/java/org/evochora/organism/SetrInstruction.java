// src/main/java/org/evochora/organism/SetrInstruction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.assembler.ArgumentType;
import org.evochora.Config;
import org.evochora.world.Symbol;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SetrInstruction extends Instruction {
    public static final int ID = 2;

    private final int destReg;
    private final int srcReg;

    public SetrInstruction(Organism o, int d, int s) {
        super(o);
        this.destReg = d;
        this.srcReg = s;
    }

    static {
        Instruction.registerInstruction(SetrInstruction.class, ID, "SETR", 3, SetrInstruction::plan, SetrInstruction::assemble);
    }

    @Override
    public String getName() {
        return "SETR";
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
        return getFixedBaseCost();
    }

    @Override
    public ArgumentType getArgumentType(int argIndex) {
        return switch (argIndex) {
            case 0, 1 -> ArgumentType.REGISTER;
            default -> throw new IllegalArgumentException("Ungültiger Argumentindex für SETR: " + argIndex);
        };
    }

    public static Instruction plan(Organism organism, World world) {
        // GEÄNDERT: Neue Logik mit FetchResult
        // Erstes Argument (Ziel-Register) lesen
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int dest = result1.value();

        // Zweites Argument (Quell-Register) lesen, startend bei der Position des ersten Arguments
        Organism.FetchResult result2 = organism.fetchArgument(result1.nextIp(), world);
        int src = result2.value();

        return new SetrInstruction(organism, dest, src);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("SETR erwartet 2 Argumente: %REG_DEST %REG_SRC");

        int destRegId = registerMap.get(args[0].toUpperCase());
        int srcRegId = registerMap.get(args[1].toUpperCase());

        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, destRegId).toInt(),
                new Symbol(Config.TYPE_DATA, srcRegId).toInt()
        ));
    }

    @Override
    public void execute(Simulation simulation) {
        Object value = organism.getDr(srcReg);

        if (value == null || !organism.setDr(destReg, value)) {
            organism.instructionFailed("SETR: Failed to set value from DR " + srcReg + " to DR " + destReg + ". Possible invalid register index or null source value.");
        }
    }
}