package org.evochora.runtime;

import java.util.Arrays;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Organism;

/**
 * Signals a defect in the runtime or in a plugin that surfaced while a tick was running, and
 * names the place in the run where it did: the tick, the organism and the instruction.
 * <p>
 * An organism's own failure — a wrong operand, an empty stack, a cell it may not touch — is never
 * an exception: it is booked through {@link Organism#instructionFailed(String)} and costs the error
 * penalty. Every exception that escapes the work done for one organism is therefore a defect, and
 * the tick loop wraps it in this type at the point where the organism and its instruction are
 * known, so that the log names them alongside the stack trace of the cause. The fault ends the
 * run: the state it leaves behind is not a state any complete tick produces, and it is neither
 * persisted nor continued.
 */
public final class SimulationFault extends IllegalStateException {

    private SimulationFault(String message, RuntimeException cause) {
        super(message, cause);
    }

    /**
     * Creates the fault for the work done on one organism's instruction.
     *
     * @param tick the tick that was running
     * @param organism the organism whose instruction was being planned, resolved or executed
     * @param instruction the instruction, or {@code null} when the fault occurred before one was
     *        planned
     * @param cause the exception the defect raised
     * @return the fault, with the cause attached
     */
    public static SimulationFault inInstruction(long tick, Organism organism, Instruction instruction,
                                                RuntimeException cause) {
        StringBuilder text = new StringBuilder("Organism ").append(organism.getId())
                .append(" at tick ").append(tick);
        if (instruction != null) {
            text.append(", instruction ").append(instruction.getName())
                    .append(" (opcode ").append(instruction.getFullOpcodeId()).append(')');
        }
        text.append(" at ").append(Arrays.toString(organism.getIp())).append(": ").append(cause);
        return new SimulationFault(text.toString(), cause);
    }

    /**
     * Creates the fault for a plugin or handler that the tick loop ran.
     *
     * @param tick the tick that was running
     * @param plugin the plugin or handler that threw
     * @param organism the organism the plugin or handler was run for, or {@code null} when it was
     *        run for the tick as a whole
     * @param cause the exception the defect raised
     * @return the fault, with the cause attached
     */
    public static SimulationFault inPlugin(long tick, Object plugin, Organism organism, RuntimeException cause) {
        return new SimulationFault(plugin.getClass().getName()
                + (organism == null ? "" : " for organism " + organism.getId())
                + " at tick " + tick + ": " + cause, cause);
    }
}
