package org.evochora.runtime.isa.instructions;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;

import java.util.Deque;
import java.util.NoSuchElementException;

/**
 * Handles stack manipulation instructions like DUP, SWAP, DROP, and ROT.
 */
public class StackInstruction extends Instruction {

    /**
     * Constructs a new StackInstruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    public StackInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context, ProgramArtifact artifact) {
        Organism organism = context.getOrganism();
        Deque<Object> ds = organism.getDataStack();
        String opName = getName();

        try {
            switch (opName) {
                case "DUP":
                    if (ds.isEmpty()) { organism.instructionFailed("Stack Underflow for DUP."); return; }
                    if (ds.size() >= Config.DS_MAX_DEPTH) { organism.instructionFailed("Stack Overflow for DUP."); return; }
                    ds.push(ds.peek());
                    break;

                case "SWAP":
                    if (ds.size() < 2) { organism.instructionFailed("Stack Underflow for SWAP."); return; }
                    Object a = ds.pop();
                    Object b = ds.pop();
                    ds.push(a);
                    ds.push(b);
                    break;

                case "DROP":
                    if (ds.isEmpty()) { organism.instructionFailed("Stack Underflow for DROP."); return; }
                    ds.pop();
                    break;

                case "ROT":
                    if (ds.size() < 3) { organism.instructionFailed("Stack Underflow for ROT."); return; }
                    Object c = ds.pop();
                    Object b_rot = ds.pop();
                    Object a_rot = ds.pop();
                    ds.push(b_rot);
                    ds.push(c);
                    ds.push(a_rot);
                    break;

                default:
                    organism.instructionFailed("Unknown stack instruction: " + opName);
            }
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during " + opName);
        }
    }

    /**
     * Plans the execution of a stack instruction.
     * @param organism The organism that will execute the instruction.
     * @param environment The environment in which the instruction will be executed.
     * @return The planned instruction.
     */
    public static Instruction plan(Organism organism, Environment environment) {
        int fullOpcodeId = environment.getMolecule(organism.getIp()).toInt();
        return new StackInstruction(organism, fullOpcodeId);
    }
}