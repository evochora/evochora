package org.evochora.datapipeline.api.resources.database;

import java.util.List;

/**
 * Dynamic organism state for a specific tick as exposed via HTTP API.
 */
public final class OrganismRuntimeView {

    // Hot-path (from organism_states)
    public final int energy;
    public final int[] ip;
    public final int[] dv;
    public final int[][] dataPointers;
    public final int activeDpIndex;

    // Cold-path (decoded from OrganismRuntimeState)
    public final List<RegisterValueView> dataRegisters;
    public final List<RegisterValueView> procedureRegisters;
    public final List<RegisterValueView> formalParamRegisters;
    public final List<int[]> locationRegisters;
    public final List<RegisterValueView> dataStack;
    public final List<int[]> locationStack;
    public final List<ProcFrameView> callStack;
    public final boolean instructionFailed;
    public final String failureReason;
    public final List<ProcFrameView> failureCallStack;

    public OrganismRuntimeView(int energy,
                               int[] ip,
                               int[] dv,
                               int[][] dataPointers,
                               int activeDpIndex,
                               List<RegisterValueView> dataRegisters,
                               List<RegisterValueView> procedureRegisters,
                               List<RegisterValueView> formalParamRegisters,
                               List<int[]> locationRegisters,
                               List<RegisterValueView> dataStack,
                               List<int[]> locationStack,
                               List<ProcFrameView> callStack,
                               boolean instructionFailed,
                               String failureReason,
                               List<ProcFrameView> failureCallStack) {
        this.energy = energy;
        this.ip = ip;
        this.dv = dv;
        this.dataPointers = dataPointers;
        this.activeDpIndex = activeDpIndex;
        this.dataRegisters = dataRegisters;
        this.procedureRegisters = procedureRegisters;
        this.formalParamRegisters = formalParamRegisters;
        this.locationRegisters = locationRegisters;
        this.dataStack = dataStack;
        this.locationStack = locationStack;
        this.callStack = callStack;
        this.instructionFailed = instructionFailed;
        this.failureReason = failureReason;
        this.failureCallStack = failureCallStack;
    }
}


