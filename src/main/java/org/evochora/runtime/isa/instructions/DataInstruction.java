package org.evochora.runtime.isa.instructions;

import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;

import java.util.List;

import static org.evochora.runtime.isa.Instruction.OperandSource.*;

/**
 * Handles data movement instructions like SET, PUSH, and POP.
 * It supports different operand sources and destinations.
 */
public class DataInstruction extends Instruction {

    private static int family;

    /**
     * Registers all data movement instructions with the instruction registry.
     *
     * @param f the family ID for this instruction family
     */
    public static void register(int f) {
        family = f;
        // Operation 0: SET (copy value to register)
        reg(0, 0, "SETR", REGISTER, REGISTER);
        reg(0, 1, "SETI", REGISTER, IMMEDIATE);
        reg(0, 2, "SETV", REGISTER, VECTOR);
        // Operation 1: PUSH (push value onto stack)
        reg(1, 3, "PUSH", REGISTER);
        reg(1, 4, "PUSI", IMMEDIATE);
        reg(1, 5, "PUSV", VECTOR);
        // Operation 2: POP (pop value from stack)
        reg(2, 6, "POP", REGISTER);
        // Operation 7: XCHG (exchange registers)
        reg(7, 7, "XCHG", REGISTER, REGISTER);
    }

    private static void reg(int op, int index, String name, OperandSource... sources) {
        Instruction.registerOp(DataInstruction.class, DataInstruction::new, family, op, index, name, true, sources);
    }

    /**
     * Constructs a new DataInstruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    public DataInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context) {
        Organism organism = context.getOrganism();
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
                Object value = operands.get(0).value();
                if (value == null) { organism.instructionFailed("Null value for PUSH"); return; }
                if (!organism.pushData(value)) { return; }
                break;
            }
            case "POP": {
                if (operands.size() != 1) { organism.instructionFailed("Invalid operands for POP"); return; }
                if (organism.getDataStack().isEmpty()) { organism.instructionFailed("Data stack underflow"); return; }
                Object value = organism.getDataStack().pop();
                if (!writeOperand(operands.get(0).rawSourceId(), value)) {
                    return;
                }
                break;
            }
            case "PUSI": {
                if (operands.size() != 1) { organism.instructionFailed("Invalid operands for PUSI"); return; }
                Object value = operands.get(0).value();
                if (value == null) { organism.instructionFailed("Null value for PUSI"); return; }
                if (!organism.pushData(value)) { return; }
                break;
            }
            case "PUSV": {
                if (operands.size() != 1) { organism.instructionFailed("Invalid operands for PUSV"); return; }
                Object value = operands.get(0).value();
                if (value == null) { organism.instructionFailed("Null value for PUSV"); return; }
                if (!organism.pushData(value)) { return; }
                break;
            }
            case "XCHG": {
                if (operands.size() != 2) { organism.instructionFailed("Invalid operands for XCHG"); return; }
                Operand op1 = operands.get(0);
                Operand op2 = operands.get(1);
                int reg1 = op1.rawSourceId();
                int reg2 = op2.rawSourceId();
                if (reg1 == -1 || reg2 == -1) { organism.instructionFailed("XCHG requires two register operands"); return; }
                Object val1 = op1.value();
                Object val2 = op2.value();
                if (!writeOperand(reg1, val2)) { return; }
                if (!writeOperand(reg2, val1)) { return; }
                break;
            }
            default:
                organism.instructionFailed("Unknown data instruction: " + opName);
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