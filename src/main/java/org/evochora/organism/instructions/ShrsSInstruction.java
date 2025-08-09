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

public class ShrsSInstruction extends Instruction {

    public static final int LENGTH = 1;

    public ShrsSInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        Deque<Object> ds = organism.getDataStack();
        try {
            Object value = ds.pop();
            Object count = ds.pop();
            if (count instanceof Integer ci && value instanceof Integer vi) {
                Symbol sc = Symbol.fromInt(ci);
                Symbol sv = Symbol.fromInt(vi);
                int shift = sc.toScalarValue();
                int val = sv.toScalarValue();
                int res = val >> shift;
                ds.push(new Symbol(sv.type(), res).toInt());
            } else {
                organism.instructionFailed("SHRS: Nur Skalar-Skalar unterstützt (value, count).");
            }
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack Underflow: SHRS benötigt 2 Operanden.");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int full = world.getSymbol(organism.getIp()).toInt();
        return new ShrsSInstruction(organism, full);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> regMap, Map<String, Integer> lblMap) {
        if (args.length != 0) throw new IllegalArgumentException("SHRS erwartet keine Argumente.");
        return new AssemblerOutput.CodeSequence(List.of());
    }
}
