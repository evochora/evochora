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

public class AddsSInstruction extends Instruction {

    public static final int LENGTH = 1;

    public AddsSInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        Deque<Object> ds = organism.getDataStack();
        try {
            Object b = ds.pop();
            Object a = ds.pop();
            Object result;
            if (a instanceof int[] va && b instanceof int[] vb) {
                if (va.length != vb.length) {
                    organism.instructionFailed("ADDS: Vektor-Dimensionen stimmen nicht überein.");
                    return;
                }
                int[] r = new int[va.length];
                for (int i = 0; i < va.length; i++) r[i] = va[i] + vb[i];
                result = r;
            } else if (a instanceof Integer ai && b instanceof Integer bi) {
                Symbol sa = Symbol.fromInt(ai);
                Symbol sb = Symbol.fromInt(bi);
                if (Config.STRICT_TYPING && sa.type() != sb.type()) {
                    organism.instructionFailed("ADDS: Typen müssen im strikten Modus übereinstimmen.");
                    return;
                }
                int sum = sa.toScalarValue() + sb.toScalarValue();
                result = new Symbol(sa.type(), sum).toInt();
            } else {
                organism.instructionFailed("ADDS: Ungültige Operanden (müssen beide Skalar oder beide Vektor sein).");
                return;
            }
            ds.push(result);
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack Underflow: ADDS benötigt 2 Operanden.");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int full = world.getSymbol(organism.getIp()).toInt();
        return new AddsSInstruction(organism, full);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> regMap, Map<String, Integer> lblMap) {
        if (args.length != 0) throw new IllegalArgumentException("ADDS erwartet keine Argumente.");
        return new AssemblerOutput.CodeSequence(List.of());
    }
}
