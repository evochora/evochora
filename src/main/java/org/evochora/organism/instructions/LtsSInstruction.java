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

public class LtsSInstruction extends Instruction {

    public static final int LENGTH = 1;

    public LtsSInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        Deque<Object> ds = organism.getDataStack();
        try {
            Object a = ds.pop();
            Object b = ds.pop();
            boolean cond = false;
            if (a instanceof Integer ai && b instanceof Integer bi) {
                Symbol sa = Symbol.fromInt(ai);
                Symbol sb = Symbol.fromInt(bi);
                if (!Config.STRICT_TYPING || sa.type() == sb.type()) {
                    cond = sa.toScalarValue() < sb.toScalarValue();
                }
            }
            if (!cond) {
                organism.skipNextInstruction(simulation.getWorld());
            }
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack Underflow: LTS benötigt 2 Operanden.");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int full = world.getSymbol(organism.getIp()).toInt();
        return new LtsSInstruction(organism, full);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 0) throw new IllegalArgumentException("LTS erwartet keine Argumente.");
        return new AssemblerOutput.CodeSequence(List.of());
    }
}
