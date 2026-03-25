package org.evochora.runtime.internal.services;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.RegisterBank;
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
     * @param labelHash The hash value of the target label (used to look up the procedure name).
     * @param artifact The program artifact containing metadata about the procedure.
     */
    public void executeCall(int[] targetIp, int labelHash, ProgramArtifact artifact) {
        Organism organism = context.getOrganism();
        Environment environment = context.getWorld();

        if (organism.getCallStack().size() >= Config.CALL_STACK_MAX_DEPTH) {
            organism.instructionFailed("Call stack overflow");
            return;
        }

        CallBindingResolver bindingResolver = new CallBindingResolver(context);
        int[] bindings = bindingResolver.resolveBindings();
        int[] ipBeforeFetch = organism.getIpBeforeFetch();

        Map<Integer, Integer> parameterBindings = new HashMap<>();
        if (bindings != null) {
            for (int i = 0; i < bindings.length; i++) {
                if (i < RegisterBank.FDR.count) {
                    parameterBindings.put(RegisterBank.FDR.base + i, bindings[i]);
                }
            }
        }

        // CALL now only consumes 1 operand (label hash) instead of N (coordinate delta)
        int instructionLength = 1 + 1; // opcode + label hash
        int[] returnIp = ipBeforeFetch;
        for (int i = 0; i < instructionLength; i++) {
            returnIp = organism.getNextInstructionPosition(returnIp, organism.getDvBeforeFetch(), environment);
        }

        Object[] savedRegisters = organism.snapshotStackSavedRegisters();

        String procName = "";
        if (artifact != null && artifact.labelValueToName() != null) {
            String name = artifact.labelValueToName().get(labelHash);
            if (name != null) procName = name;
        }

        // Save caller's persistent register state
        Map<Integer, Object[]> persistentState = organism.getPersistentRegisterState();
        persistentState.put(organism.getCurrentProcLabelHash(), organism.snapshotPersistentRegisters());

        // Check limit before potentially adding a new entry
        if (!persistentState.containsKey(labelHash) && persistentState.size() >= Config.PERSISTENT_STATE_MAX_PROCEDURES) {
            organism.instructionFailed("Persistent register store limit exceeded");
            return;
        }

        Organism.ProcFrame frame = new Organism.ProcFrame(procName, labelHash, returnIp, ipBeforeFetch, savedRegisters, parameterBindings);
        organism.getCallStack().push(frame);

        // Switch to callee's persistent register state
        organism.setCurrentProcLabelHash(labelHash);
        Object[] calleeState = persistentState.get(labelHash);
        if (calleeState != null) {
            organism.restorePersistentRegisters(calleeState);
        } else {
            organism.resetPersistentRegisters();
        }

        // Note: Parameter passing is handled by compiler-generated PUSH/POP sequences.
        // The compiler generates:
        //   - PUSH instructions before CALL to push arguments onto the stack
        //   - POP instructions in the procedure prologue to load parameters into FDRs
        //   - PUSH instructions before RET to push REF parameters back onto the stack
        //   - POP instructions after CALL to copy REF parameters back to original registers
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
        // Save current procedure's persistent register state before leaving
        organism.getPersistentRegisterState().put(organism.getCurrentProcLabelHash(), organism.snapshotPersistentRegisters());

        Organism.ProcFrame returnFrame = organism.getCallStack().pop();

        organism.restoreStackSavedRegisters(returnFrame.savedRegisters());

        // Restore caller's persistent register state
        int callerLabelHash = organism.getCallStack().isEmpty()
                ? Organism.MAIN_LEVEL_LABEL_HASH
                : organism.getCallStack().peek().labelHash();
        organism.setCurrentProcLabelHash(callerLabelHash);
        Object[] callerState = organism.getPersistentRegisterState().get(callerLabelHash);
        if (callerState != null) {
            organism.restorePersistentRegisters(callerState);
        }

        organism.setIp(returnFrame.absoluteReturnIp());
        organism.setSkipIpAdvance(true);
    }
}