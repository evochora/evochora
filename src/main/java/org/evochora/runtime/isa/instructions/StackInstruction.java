package org.evochora.runtime.isa.instructions;

import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;

import java.util.Deque;
import java.util.NoSuchElementException;

/**
 * Handles stack manipulation instructions like DUP, SWAP, DROP, and ROT.
 * <p>
 * Note: These are part of the DATA family but handled by a separate class for cleaner implementation.
 */
public class StackInstruction extends Instruction {

    private static int family;

    /**
     * Registers all stack manipulation instructions with the instruction registry.
     * <p>
     * Note: Stack operations share the DATA family ID, continuing both the operation numbers and
     * the indices within the family from DataInstruction.
     *
     * @param f the family ID for this instruction family (should be DATA family)
     */
    public static void register(int f) {
        family = f;
        // Operation 3: DUP (duplicate top of stack)
        reg(3, 8, "DUP");
        // Operation 4: SWAP (swap top two stack values)
        reg(4, 9, "SWAP");
        // Operation 5: DROP (discard top of stack)
        reg(5, 10, "DROP");
        // Operation 6: ROT (stack rotate)
        reg(6, 11, "ROT");
    }

    private static void reg(int op, int index, String name, OperandSource... sources) {
        Instruction.registerOp(StackInstruction.class, StackInstruction::new, family, op, index, name, true, sources);
    }

    /**
     * Constructs a new StackInstruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    public StackInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context) {
        Organism organism = context.getOrganism();
        Deque<Object> ds = organism.getDataStack();
        String opName = getName();

        try {
            switch (opName) {
                case "DUP":
                    if (ds.isEmpty()) { organism.instructionFailed("Stack Underflow for DUP."); return; }
                    if (!organism.pushData(ds.peek())) { return; }
                    break;

                case "SWAP":
                    if (ds.size() < 2) { organism.instructionFailed("Stack Underflow for SWAP."); return; }
                    Object a = ds.pop();
                    Object b = ds.pop();
                    if (!organism.pushData(a)) { return; }
                    if (!organism.pushData(b)) { return; }
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
                    if (!organism.pushData(b_rot)) { return; }
                    if (!organism.pushData(c)) { return; }
                    if (!organism.pushData(a_rot)) { return; }
                    break;

                default:
                    organism.instructionFailed("Unknown stack instruction: " + opName);
            }
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during " + opName);
            return;
        }
    }

    /**
     * Plans the execution of a stack instruction.
     * @param organism The organism that will execute the instruction.
     * @param environment The environment in which the instruction will be executed.
     * @return The planned instruction.
     */
    public static Instruction plan(Organism organism, Environment environment) {
        int fullOpcodeId = environment.getMolecule(organism.getIp()).value();
        return new StackInstruction(organism, fullOpcodeId);
    }
}