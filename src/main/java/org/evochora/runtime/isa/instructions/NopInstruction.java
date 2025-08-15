package org.evochora.runtime.isa.instructions;

import org.evochora.runtime.Simulation;
import org.evochora.compiler.internal.legacy.AssemblerOutput;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;

import java.util.Collections;
import java.util.Map;

public class NopInstruction extends Instruction {

    public static final int LENGTH = 1;

    public NopInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        // Diese Instruktion tut absichtlich nichts.
    }

    public static Instruction plan(Organism organism, Environment environment) {
        int fullOpcodeId = environment.getMolecule(organism.getIp()).toInt();
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