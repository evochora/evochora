package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class PeksInstruction extends Instruction {

    public static final int LENGTH = 1;

    public PeksInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        World world = simulation.getWorld();
        Deque<Object> ds = organism.getDataStack();
        try {
            Object vecObj = ds.pop();
            if (!(vecObj instanceof int[] v)) {
                organism.instructionFailed("PEKS: Erwartet Vektor (int[]) auf dem Stack.");
                return;
            }
            int[] target = organism.getTargetCoordinate(organism.getDp(), v, world);
            Symbol s = world.getSymbol(target);
            if (s.isEmpty()) {
                organism.instructionFailed("PEKS: Zielzelle ist leer bei " + Arrays.toString(target));
                return;
            }
            if (s.type() == Config.TYPE_ENERGY) {
                int gain = Math.min(s.toScalarValue(), Config.MAX_ORGANISM_ENERGY - organism.getEr());
                organism.addEr(gain);
                ds.push(new Symbol(Config.TYPE_ENERGY, gain).toInt());
            } else {
                ds.push(s.toInt());
            }
            world.setSymbol(new Symbol(Config.TYPE_CODE, 0), target);
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack Underflow: PEKS benötigt 1 Operanden.");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int full = world.getSymbol(organism.getIp()).toInt();
        return new PeksInstruction(organism, full);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 0) throw new IllegalArgumentException("PEKS erwartet keine Argumente.");
        return new AssemblerOutput.CodeSequence(List.of());
    }
}
