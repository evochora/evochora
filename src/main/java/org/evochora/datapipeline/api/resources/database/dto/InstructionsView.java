package org.evochora.datapipeline.api.resources.database.dto;

/**
 * View model for instruction execution data used by organism debugging APIs.
 * <p>
 * Contains both the last executed instruction and the next instruction (if available).
 */
public final class InstructionsView {

    /**
     * Last executed instruction in the current tick.
     * Null if no instruction was executed in this tick.
     */
    public final InstructionView last;

    /**
     * Next instruction to be executed (from tick+1).
     * Null if tick+1 does not exist or sampling_interval != 1.
     */
    public final InstructionView next;

    /**
     * Creates a new InstructionsView.
     *
     * @param last Last executed instruction in the current tick (null if no instruction was executed)
     * @param next Next instruction to be executed from tick+1 (null if not available)
     */
    public InstructionsView(InstructionView last, InstructionView next) {
        this.last = last;
        this.next = next;
    }
}

