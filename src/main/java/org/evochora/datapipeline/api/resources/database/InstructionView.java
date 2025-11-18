package org.evochora.datapipeline.api.resources.database;

import java.util.List;

/**
 * View model for an executed instruction used by organism debugging APIs.
 */
public final class InstructionView {

    /**
     * Opcode ID of the instruction.
     */
    public final int opcodeId;

    /**
     * Human-readable opcode name (e.g., "SETI", "ADDS").
     */
    public final String opcodeName;

    /**
     * Resolved and formatted arguments.
     */
    public final List<InstructionArgumentView> arguments;

    /**
     * Argument types as strings (e.g., "REGISTER", "IMMEDIATE").
     * <p>
     * This list matches the ISA signature and may be useful for frontend
     * formatting without iterating through arguments.
     */
    public final List<String> argumentTypes;

    /**
     * Total energy cost for executing this instruction.
     */
    public final int energyCost;

    /**
     * Instruction pointer position before instruction fetch.
     */
    public final int[] ipBeforeFetch;

    /**
     * Direction vector before instruction fetch.
     */
    public final int[] dvBeforeFetch;

    /**
     * Whether the instruction execution failed.
     */
    public final boolean failed;

    /**
     * Human-readable reason for failure.
     * Only present if failed is true.
     */
    public final String failureReason;

    /**
     * Creates a new InstructionView.
     *
     * @param opcodeId      Opcode ID of the instruction
     * @param opcodeName    Human-readable opcode name (e.g., "SETI", "ADDS")
     * @param arguments     Resolved and formatted arguments
     * @param argumentTypes Argument types as strings (e.g., "REGISTER", "IMMEDIATE")
     * @param energyCost    Total energy cost for executing this instruction
     * @param ipBeforeFetch Instruction pointer position before instruction fetch
     * @param dvBeforeFetch Direction vector before instruction fetch
     * @param failed        Whether the instruction execution failed
     * @param failureReason Human-readable reason for failure (null if not failed)
     */
    public InstructionView(int opcodeId,
                          String opcodeName,
                          List<InstructionArgumentView> arguments,
                          List<String> argumentTypes,
                          int energyCost,
                          int[] ipBeforeFetch,
                          int[] dvBeforeFetch,
                          boolean failed,
                          String failureReason) {
        this.opcodeId = opcodeId;
        this.opcodeName = opcodeName;
        this.arguments = arguments;
        this.argumentTypes = argumentTypes;
        this.energyCost = energyCost;
        this.ipBeforeFetch = ipBeforeFetch;
        this.dvBeforeFetch = dvBeforeFetch;
        this.failed = failed;
        this.failureReason = failureReason;
    }
}

