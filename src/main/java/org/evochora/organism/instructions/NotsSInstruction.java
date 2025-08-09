package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class NotsSInstruction extends Instruction {

    public static final int LENGTH = 1;

    public NotsSInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        try {
            Object a = organism.getDataStack().pop();
            if (a instanceof Integer ai) {
                Symbol sa = Symbol.fromInt(ai);
                int v = ~sa.toScalarValue();
                organism.getDataStack().push(new Symbol(sa.type(), v).toInt());
            } else {
                organism.instructionFailed("NOTS: Nur Skalar NOT unterstützt.");
            }
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack Underflow: NOTS benötigt 1 Operanden.");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int full = world.getSymbol(organism.getIp()).toInt();
        return new NotsSInstruction(organism, full);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> regMap, Map<String, Integer> lblMap) {
        if (args.length != 0) throw new IllegalArgumentException("NOTS erwartet keine Argumente.");
        return new AssemblerOutput.CodeSequence(List.of());
    }
}
