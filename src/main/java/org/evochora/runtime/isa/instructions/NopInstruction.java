package org.evochora.runtime.isa.instructions;

import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;

public class NopInstruction extends Instruction {

    public static final int LENGTH = 1;

    public NopInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context) {
        // Diese Instruktion tut absichtlich nichts.
    }

    public static Instruction plan(Organism organism, Environment environment) {
        int fullOpcodeId = environment.getMolecule(organism.getIp()).toInt();
        return new NopInstruction(organism, fullOpcodeId);
    }
}