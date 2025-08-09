package org.evochora.organism.instructions;

import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.World;

import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class SwapInstruction extends Instruction {

    public static final int LENGTH = 1;

    public SwapInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        Deque<Object> ds = organism.getDataStack();
        try {
            Object a = ds.pop(); // TOS
            Object b = ds.pop();
            ds.push(a);
            ds.push(b);
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack Underflow: SWAP benötigt mindestens 2 Elemente.");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        return new SwapInstruction(organism, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 0) throw new IllegalArgumentException("SWAP erwartet keine Argumente.");
        return new AssemblerOutput.CodeSequence(List.of());
    }
}
