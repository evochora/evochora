package org.evochora.runtime.internal.services;

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
     * Executes a procedure call. This involves saving the current processor state
     * and jumping to the target procedure's address. Parameters are not bound here:
     * the compiler emits PUSH/POP marshalling instructions around the CALL and in the
     * procedure's prologue, so the machine code binds them on its own.
     * @param context The execution context for the current instruction.
     * @param targetIp The absolute coordinates of the target procedure (resolved via LabelIndex).
     * @param labelHash The hash value of the target label, kept in the frame so that observers can
     *                  resolve the procedure name when it is actually displayed.
     */
    public static void executeCall(ExecutionContext context, int[] targetIp, int labelHash) {
        Organism organism = context.getOrganism();
        Environment environment = context.getWorld();

        int[] ipBeforeFetch = organism.getIpBeforeFetch();

        // The procedure's code is the cell one step past its label. In a bounded world that cell
        // may lie beyond the edge; the call then fails before anything of the caller's state is
        // touched.
        int[] codeIp = organism.getNextInstructionPosition(targetIp, organism.getDv(), environment);
        if (!environment.exists(codeIp)) {
            organism.instructionFailed("CALL: Code cell beyond the edge of the world");
            return;
        }

        // CALL now only consumes 1 operand (label hash) instead of N (coordinate delta)
        int instructionLength = 1 + 1; // opcode + label hash
        // The return address may lie beyond the edge of a bounded world when the CALL stands on
        // its last cells. It is stored as it is: returning there is the step that cannot be
        // taken, and RET fails on it and recovers.
        int[] returnIp = ipBeforeFetch;
        for (int i = 0; i < instructionLength; i++) {
            returnIp = organism.getNextInstructionPosition(returnIp, organism.getDvBeforeFetch(), environment);
        }

        Object[] savedRegisters = organism.isStackSavedDirty()
                ? organism.snapshotStackSavedRegisters()
                : null;

        Map<Integer, Object[]> persistentState = organism.isPersistentDirty()
                ? organism.getPersistentRegisterState()
                : null;

        // Check limit before saving — avoid unnecessary snapshot + put if limit exceeded
        if (persistentState != null
                && !persistentState.containsKey(labelHash)
                && persistentState.size() >= Config.PERSISTENT_STATE_MAX_PROCEDURES) {
            organism.instructionFailed("Persistent register store limit exceeded");
            return;
        }

        // The frame goes on the call stack before the persistent registers are switched, so that a
        // full call stack fails the call with the caller's state untouched.
        Organism.ProcFrame frame = new Organism.ProcFrame(labelHash, returnIp, ipBeforeFetch, savedRegisters);
        if (!organism.pushCallFrame(frame)) {
            return;
        }

        if (persistentState != null) {
            // Save caller's persistent register state
            persistentState.put(organism.getCurrentProcLabelHash(), organism.snapshotPersistentRegisters());

            // Switch to callee's persistent register state
            Object[] calleeState = persistentState.get(labelHash);
            if (calleeState != null) {
                organism.restorePersistentRegisters(calleeState);
            } else {
                organism.resetPersistentRegisters();
            }
        }

        // Always track which procedure is active (needed for correct save on RET after first dirty write)
        organism.setCurrentProcLabelHash(labelHash);

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

        // The frame is popped and the caller's registers are back either way. In a bounded world
        // the return address may lie beyond the edge; that step fails, and the pointer is
        // recovered from the next frame or the birth position.
        int[] returnIp = returnFrame.absoluteReturnIp();
        Environment environment = context.getWorld();
        if (!environment.exists(returnIp)) {
            organism.instructionFailed("RET: Return address beyond the edge of the world");
            organism.recoverFromStall();
        } else {
            organism.setIp(returnIp);
        }
        organism.setSkipIpAdvance(true);
    }
}
