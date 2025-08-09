package org.evochora.organism.instructions;

import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.World;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class RotInstruction extends Instruction {

    public static final int LENGTH = 1;

    public RotInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        try {
            Object a = organism.getDataStack().pop(); // top (C)
            Object b = organism.getDataStack().pop(); // (B)
            Object c = organism.getDataStack().pop(); // (A)
            // push in order B, C, A
            organism.getDataStack().push(b);
            organism.getDataStack().push(a); // this 'a' is the original C
            organism.getDataStack().push(c); // original A becomes top
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack Underflow: ROT benötigt mindestens 3 Elemente.");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        return new RotInstruction(organism, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 0) throw new IllegalArgumentException("ROT erwartet keine Argumente.");
        return new AssemblerOutput.CodeSequence(List.of());
    }
}
