package org.evochora.runtime.isa.instructions;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Handles data movement instructions like SET, PUSH, and POP.
 * It supports different operand sources and destinations.
 */
public class DataInstruction extends Instruction {

    /**
     * Constructs a new DataInstruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    public DataInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context, ProgramArtifact artifact) {
        Organism organism = context.getOrganism();
        try {
            List<Operand> operands = resolveOperands(context.getWorld());
            String opName = getName();

            switch (opName) {
                case "SETI":
                case "SETV": {
                    if (operands.size() != 2) { organism.instructionFailed("Invalid operands for " + opName); return; }
                    Operand dest = operands.get(0);
                    Operand source = operands.get(1);
                    if (!writeOperand(dest.rawSourceId(), source.value())) {
                        return;
                    }
                    break;
                }
                case "SETR": {
                    if (operands.size() != 2) { organism.instructionFailed("Invalid operands for SETR"); return; }
                    Operand dest = operands.get(0);
                    Operand source = operands.get(1);
                    if (!writeOperand(dest.rawSourceId(), source.value())) {
                        return;
                    }
                    break;
                }
                case "PUSH": {
                    if (operands.size() != 1) { organism.instructionFailed("Invalid operands for PUSH"); return; }
                    if (organism.getDataStack().size() >= Config.DS_MAX_DEPTH) { organism.instructionFailed("Stack Overflow"); return; }
                    Object value = operands.get(0).value();
                    if (value == null) { organism.instructionFailed("Null value for PUSH"); return; }
                    organism.getDataStack().push(value);
                    break;
                }
                case "POP": {
                    if (operands.size() != 1) { organism.instructionFailed("Invalid operands for POP"); return; }
                    Object value = organism.getDataStack().pop();
                    if (!writeOperand(operands.get(0).rawSourceId(), value)) {
                        return;
                    }
                    break;
                }
                case "PUSI": {
                    if (operands.size() != 1) { organism.instructionFailed("Invalid operands for PUSI"); return; }
                    if (organism.getDataStack().size() >= Config.DS_MAX_DEPTH) { organism.instructionFailed("Stack Overflow"); return; }
                    Object value = operands.get(0).value();
                    if (value == null) { organism.instructionFailed("Null value for PUSI"); return; }
                    organism.getDataStack().push(value);
                    break;
                }
                case "PUSV": {
                    if (operands.size() != 1) { organism.instructionFailed("Invalid operands for PUSV"); return; }
                    if (organism.getDataStack().size() >= Config.DS_MAX_DEPTH) { organism.instructionFailed("Stack Overflow"); return; }
                    Object value = operands.get(0).value();
                    if (value == null) { organism.instructionFailed("Null value for PUSV"); return; }
                    organism.getDataStack().push(value);
                    break;
                }
                default:
                    organism.instructionFailed("Unknown data instruction: " + opName);
            }
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during data operation.");
        }
    }

    /**
     * Plans the execution of a data instruction.
     * @param organism The organism that will execute the instruction.
     * @param environment The environment in which the instruction will be executed.
     * @return The planned instruction.
     */
    public static Instruction plan(Organism organism, Environment environment) {
        int fullOpcodeId = environment.getMolecule(organism.getIp()).value();
        return new DataInstruction(organism, fullOpcodeId);
    }
}