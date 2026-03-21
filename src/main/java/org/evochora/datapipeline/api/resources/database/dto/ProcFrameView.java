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
    public final List<RegisterValueView> savedPdrs;
    public final List<RegisterValueView> savedFdrs;
    public final Map<Integer, Integer> fdrBindings;

    public ProcFrameView(String procName,
                         int[] absoluteReturnIp,
                         int[] absoluteCallIp,
                         List<RegisterValueView> savedPdrs,
                         List<RegisterValueView> savedFdrs,
                         Map<Integer, Integer> fdrBindings) {
        this.procName = procName;
        this.absoluteReturnIp = absoluteReturnIp;
        this.absoluteCallIp = absoluteCallIp;
        this.savedPdrs = savedPdrs;
        this.savedFdrs = savedFdrs;
        this.fdrBindings = fdrBindings;
    }
}


