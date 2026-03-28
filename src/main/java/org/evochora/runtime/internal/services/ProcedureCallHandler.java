package org.evochora.runtime.internal.services;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;

import java.util.Map;

/**
 * Handles the logic for procedure call (CALL) and return (RET) instructions.
 * All methods are static to avoid per-instruction object allocation on the hotpath.
 */
public final class ProcedureCallHandler {

    private ProcedureCallHandler() {}

    /**
     * Executes a procedure call. This involves resolving parameter bindings,
     * saving the current processor state, and jumping to the target procedure's address.
     * @param context The execution context for the current instruction.
     * @param targetIp The absolute coordinates of the target procedure (resolved via LabelIndex).
     * @param labelHash The hash value of the target label (used to look up the procedure name).
     * @param artifact The program artifact containing metadata about the procedure.
     */
    public static void executeCall(ExecutionContext context, int[] targetIp, int labelHash, ProgramArtifact artifact) {
        Organism organism = context.getOrganism();
        Environment environment = context.getWorld();

        if (organism.getCallStack().size() >= Config.CALL_STACK_MAX_DEPTH) {
            organism.instructionFailed("Call stack overflow");
            return;
        }

        Map<Integer, Integer> bindings = CallBindingResolver.resolveBindings(context);
        int[] ipBeforeFetch = organism.getIpBeforeFetch();

        Map<Integer, Integer> parameterBindings = bindings != null ? bindings : Map.of();

        // CALL now only consumes 1 operand (label hash) instead of N (coordinate delta)
        int instructionLength = 1 + 1; // opcode + label hash
        int[] returnIp = ipBeforeFetch;
        for (int i = 0; i < instructionLength; i++) {
            returnIp = organism.getNextInstructionPosition(returnIp, organism.getDvBeforeFetch(), environment);
        }

        Object[] savedRegisters = organism.isStackSavedDirty()
                ? organism.snapshotStackSavedRegisters()
                : null;

        String procName = "";
        if (artifact != null && artifact.labelValueToName() != null) {
            String name = artifact.labelValueToName().get(labelHash);
            if (name != null) procName = name;
        }

        if (organism.isPersistentDirty()) {
            // Save caller's persistent register state
            Map<Integer, Object[]> persistentState = organism.getPersistentRegisterState();
            persistentState.put(organism.getCurrentProcLabelHash(), organism.snapshotPersistentRegisters());

            // Check limit before potentially adding a new entry
            if (!persistentState.containsKey(labelHash) && persistentState.size() >= Config.PERSISTENT_STATE_MAX_PROCEDURES) {
                organism.instructionFailed("Persistent register store limit exceeded");
                return;
            }

            // Switch to callee's persistent register state
            Object[] calleeState = persistentState.get(labelHash);
            if (calleeState != null) {
                organism.restorePersistentRegisters(calleeState);
            } else {
                organism.resetPersistentRegisters();
            }
        }

        Organism.ProcFrame frame = new Organism.ProcFrame(procName, labelHash, returnIp, ipBeforeFetch, savedRegisters, parameterBindings);
        organism.getCallStack().push(frame);

        // Always track which procedure is active (needed for correct save on RET after first dirty write)
        organism.setCurrentProcLabelHash(labelHash);

        // Note: Parameter passing is handled by compiler-generated PUSH/POP sequences.
        // Skip past the LABEL molecule to the actual procedure code
        int[] codeIp = organism.getNextInstructionPosition(targetIp, organism.getDv(), environment);
        organism.setIp(codeIp);
        organism.setSkipIpAdvance(true);
    }

    /**
     * Executes a procedure return. This involves restoring the processor state
     * from the call stack and jumping back to the return address.
     * @param context The execution context for the current instruction.
     */
    public static void executeReturn(ExecutionContext context) {
        Organism organism = context.getOrganism();

        if (organism.getCallStack().isEmpty()) {
            organism.instructionFailed("Call stack underflow (RET without CALL)");
            return;
        }
        if (organism.isPersistentDirty()) {
            // Save current procedure's persistent register state before leaving
            organism.getPersistentRegisterState().put(organism.getCurrentProcLabelHash(), organism.snapshotPersistentRegisters());
        }

        Organism.ProcFrame returnFrame = organism.getCallStack().pop();

        if (returnFrame.savedRegisters() != null) {
            organism.restoreStackSavedRegisters(returnFrame.savedRegisters());
        } else if (organism.isStackSavedDirty()) {
            organism.resetStackSavedRegisters();
        }

        // Always track which procedure is active
        int callerLabelHash = organism.getCallStack().isEmpty()
                ? Organism.MAIN_LEVEL_LABEL_HASH
                : organism.getCallStack().peek().labelHash();
        organism.setCurrentProcLabelHash(callerLabelHash);

        if (organism.isPersistentDirty()) {
            // Restore caller's persistent register state
            Object[] callerState = organism.getPersistentRegisterState().get(callerLabelHash);
            if (callerState != null) {
                organism.restorePersistentRegisters(callerState);
            } else {
                organism.resetPersistentRegisters();
            }
        }

        organism.setIp(returnFrame.absoluteReturnIp());
        organism.setSkipIpAdvance(true);
    }
}
