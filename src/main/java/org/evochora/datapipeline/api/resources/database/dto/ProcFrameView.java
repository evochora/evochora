package org.evochora.datapipeline.api.resources.database.dto;

import java.util.List;
import java.util.Map;

/**
 * View model for a single procedure call frame on the organism call stack.
 */
public final class ProcFrameView {

    public final String procName;
    public final int[] absoluteReturnIp;
    public final int[] absoluteCallIp;
    public final List<RegisterValueView> savedRegisters;
    public final Map<Integer, Integer> parameterBindings;

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
