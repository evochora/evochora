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

public class XorsSInstruction extends Instruction {

    public static final int LENGTH = 1;

    public XorsSInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        Deque<Object> ds = organism.getDataStack();
        try {
            Object b = ds.pop();
            Object a = ds.pop();
            if (a instanceof Integer ai && b instanceof Integer bi) {
                Symbol sa = Symbol.fromInt(ai);
                Symbol sb = Symbol.fromInt(bi);
                if (Config.STRICT_TYPING && sa.type() != sb.type()) {
                    organism.instructionFailed("XORS: Typen müssen im strikten Modus übereinstimmen.");
                    return;
                }
                int v = sa.toScalarValue() ^ sb.toScalarValue();
                ds.push(new Symbol(sa.type(), v).toInt());
            } else {
                organism.instructionFailed("XORS: Nur Skalar-Skalar-XOR unterstützt.");
            }
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack Underflow: XORS benötigt 2 Operanden.");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int full = world.getSymbol(organism.getIp()).toInt();
        return new XorsSInstruction(organism, full);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> regMap, Map<String, Integer> lblMap) {
        if (args.length != 0) throw new IllegalArgumentException("XORS erwartet keine Argumente.");
        return new AssemblerOutput.CodeSequence(List.of());
    }
}
