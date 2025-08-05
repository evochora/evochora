// src/main/java/org/evochora/organism/NrgInstruction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import org.evochora.world.Symbol;
import org.evochora.Config;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.assembler.ArgumentType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class NrgInstruction extends Instruction {
    public static final int ID = 17;

    private final int targetReg;

    public NrgInstruction(Organism o, int tr) {
        super(o);
        this.targetReg = tr;
    }

    static {
        Instruction.registerInstruction(NrgInstruction.class, ID, "NRG", 2, NrgInstruction::plan, NrgInstruction::assemble);
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.REGISTER));
    }

    @Override
    public String getName() {
        return "NRG";
    }

    @Override
    public int getLength() {
        return 2;
    }

    @Override
    protected int getFixedBaseCost() {
        return 0;
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
        throw new IllegalArgumentException("Ungültiger Argumentindex für NRG: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        // GEÄNDERT: Neue Logik mit FetchResult für ein Argument
        Organism.FetchResult result = organism.fetchArgument(organism.getIp(), world);
        int tr = result.value();
        return new NrgInstruction(organism, tr);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("NRG erwartet 1 Argument: %REG_TARGET");

        int regId = registerMap.get(args[0].toUpperCase());

        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt()
        ));
    }

    @Override
    public void execute(Simulation simulation) {
        if (!organism.setDr(targetReg, organism.getEr())) {
            organism.instructionFailed("NRG: Failed to set energy to DR " + targetReg + ". Possible invalid register index.");
        }
    }
}