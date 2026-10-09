package org.evochora.runtime.spi;

import java.util.List;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;

/**
 * Context object passed to {@link IInstructionInterceptor#intercept(InterceptionContext)}.
 * <p>
 * Provides read-write access to the organism and planned instruction.
 * This class is reused across organisms within a tick to avoid allocation.
 *
 * <h2>Operand Modification Semantics</h2>
 * <ul>
 *   <li>Operands are resolved once during the Plan phase and cached</li>
 *   <li>Modifications via {@link #setOperand} are visible to subsequent interceptors (chaining)</li>
 *   <li>The Execute phase sees the final modified operands</li>
 *   <li>To skip an instruction entirely, use {@link #setInstruction} to replace with NOP</li>
 * </ul>
 *
 * <h2>Environment Access</h2>
 * Environment is intentionally not exposed directly to ensure interceptors
 * remain parallelizable (no shared mutable state). While interceptors could
 * access the environment via {@code getOrganism().getSimulation().getEnvironment()},
 * doing so may cause non-deterministic behavior in future parallel implementations.
 * <p>
 * <b>Contract:</b> Interceptors should only read/write the organism and instruction,
 * not the environment.
 *
 * <h2>Thread Safety</h2>
 * Interceptors run inside the parallel wave of a tick: several organisms are intercepted at the
 * same time on different threads, each thread with its own context instance. Interceptors must
 * not:
 * <ul>
 *   <li>Modify shared state between organisms</li>
 *   <li>Access other organisms' data</li>
 *   <li>Write to the environment (read-only via organism is acceptable)</li>
 *   <li>Draw from a shared random source — randomness comes from
 *       {@code getOrganism().getRandom()}, whose values depend only on seed, tick and organism</li>
 * </ul>
 *
 * @see IInstructionInterceptor
 */
public class InterceptionContext {

    private Organism organism;
    private Instruction instruction;

    /**
     * Resets context for reuse within the tick loop - zero allocation.
     * <p>
     * <b>Internal use only:</b> Called by Simulation at the start of each
     * organism's interception cycle. Interceptors should not call this method.
     *
     * @param organism The organism whose instruction is being intercepted
     * @param instruction The planned instruction
     */
    public void reset(Organism organism, Instruction instruction) {
        this.organism = organism;
        this.instruction = instruction;
    }

    /**
     * Returns the organism whose instruction is being intercepted.
     * <p>
     * The organism can be modified (registers, energy, etc.).
     *
     * @return The organism (read-write access)
     * @throws IllegalStateException if context was not initialized via {@link #reset}
     */
    public Organism getOrganism() {
        if (organism == null) {
            throw new IllegalStateException("InterceptionContext not initialized - reset() must be called first");
        }
        return organism;
    }

    /**
     * Returns the currently planned instruction.
     *
     * @return The instruction that will be executed
     * @throws IllegalStateException if context was not initialized via {@link #reset}
     */
    public Instruction getInstruction() {
        if (instruction == null) {
            throw new IllegalStateException("InterceptionContext not initialized - reset() must be called first");
        }
        return instruction;
    }

    /**
     * Replaces the planned instruction.
     * <p>
     * Use this to substitute a different instruction. For example,
     * replace with NOP to effectively skip the instruction (zero cost).
     * <p>
     * The replacement must be created for the organism being intercepted, as returned by
     * {@link #getOrganism()}. An instruction acts on the organism it was created for, so one
     * created for another organism would change that organism from inside the parallel wave,
     * possibly while another thread executes it. A rejected replacement leaves the planned
     * instruction as it was.
     *
     * @param newInstruction The instruction to execute instead (must not be null)
     * @throws IllegalArgumentException if {@code newInstruction} is null or was created for an
     *                                  organism other than the one being intercepted
     */
    public void setInstruction(Instruction newInstruction) {
        if (newInstruction == null) {
            throw new IllegalArgumentException("Replacement instruction must not be null");
        }
        if (newInstruction.getOrganism() != this.organism) {
            Organism boundTo = newInstruction.getOrganism();
            throw new IllegalArgumentException("Replacement instruction belongs to organism "
                    + (boundTo == null ? "null" : String.valueOf(boundTo.getId()))
                    + ", not to the intercepted organism "
                    + (this.organism == null ? "null" : String.valueOf(this.organism.getId())));
        }
        this.instruction = newInstruction;
    }

    /**
     * Returns the resolved operands for the current instruction.
     * <p>
     * The returned list is a direct reference to the instruction's cached operands, one entry
     * per operand of the instruction. An operand the instruction could not read is
     * {@link Instruction.Operand#MISSING}, and the instruction is then already marked failed;
     * a missing operand is never put into the list, see {@link #setOperand}. The list has a
     * fixed size, because the count mirrors the instruction's argument cells: an operand can be
     * replaced, and adding or removing one throws an {@link UnsupportedOperationException},
     * which ends the tick as a fault of the interceptor.
     * Modifications are visible to:
     * <ul>
     *   <li>Subsequent interceptors in the chain (they see your changes)</li>
     *   <li>Conflict resolution (uses operands to determine target coordinates)</li>
     *   <li>The Execute phase (instruction sees modified values)</li>
     * </ul>
     * <p>
     * The list is safe to call multiple times (idempotent, returns same cached list).
     *
     * @return The list of resolved operands (shared reference, fixed size, elements replaceable)
     */
    public List<Instruction.Operand> getOperands() {
        Environment environment = organism.getSimulation().getEnvironment();
        return instruction.resolveOperands(environment);
    }

    /**
     * Replaces an operand at the given index.
     * <p>
     * This is a convenience method equivalent to:
     * {@code getOperands().set(index, newOperand)}
     * <p>
     * <b>Note:</b> The new operand will be used by subsequent interceptors,
     * conflict resolution, and the Execute phase. Ensure the operand type
     * matches what the instruction expects (scalar vs vector).
     *
     * @param index The index of the operand to replace (0-based)
     * @param newOperand The new operand value
     * @throws IndexOutOfBoundsException if index is out of range
     * @throws IllegalArgumentException if the operand is {@code null} or
     *         {@link Instruction.Operand#MISSING}: an operand without a value stands only for
     *         one the instruction itself could not read, and then the instruction is marked
     *         failed; an interceptor cannot put one into an instruction that is not
     */
    public void setOperand(int index, Instruction.Operand newOperand) {
        if (newOperand == null || newOperand.isMissing()) {
            throw new IllegalArgumentException("An interceptor cannot set an operand without a value: "
                    + newOperand);
        }
        getOperands().set(index, newOperand);
    }
}
