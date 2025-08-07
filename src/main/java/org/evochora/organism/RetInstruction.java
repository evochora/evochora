// src/main/java/org/evochora/organism/RetInstruction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.assembler.ArgumentType;

import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Arrays;

public class RetInstruction extends Instruction {
    public static final int ID = 35;

    public RetInstruction(Organism organism) {
        super(organism);
    }

    static {
        Instruction.registerInstruction(
                RetInstruction.class,
                ID,
                "RET",
                1,
                RetInstruction::plan,
                RetInstruction::assemble
        );
        Instruction.registerArgumentTypes(ID, Map.of());
    }

    @Override
    public String getName() {
        return "RET";
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
        throw new IllegalArgumentException("RET hat keine Argumente. Ungültiger Index: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        return new RetInstruction(organism);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 0) {
            throw new IllegalArgumentException("RET erwartet keine Argumente.");
        }
        return new AssemblerOutput.CodeSequence(Collections.emptyList());
    }

    @Override
    public void execute(Simulation simulation) {
        Deque<Object> stack = organism.getDataStack();

        try {
            Object poppedValue = stack.pop();

            if (poppedValue instanceof int[] returnIp) {
                organism.setIp(returnIp);
                organism.setSkipIpAdvance(true);
            } else {
                organism.instructionFailed("RET: Ungültiger Wert auf dem Stack. Erwartet wurde ein Vektor als Rücksprungadresse.");
            }

        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack Underflow: RET-Befehl auf leerem Stack aufgerufen.");
        }
    }
}