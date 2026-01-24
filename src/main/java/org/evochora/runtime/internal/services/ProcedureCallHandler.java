package org.evochora.runtime.internal.services;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles the logic for procedure call (CALL) and return (RET) instructions.
 * This class manages the call stack, parameter bindings, and processor state restoration.
 */
public class ProcedureCallHandler {

    private final ExecutionContext context;

    /**
     * Constructs a new ProcedureCallHandler.
     * @param context The execution context for the current instruction.
     */
    public ProcedureCallHandler(ExecutionContext context) {
        this.context = context;
    }

    /**
     * Executes a procedure call. This involves resolving parameter bindings,
     * saving the current processor state, and jumping to the target procedure's address.
     * @param targetIp The absolute coordinates of the target procedure (resolved via LabelIndex).
     * @param artifact The program artifact containing metadata about the procedure.
     */
    public void executeCall(int[] targetIp, ProgramArtifact artifact) {
        Organism organism = context.getOrganism();
        Environment environment = context.getWorld();

        if (organism.getCallStack().size() >= Config.CALL_STACK_MAX_DEPTH) {
            organism.instructionFailed("Call stack overflow");
            return;
        }

        CallBindingResolver bindingResolver = new CallBindingResolver(context);
        int[] bindings = bindingResolver.resolveBindings();
        int[] ipBeforeFetch = organism.getIpBeforeFetch();

        Map<Integer, Integer> fprBindings = new HashMap<>();
        if (bindings != null) {
            for (int i = 0; i < bindings.length; i++) {
                if (i < organism.getFprs().size()) {
                    fprBindings.put(Instruction.FPR_BASE + i, bindings[i]);
                }
            }
        }

        // CALL now only consumes 1 operand (label hash) instead of N (coordinate delta)
        int instructionLength = 1 + 1; // opcode + label hash
        int[] returnIp = ipBeforeFetch;
        for (int i = 0; i < instructionLength; i++) {
            returnIp = organism.getNextInstructionPosition(returnIp, organism.getDvBeforeFetch(), environment);
        }

        Object[] prsSnapshot = organism.getPrs().toArray();
        Object[] fprsSnapshot = organism.getFprs().toArray();

        String procName = "";

        if (artifact != null) {
            int[] origin = organism.getInitialPosition();
            int[] relTarget = new int[targetIp.length];
            for (int i = 0; i < targetIp.length; i++) {
                relTarget[i] = targetIp[i] - origin[i];
            }

            StringBuilder keyBuilder = new StringBuilder();
            for (int i = 0; i < relTarget.length; i++) {
                if (i > 0) keyBuilder.append('|');
                keyBuilder.append(relTarget[i]);
            }
            String relativeKey = keyBuilder.toString();
            Integer targetAddress = artifact.relativeCoordToLinearAddress().get(relativeKey);
            if (targetAddress != null) {
                String name = artifact.labelAddressToName().get(targetAddress);
                if (name != null) procName = name;
            }
        }

        Organism.ProcFrame frame = new Organism.ProcFrame(procName, returnIp, ipBeforeFetch, prsSnapshot, fprsSnapshot, fprBindings);
        organism.getCallStack().push(frame);

        // Note: Parameter passing is handled by compiler-generated PUSH/POP sequences.
        // The compiler generates:
        //   - PUSH instructions before CALL to push arguments onto the stack
        //   - POP instructions in the procedure prologue to load parameters into FPRs
        //   - PUSH instructions before RET to push REF parameters back onto the stack
        //   - POP instructions after CALL to copy REF parameters back to original registers
        // No manual copy-in from registers to FPRs is needed here.
        /*
        if (bindings != null) {
            for (int i = 0; i < bindings.length; i++) {
                Object value = organism.readOperand(bindings[i]);
                organism.setFpr(i, value);
            }
        }
        */

        // Skip past the LABEL molecule to the actual procedure code
        int[] codeIp = organism.getNextInstructionPosition(targetIp, organism.getDv(), environment);
        organism.setIp(codeIp);
        organism.setSkipIpAdvance(true);
    }

    /**
     * Executes a procedure return. This involves restoring the processor state
     * from the call stack and jumping back to the return address.
     */
    public void executeReturn() {
        Organism organism = context.getOrganism();

        if (organism.getCallStack().isEmpty()) {
            organism.instructionFailed("Call stack underflow (RET without CALL)");
            return;
        }
        Organism.ProcFrame returnFrame = organism.getCallStack().pop();

        organism.restorePrs(returnFrame.savedPrs);
        organism.setIp(returnFrame.absoluteReturnIp);
        organism.setSkipIpAdvance(true);
    }
}