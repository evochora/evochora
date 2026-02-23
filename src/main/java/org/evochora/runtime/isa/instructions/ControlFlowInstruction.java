package org.evochora.runtime.isa.instructions;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.internal.services.ProcedureCallHandler;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Variant;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;

import java.util.List;
import java.util.NoSuchElementException;

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
        reg(0, Variant.R, "JMPR", REGISTER);
        reg(0, Variant.S, "JMPS", STACK);
        reg(0, Variant.L, "JMPI", LABEL);
        // Operation 1: CALL (Call subroutine)
        reg(1, Variant.L, "CALL", LABEL);
        // Operation 2: RET (Return from subroutine)
        reg(2, Variant.NONE, "RET");
    }

    private static void reg(int op, int variant, String name, OperandSource... sources) {
        Instruction.registerOp(ControlFlowInstruction.class, ControlFlowInstruction::new, family, op, variant, name, sources);
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
    public java.util.List<Operand> resolveOperands(Environment environment) {
        String opName = getName();
        if ("CALL".equals(opName) || "JMPI".equals(opName)) {
            // Fuzzy jump: fetch a single label hash value (20-bit, masked with VALUE_MASK)
            java.util.List<Operand> resolved = new java.util.ArrayList<>();
            int[] currentIp = organism.getIpBeforeFetch();

            Organism.FetchResult res = organism.fetchSignedArgument(currentIp, environment);
            int labelHash = res.value() & Config.VALUE_MASK;

            resolved.add(new Operand(labelHash, -1));
            return resolved;
        }
        return super.resolveOperands(environment);
    }

    @Override
    public void execute(ExecutionContext context, ProgramArtifact artifact) {
        ProcedureCallHandler callHandler = new ProcedureCallHandler(context);
        Organism organism = context.getOrganism();
        Environment environment = context.getWorld();

        String opName = getName();
        List<Operand> operands = resolveOperands(environment);

        try {
            switch (opName) {
                case "CALL":
                    if (operands.size() < 1) { organism.instructionFailed("CALL requires target label hash."); return; }
                    Object callTargetObj = operands.get(0).value();
                    if (!(callTargetObj instanceof Integer)) { organism.instructionFailed("CALL target must be a label hash."); return; }
                    int callLabelHash = (Integer) callTargetObj;
                    int[] callTargetIp = resolveLabelTarget(callLabelHash, organism.getIp(), organism, environment);
                    if (callTargetIp == null) {
                        organism.instructionFailed("CALL: No matching label found for hash " + callLabelHash);
                        return;
                    }
                    callHandler.executeCall(callTargetIp, callLabelHash, artifact);
                    break;
                case "RET":
                    callHandler.executeReturn();
                    break;
                case "JMPI":
                    if (operands.size() < 1) { organism.instructionFailed("JMPI requires target label hash."); return; }
                    Object jmpiTargetObj = operands.get(0).value();
                    if (!(jmpiTargetObj instanceof Integer)) { organism.instructionFailed("JMPI target must be a label hash."); return; }
                    int jmpiLabelHash = (Integer) jmpiTargetObj;
                    int[] jmpiTargetIp = resolveLabelTarget(jmpiLabelHash, organism.getIp(), organism, environment);
                    if (jmpiTargetIp == null) {
                        organism.instructionFailed("JMPI: No matching label found for hash " + jmpiLabelHash);
                        return;
                    }
                    // Skip past the LABEL molecule to the actual code
                    int[] jmpiCodeIp = organism.getNextInstructionPosition(jmpiTargetIp, organism.getDv(), environment);
                    organism.setIp(jmpiCodeIp);
                    organism.setSkipIpAdvance(true);
                    break;
                case "JMPR":
                    // Register-based jump: read label hash from register
                    if (operands.size() < 1) { organism.instructionFailed("JMPR requires register operand."); return; }
                    Object jmprRegObj = operands.get(0).value();
                    int jmprLabelHash = extractLabelHash(jmprRegObj);
                    if (jmprLabelHash < 0) { organism.instructionFailed("JMPR: Invalid register value for label hash."); return; }
                    int[] jmprTargetIp = resolveLabelTarget(jmprLabelHash, organism.getIp(), organism, environment);
                    if (jmprTargetIp == null) {
                        organism.instructionFailed("JMPR: No matching label found for hash " + jmprLabelHash);
                        return;
                    }
                    // Skip past the LABEL molecule to the actual code
                    int[] jmprCodeIp = organism.getNextInstructionPosition(jmprTargetIp, organism.getDv(), environment);
                    organism.setIp(jmprCodeIp);
                    organism.setSkipIpAdvance(true);
                    break;
                case "JMPS":
                    // Stack-based jump: pop label hash from stack
                    if (operands.size() < 1) { organism.instructionFailed("JMPS requires stack operand."); return; }
                    Object jmpsStackObj = operands.get(0).value();
                    int jmpsLabelHash = extractLabelHash(jmpsStackObj);
                    if (jmpsLabelHash < 0) { organism.instructionFailed("JMPS: Invalid stack value for label hash."); return; }
                    int[] jmpsTargetIp = resolveLabelTarget(jmpsLabelHash, organism.getIp(), organism, environment);
                    if (jmpsTargetIp == null) {
                        organism.instructionFailed("JMPS: No matching label found for hash " + jmpsLabelHash);
                        return;
                    }
                    // Skip past the LABEL molecule to the actual code
                    int[] jmpsCodeIp = organism.getNextInstructionPosition(jmpsTargetIp, organism.getDv(), environment);
                    organism.setIp(jmpsCodeIp);
                    organism.setSkipIpAdvance(true);
                    break;
                default:
                    organism.instructionFailed("Unknown control flow instruction: " + opName);
                    break;
            }
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during control flow operation.");
        } catch (ClassCastException e) {
            organism.instructionFailed("Invalid operand type for control flow operation.");
        }
    }

    /**
     * Extracts a label hash from a register or stack value.
     * Handles different value types that may be stored in registers/stack:
     * <ul>
     *   <li>Integer: uses directly as hash (masked to 20 bits)</li>
     *   <li>Molecule: extracts the value field</li>
     *   <li>int[] (legacy vector): uses first element as hash</li>
     * </ul>
     *
     * @param value The value from register or stack
     * @return The extracted label hash (20-bit), or -1 if extraction failed
     */
    private int extractLabelHash(Object value) {
        if (value instanceof Integer intVal) {
            return intVal & Config.VALUE_MASK;
        }
        if (value instanceof org.evochora.runtime.model.Molecule mol) {
            return mol.value() & Config.VALUE_MASK;
        }
        if (value instanceof int[] arr && arr.length > 0) {
            // Legacy: treat first element as hash for backwards compatibility
            return arr[0] & Config.VALUE_MASK;
        }
        return -1;
    }
}