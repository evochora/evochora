package org.evochora.datapipeline.api.resources.database.dto;

import java.util.List;

/**
 * Dynamic organism state for a specific tick as exposed via HTTP API.
 * <p>
 * Contains both "hot-path" fields (frequently accessed, stored directly in database columns)
 * and "cold-path" fields (decoded from compressed protobuf blobs on demand).
 */
public final class OrganismRuntimeView {

    /** Current energy level (ER). */
    public final int energy;
    /** Instruction pointer coordinates. */
    public final int[] ip;
    /** Direction vector for IP advancement. */
    public final int[] dv;
    /** Data pointer coordinates (one per DP index). */
    public final int[][] dataPointers;
    /** Currently active data pointer index (0 or 1). */
    public final int activeDpIndex;

    /** Data registers (DR0-DR7) with type and value. */
    public final List<RegisterValueView> dataRegisters;
    /** Procedure registers (PR0-PR7) storing code addresses. */
    public final List<RegisterValueView> procedureRegisters;
    /** Formal parameter registers (FPR0-FPR7) for procedure calls. */
    public final List<RegisterValueView> formalParamRegisters;
    /** Location registers (LR0-LR3) storing coordinate vectors. */
    public final List<int[]> locationRegisters;
    /** Data stack contents (LIFO). */
    public final List<RegisterValueView> dataStack;
    /** Location stack contents (LIFO). */
    public final List<int[]> locationStack;
    /** Call stack with procedure frames. */
    public final List<ProcFrameView> callStack;
    /** Whether the last instruction execution failed. */
    public final boolean instructionFailed;
    /** Reason for instruction failure, or {@code null} if not failed. */
    public final String failureReason;
    /** Call stack at time of failure for debugging. */
    public final List<ProcFrameView> failureCallStack;
    /** Disassembled instructions around the current IP. */
    public final InstructionsView instructions;

    /** Entropy register (SR) - thermodynamic constraint alongside energy. */
    public final int entropyRegister;
    /** Molecule marker register (MR) - used for ownership transfer during FORK. */
    public final int moleculeMarkerRegister;

    /**
     * Constructs a new organism runtime view with all state fields.
     *
     * @param energy                  Current energy level (ER).
     * @param ip                      Instruction pointer coordinates.
     * @param dv                      Direction vector for IP advancement.
     * @param dataPointers            Data pointer coordinates.
     * @param activeDpIndex           Currently active data pointer index.
     * @param dataRegisters           Data registers (DR0-DR7).
     * @param procedureRegisters      Procedure registers (PR0-PR7).
     * @param formalParamRegisters    Formal parameter registers (FPR0-FPR7).
     * @param locationRegisters       Location registers (LR0-LR3).
     * @param dataStack               Data stack contents.
     * @param locationStack           Location stack contents.
     * @param callStack               Call stack with procedure frames.
     * @param instructionFailed       Whether the last instruction failed.
     * @param failureReason           Reason for failure, or {@code null}.
     * @param failureCallStack        Call stack at time of failure.
     * @param instructions            Disassembled instructions around IP.
     * @param entropyRegister         Entropy register (SR).
     * @param moleculeMarkerRegister  Molecule marker register (MR).
     */
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
                               List<ProcFrameView> failureCallStack,
                               InstructionsView instructions,
                               int entropyRegister,
                               int moleculeMarkerRegister) {
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
        this.instructions = instructions;
        this.entropyRegister = entropyRegister;
        this.moleculeMarkerRegister = moleculeMarkerRegister;
    }
}


