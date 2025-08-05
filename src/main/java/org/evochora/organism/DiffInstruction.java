// src/main/java/org/evochora/organism/DiffInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.World;
import org.evochora.world.Symbol;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.assembler.ArgumentType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class DiffInstruction extends Instruction {
    public static final int ID = 19;

    private final int reg;

    public DiffInstruction(Organism o, int r) {
        super(o);
        this.reg = r;
    }

    static {
        Instruction.registerInstruction(DiffInstruction.class, ID, "DIFF", 2, DiffInstruction::plan, DiffInstruction::assemble);
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.REGISTER));
    }

    @Override
    public String getName() {
        return "DIFF";
    }

    @Override
    public int getLength() {
        return 2;
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
        }
        throw new IllegalArgumentException("Ungültiger Argumentindex für DIFF: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        // GEÄNDERT: Neue Logik mit FetchResult für ein Argument
        Organism.FetchResult result = organism.fetchArgument(organism.getIp(), world);
        int r = result.value();
        return new DiffInstruction(organism, r);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("DIFF erwartet 1 Argument: %REG_TARGET");

        int regId = registerMap.get(args[0].toUpperCase());

        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt()
        ));
    }

    @Override
    public void execute(Simulation simulation) {
        int[] lastIp = organism.getIpBeforeFetch();

        if (lastIp == null || lastIp.length != organism.getIp().length) {
            organism.instructionFailed("DIFF: Cannot determine previous IP for difference calculation.");
            return;
        }

        int[] delta = new int[organism.getIp().length];
        for (int i = 0; i < organism.getIp().length; i++) {
            delta[i] = organism.getIp()[i] - lastIp[i];
        }

        if (!organism.setDr(reg, delta)) {
            organism.instructionFailed("DIFF: Failed to set result to DR " + reg + ". Possible invalid register index.");
        }
    }
}