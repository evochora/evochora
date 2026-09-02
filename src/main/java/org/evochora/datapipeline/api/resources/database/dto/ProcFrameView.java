package org.evochora.datapipeline.api.resources.database.dto;

import java.util.List;
import java.util.Map;

/**
 * View model for a single procedure call frame on the organism call stack.
 */
public final class ProcFrameView {

    /**
     * Name of the called procedure, resolved from the frame's label hash via the run's program
     * artifact. Empty when the hash is not in that artifact, which happens for a call target
     * created by code mutation; callers distinguish named from unnamed frames by emptiness.
     */
    public final String procName;
    /** Absolute coordinates execution resumes at once the procedure returns. */
    public final int[] absoluteReturnIp;
    /**
     * Absolute coordinates of the CALL instruction that created this frame, used to resolve the
     * parameter bindings against the program artifact. {@code null} for a frame that carries none.
     */
    public final int[] absoluteCallIp;
    /**
     * Caller registers preserved for the duration of the call, as a compact list in
     * {@code RegisterBank.allSavedOnCall()} order rather than keyed by register id.
     */
    public final List<RegisterValueView> savedRegisters;
    /**
     * Maps the formal parameter register id to the caller register it is bound to. Both are full
     * register ids: formal data parameters live in the FDR bank, location parameters in the FLR bank.
     */
    public final Map<Integer, Integer> parameterBindings;

    /**
     * Constructs a view of one call frame.
     *
     * @param procName          Resolved procedure name, empty if the label hash is unknown.
     * @param absoluteReturnIp  Absolute coordinates execution resumes at after the return.
     * @param absoluteCallIp    Absolute coordinates of the originating CALL, or {@code null}.
     * @param savedRegisters    Caller registers preserved for the duration of the call.
     * @param parameterBindings Formal parameter register id to caller register id.
     */
    public ProcFrameView(String procName,
                         int[] absoluteReturnIp,
                         int[] absoluteCallIp,
                         List<RegisterValueView> savedRegisters,
                         Map<Integer, Integer> parameterBindings) {
        this.procName = procName;
        this.absoluteReturnIp = absoluteReturnIp;
        this.absoluteCallIp = absoluteCallIp;
        this.savedRegisters = savedRegisters;
        this.parameterBindings = parameterBindings;
    }
}
