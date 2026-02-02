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
 * Currently, interceptors run sequentially per organism. Future versions may
 * parallelize interception across organisms. Interceptors must not:
 * <ul>
 *   <li>Modify shared state between organisms</li>
 *   <li>Access other organisms' data</li>
 *   <li>Write to the environment (read-only via organism is acceptable)</li>
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
     */
    public Organism getOrganism() {
        return organism;
    }

    /**
     * Returns the currently planned instruction.
     *
     * @return The instruction that will be executed
     */
    public Instruction getInstruction() {
        return instruction;
    }

    /**
     * Replaces the planned instruction.
     * <p>
     * Use this to substitute a different instruction. For example,
     * replace with NOP to effectively skip the instruction (zero cost).
     *
     * @param newInstruction The instruction to execute instead
     */
    public void setInstruction(Instruction newInstruction) {
        this.instruction = newInstruction;
    }

    /**
     * Returns the resolved operands for the current instruction.
     * <p>
     * The returned list is a direct reference to the instruction's cached operands.
     * Modifications are visible to:
     * <ul>
     *   <li>Subsequent interceptors in the chain (they see your changes)</li>
     *   <li>Conflict resolution (uses operands to determine target coordinates)</li>
     *   <li>The Execute phase (instruction sees modified values)</li>
     * </ul>
     * <p>
     * The list is safe to call multiple times (idempotent, returns same cached list).
     *
     * @return The list of resolved operands (mutable, shared reference)
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
     */
    public void setOperand(int index, Instruction.Operand newOperand) {
        getOperands().set(index, newOperand);
    }
}
