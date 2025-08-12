package org.evochora.organism.instructions;

import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.World;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class NopInstruction extends Instruction {

    public static final int LENGTH = 1;

    public NopInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        /* Tut nichts */
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        return new NopInstruction(organism, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 0) throw new IllegalArgumentException("NOP erwartet keine Argumente.");
        return new AssemblerOutput.CodeSequence(Collections.emptyList());
    }

    // Overload used by registerFamily; instructionName is ignored for NOP
    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap, String instructionName) {
        return assemble(args, registerMap, labelMap);
    }
}