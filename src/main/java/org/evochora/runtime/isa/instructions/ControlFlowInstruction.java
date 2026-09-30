package org.evochora.runtime.isa.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.internal.services.ProcedureCallHandler;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;

import java.util.List;

import static org.evochora.runtime.isa.Instruction.OperandSource.*;

/**
 * Handles control flow instructions like CALL, RET, and JMP.
 * It uses a ProcedureCallHandler for CALL and RET instructions.
 */
public class ControlFlowInstruction extends Instruction {

    private static int family;

    /**
     * Registers all control flow instructions with the instruction registry.
     *
     * @param f the family ID for this instruction family
     */
    public static void register(int f) {
        family = f;
        // Operation 0: JMP (Jump)
        reg(0, 0, "JMPR", REGISTER);
        reg(0, 1, "JMPS", STACK);
        reg(0, 2, "JMPI", LABEL);
        // Operation 1: CALL (Call subroutine)
        reg(1, 3, "CALL", LABEL);
        // Operation 2: RET (Return from subroutine)
        reg(2, 4, "RET");

        // A call is left out: its return continues with the cell behind it.
        declareNeverFallsThrough("JMPR");
        declareNeverFallsThrough("JMPS");
        declareNeverFallsThrough("JMPI");
        declareNeverFallsThrough("RET");
    }

    private static void reg(int op, int index, String name, OperandSource... sources) {
        Instruction.registerOp(ControlFlowInstruction.class, ControlFlowInstruction::new, family, op, index, name, true, sources);
    }

    /**
     * Constructs a new ControlFlowInstruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    public ControlFlowInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context) {
        Organism organism = context.getOrganism();
        Environment environment = context.getWorld();

        String opName = getName();
        List<Operand> operands = resolveOperands(environment);

        switch (opName) {
            case "CALL":
                if (operands.size() < 1) { organism.instructionFailed("CALL requires target label hash."); return; }
                Object callTargetObj = operands.get(0).value();
                if (!(callTargetObj instanceof Integer)) { organism.instructionFailed("CALL target must be a label hash."); return; }
                int callLabelHash = (Integer) callTargetObj;
                int[] callTargetIp = resolveLabelTarget(callLabelHash, organism.getIp(), organism, environment);
                if (callTargetIp == null) {
                    organism.instructionFailed("CALL: No matching label found for hash "
                            + labelHashText(callLabelHash));
                    return;
                }
                ProcedureCallHandler.executeCall(context, callTargetIp, callLabelHash);
                break;
            case "RET":
                ProcedureCallHandler.executeReturn(context);
                break;
            case "JMPI":
                if (operands.size() < 1) { organism.instructionFailed("JMPI requires target label hash."); return; }
                Object jmpiTargetObj = operands.get(0).value();
                if (!(jmpiTargetObj instanceof Integer)) { organism.instructionFailed("JMPI target must be a label hash."); return; }
                int jmpiLabelHash = (Integer) jmpiTargetObj;
                int[] jmpiTargetIp = resolveLabelTarget(jmpiLabelHash, organism.getIp(), organism, environment);
                if (jmpiTargetIp == null) {
                    organism.instructionFailed("JMPI: No matching label found for hash "
                            + labelHashText(jmpiLabelHash));
                    return;
                }
                jumpTo("JMPI", jmpiTargetIp, organism, environment);
                break;
            case "JMPR":
                // Register-based jump: read label hash from register
                if (operands.size() < 1) { organism.instructionFailed("JMPR requires register operand."); return; }
                Object jmprRegObj = operands.get(0).value();
                int jmprLabelHash = extractLabelHash(jmprRegObj);
                if (jmprLabelHash < 0) { organism.instructionFailed("JMPR: Invalid register value for label hash."); return; }
                int[] jmprTargetIp = resolveLabelTarget(jmprLabelHash, organism.getIp(), organism, environment);
                if (jmprTargetIp == null) {
                    organism.instructionFailed("JMPR: No matching label found for hash "
                            + labelHashText(jmprLabelHash));
                    return;
                }
                jumpTo("JMPR", jmprTargetIp, organism, environment);
                break;
            case "JMPS":
                // Stack-based jump: pop label hash from stack
                if (operands.size() < 1) { organism.instructionFailed("JMPS requires stack operand."); return; }
                Object jmpsStackObj = operands.get(0).value();
                int jmpsLabelHash = extractLabelHash(jmpsStackObj);
                if (jmpsLabelHash < 0) { organism.instructionFailed("JMPS: Invalid stack value for label hash."); return; }
                int[] jmpsTargetIp = resolveLabelTarget(jmpsLabelHash, organism.getIp(), organism, environment);
                if (jmpsTargetIp == null) {
                    organism.instructionFailed("JMPS: No matching label found for hash "
                            + labelHashText(jmpsLabelHash));
                    return;
                }
                jumpTo("JMPS", jmpsTargetIp, organism, environment);
                break;
            default:
                organism.instructionFailed("Unknown control flow instruction: " + opName);
                break;
        }
    }

    /**
     * Moves the instruction pointer to the code behind a label: the cell one step past the LABEL
     * molecule along the direction of travel. In a bounded world that cell may lie beyond the
     * edge, when the label stands on the last cell; the jump then fails like a jump that finds
     * no label, and the pointer advances past the jump instruction as usual.
     *
     * @param opName      The jump instruction, for the failure reason.
     * @param labelIp     The position of the label the jump resolved to.
     * @param organism    The organism that jumps.
     * @param environment The environment the code lies in.
     */
    private void jumpTo(String opName, int[] labelIp, Organism organism, Environment environment) {
        int[] codeIp = organism.getNextInstructionPosition(labelIp, organism.getDv(), environment);
        if (!environment.exists(codeIp)) {
            organism.instructionFailed(opName + ": Code cell beyond the edge of the world");
            return;
        }
        organism.setIp(codeIp);
        organism.setSkipIpAdvance(true);
    }

    /**
     * Extracts a label hash from a register or stack value.
     * <p>
     * A label hash is a scalar, and its value bits are the hash. A vector names no label, so it
     * yields no hash and the jump fails, as SKJR and SKJS do.
     *
     * @param value The value from register or stack
     * @return The extracted label hash (20-bit), or -1 if the value is not a scalar
     */
    private int extractLabelHash(Object value) {
        if (value instanceof Integer intVal) {
            return intVal & Config.VALUE_MASK;
        }
        return -1;
    }
}