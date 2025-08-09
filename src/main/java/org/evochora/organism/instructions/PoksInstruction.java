package org.evochora.organism.instructions;

import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.IWorldModifyingInstruction;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class PoksInstruction extends Instruction {

    public static final int LENGTH = 1;

    public PoksInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        World world = simulation.getWorld();
        Deque<Object> ds = organism.getDataStack();
        try {
            Object vecObj = ds.pop();   // TOS = vec
            Object valObj = ds.pop();   // next = value
            if (!(vecObj instanceof int[] v) || !(valObj instanceof Integer vi)) {
                organism.instructionFailed("POKS: Erwartet (value:int, vec:int[]) auf dem Stack (vec oben).");
                return;
            }
            int[] target = organism.getTargetCoordinate(organism.getDp(), v, world);
            world.setSymbol(Symbol.fromInt(vi), target);
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack Underflow: POKS benötigt 2 Operanden.");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int full = world.getSymbol(organism.getIp()).toInt();
        return new PoksInstruction(organism, full);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 0) throw new IllegalArgumentException("POKS erwartet keine Argumente.");
        return new AssemblerOutput.CodeSequence(List.of());
    }
}
