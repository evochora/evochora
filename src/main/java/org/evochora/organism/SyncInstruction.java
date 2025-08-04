// src/main/java/org/evochora/organism/SyncInstruction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.assembler.ArgumentType;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SyncInstruction extends Instruction {
    public static final int ID = 13;

    public SyncInstruction(Organism o) {
        super(o);
    }

    static {
        Instruction.registerInstruction(SyncInstruction.class, ID, "SYNC", 1, SyncInstruction::plan, SyncInstruction::assemble);
    }

    @Override
    public String getName() {
        return "SYNC";
    }

    @Override
    public int getLength() {
        return 1;
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
        throw new IllegalArgumentException("SYNC hat keine Argumente. Ungültiger Index: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        return new SyncInstruction(organism);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        return new AssemblerOutput.CodeSequence(Collections.emptyList());
    }

    @Override
    public void execute(Simulation simulation) {
        organism.setDp(organism.getIp());
    }
}