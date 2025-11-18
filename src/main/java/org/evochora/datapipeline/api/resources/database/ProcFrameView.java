package org.evochora.datapipeline.api.resources.database;

import java.util.List;
import java.util.Map;

/**
 * View model for a single procedure call frame on the organism call stack.
 */
public final class ProcFrameView {

    public final String procName;
    public final int[] absoluteReturnIp;
    public final List<RegisterValueView> savedPrs;
    public final List<RegisterValueView> savedFprs;
    public final Map<Integer, Integer> fprBindings;

    public ProcFrameView(String procName,
                         int[] absoluteReturnIp,
                         List<RegisterValueView> savedPrs,
                         List<RegisterValueView> savedFprs,
                         Map<Integer, Integer> fprBindings) {
        this.procName = procName;
        this.absoluteReturnIp = absoluteReturnIp;
        this.savedPrs = savedPrs;
        this.savedFprs = savedFprs;
        this.fprBindings = fprBindings;
    }
}


