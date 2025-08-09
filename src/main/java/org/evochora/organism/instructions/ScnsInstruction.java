package org.evochora.organism.instructions;

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

public class ScnsInstruction extends Instruction {

    public static final int LENGTH = 1;

    public ScnsInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        Deque<Object> ds = organism.getDataStack();
        try {
            Object vecObj = ds.pop();
            if (!(vecObj instanceof int[] vec)) {
                organism.instructionFailed("SCNS: Erwartet Vektor (int[]) auf dem Stack.");
                return;
            }
            int[] target = organism.getTargetCoordinate(organism.getDp(), vec, simulation.getWorld());
            Symbol scanned = simulation.getWorld().getSymbol(target); // non-destructive
            ds.push(scanned.toInt());
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack Underflow: SCNS benötigt 1 Operanden (Vektor).");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int full = world.getSymbol(organism.getIp()).toInt();
        return new ScnsInstruction(organism, full);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> regMap, Map<String, Integer> lblMap) {
        if (args.length != 0) throw new IllegalArgumentException("SCNS erwartet keine Argumente.");
        return new AssemblerOutput.CodeSequence(List.of());
    }
}
