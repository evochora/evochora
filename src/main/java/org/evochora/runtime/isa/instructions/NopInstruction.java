package org.evochora.runtime.isa.instructions;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;

/**
 * Represents the NOP (No Operation) instruction, which does nothing.
 */
public class NopInstruction extends Instruction {

    /**
     * The length of the NOP instruction in the environment.
     */
    public static final int LENGTH = 1;

    /**
     * Constructs a new NopInstruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    public NopInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context, ProgramArtifact artifact) {
        // This instruction intentionally does nothing.
    }

    /**
     * Plans the execution of a NOP instruction.
     * @param organism The organism that will execute the instruction.
     * @param environment The environment in which the instruction will be executed.
     * @return The planned instruction.
     */
    public static Instruction plan(Organism organism, Environment environment) {
        int fullOpcodeId = environment.getMolecule(organism.getIp()).toInt();
        return new NopInstruction(organism, fullOpcodeId);
    }
}