package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class PopInstruction extends Instruction {

    public static final int LENGTH = 2;

    private final int destReg;

    public PopInstruction(Organism organism, int destReg, int fullOpcodeId) {
        super(organism, fullOpcodeId);
        this.destReg = destReg;
    }

    @Override
    public void execute(Simulation simulation) {
        Deque<Object> stack = organism.getDataStack();
        try {
            Object poppedValue = stack.pop();
            organism.setDr(this.destReg, poppedValue);
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack Underflow");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult result = organism.fetchArgument(organism.getIp(), world);
        int registerIndex = result.value();
        return new PopInstruction(organism, registerIndex, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("POP erwartet genau ein Argument: %REG_DEST");
        Integer regId = registerMap.get(args[0].toUpperCase());
        if (regId == null) throw new IllegalArgumentException("Ungültiges Register-Argument für POP: " + args[0]);
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt()
        ));
    }
}