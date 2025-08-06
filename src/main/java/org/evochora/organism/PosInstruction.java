// src/main/java/org/evochora/organism/PosInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.World;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.assembler.ArgumentType;
import org.evochora.world.Symbol;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class PosInstruction extends Instruction {
    public static final int ID = 21;

    private final int reg;

    public PosInstruction(Organism o, int r) {
        super(o);
        this.reg = r;
    }

    static {
        Instruction.registerInstruction(PosInstruction.class, ID, "POS", 2, PosInstruction::plan, PosInstruction::assemble);
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.REGISTER));
    }

    @Override
    public String getName() {
        return "POS";
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
        throw new IllegalArgumentException("Ungültiger Argumentindex für POS: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        Organism.FetchResult result = organism.fetchArgument(organism.getIp(), world);
        int r = result.value();
        return new PosInstruction(organism, r);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) {
            throw new IllegalArgumentException("POS erwartet genau 1 Argument: %REG_TARGET");
        }

        Integer regId = registerMap.get(args[0].toUpperCase());
        if (regId == null) {
            throw new IllegalArgumentException("Ungültiges Register-Argument für POS: " + args[0]);
        }

        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt()
        ));
    }

    @Override
    public void execute(Simulation simulation) {
        int[] currentIp = organism.getIp();
        int[] initialPosition = organism.getInitialPosition();

        if (currentIp.length != initialPosition.length) {
            organism.instructionFailed("POS: Dimensionen von IP und Erzeugungsposition stimmen nicht überein.");
            return;
        }

        int[] delta = new int[currentIp.length];
        for (int i = 0; i < currentIp.length; i++) {
            delta[i] = currentIp[i] - initialPosition[i];
        }

        if (!organism.setDr(reg, delta)) {
            organism.instructionFailed("POS: Failed to set result to DR " + reg + ". Possible invalid register index.");
        }
    }
}