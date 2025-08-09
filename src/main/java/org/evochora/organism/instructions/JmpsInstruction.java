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

public class JmpsInstruction extends Instruction {

    public static final int LENGTH = 1;

    public JmpsInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        Deque<Object> ds = organism.getDataStack();
        try {
            Object addr = ds.pop();
            if (!(addr instanceof int[] vec)) {
                organism.instructionFailed("JMPS: Erwartet Vektor (int[]) auf dem Stack.");
                return;
            }
            int[] target = organism.getTargetCoordinate(organism.getIpBeforeFetch(), vec, simulation.getWorld());
            organism.setIp(target);
            organism.setSkipIpAdvance(true);
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack Underflow: JMPS benötigt 1 Operanden.");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int full = world.getSymbol(organism.getIp()).toInt();
        return new JmpsInstruction(organism, full);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 0) throw new IllegalArgumentException("JMPS erwartet keine Argumente.");
        return new AssemblerOutput.CodeSequence(List.of());
    }
}
