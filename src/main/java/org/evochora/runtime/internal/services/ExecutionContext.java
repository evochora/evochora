package org.evochora.runtime.internal.services;

import java.util.Arrays;
import java.util.Objects;

import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;

/**
 * Encapsulates all the information and dependencies required at the runtime of an instruction,
 * and collects the effects that instruction has on the world.
 * <p>
 * <strong>The context carries nothing from one instruction to the next.</strong> It is reused -
 * one per thread of the parallel wave, mirroring the interception contexts - and the virtual
 * machine calls {@link #reset(Organism)} before every execution. Were a value to survive that
 * boundary, the result would depend on which organisms a thread happened to handle before, which
 * changes with the configured parallelism: the trajectory would no longer be invariant under
 * execution resources, and a resumed run would differ from an uninterrupted one. Only the effect
 * counter has to be cleared for that, because every accessor is bounded by it and the values of
 * a previous instruction are therefore unreachable rather than merely stale.
 * <p>
 * An effect is one thing an instruction did to one cell: it consumed the molecule that stood
 * there, or it stored a molecule in it. An instruction records only what actually happened - a
 * write that found its target occupied, or a read that failed, records nothing and is priced by
 * its base values alone.
 */
public class ExecutionContext {

    /** Fields per recorded effect: kind, molecule, owner. */
    private static final int EFFECT_STRIDE = 3;

    /** Effects an instruction can record before the buffer has to grow. */
    private static final int INITIAL_EFFECTS = 2;

    private static final int KIND_READ = 0;
    private static final int KIND_WRITE = 1;

    private final Environment environment;
    private final boolean isPerformanceMode;

    private Organism organism;

    /** Recorded effects, three fields each; only the first {@link #effectCount} are readable. */
    private int[] effects = new int[INITIAL_EFFECTS * EFFECT_STRIDE];
    private int effectCount;

    /**
     * Constructs a new ExecutionContext.
     * @param environment The environment in which instructions are executed.
     * @param isPerformanceMode A flag indicating if the simulation is in performance mode.
     */
    public ExecutionContext(Environment environment, boolean isPerformanceMode) {
        this.environment = environment;
        this.isPerformanceMode = isPerformanceMode;
    }

    /**
     * Prepares the context for one execution: binds the organism and discards the effects of
     * whatever ran before.
     *
     * @param organism The organism whose instruction is about to execute.
     */
    public void reset(Organism organism) {
        this.organism = organism;
        this.effectCount = 0;
    }

    /**
     * Returns the organism associated with this execution context.
     * @return The organism.
     */
    public Organism getOrganism() {
        return organism;
    }

    /**
     * Returns the environment associated with this execution context.
     * @return The environment.
     */
    public Environment getWorld() {
        return environment;
    }

    /**
     * Checks if the simulation is running in performance mode.
     * @return true if in performance mode, false otherwise.
     */
    public boolean isPerformanceMode() {
        return isPerformanceMode;
    }

    /**
     * Records that the instruction took a molecule out of a cell, leaving it empty.
     *
     * @param moleculeInt The molecule that was taken, in the packed form the environment stored it.
     * @param ownerId The cell's owner before the read; 0 for an unowned cell.
     */
    public void recordRead(int moleculeInt, int ownerId) {
        record(KIND_READ, moleculeInt, ownerId);
    }

    /**
     * Records that the instruction stored a molecule in a cell.
     *
     * @param moleculeInt The molecule that was stored, in the packed form the environment stores it.
     * @param ownerId The cell's owner before the write; 0 for an unowned cell, which is what a
     *                write finds today, because a write only succeeds on an empty cell.
     */
    public void recordWrite(int moleculeInt, int ownerId) {
        record(KIND_WRITE, moleculeInt, ownerId);
    }

    private void record(int kind, int moleculeInt, int ownerId) {
        int base = effectCount * EFFECT_STRIDE;
        if (base == effects.length) {
            effects = Arrays.copyOf(effects, effects.length * 2);
        }
        effects[base] = kind;
        effects[base + 1] = moleculeInt;
        effects[base + 2] = ownerId;
        effectCount++;
    }

    /**
     * Reports how many effects the running instruction has recorded so far.
     *
     * @return The number of readable effects.
     */
    public int effectCount() {
        return effectCount;
    }

    /**
     * Reports which way a recorded effect went.
     *
     * @param index The effect to ask about, below {@link #effectCount()}.
     * @return {@code true} if the effect stored a molecule, {@code false} if it took one out.
     */
    public boolean isWriteAt(int index) {
        return effects[Objects.checkIndex(index, effectCount) * EFFECT_STRIDE] == KIND_WRITE;
    }

    /**
     * Reports the molecule a recorded effect concerns.
     *
     * @param index The effect to ask about, below {@link #effectCount()}.
     * @return The molecule, in the packed form the environment stores.
     */
    public int moleculeAt(int index) {
        return effects[Objects.checkIndex(index, effectCount) * EFFECT_STRIDE + 1];
    }

    /**
     * Reports who owned the cell a recorded effect concerns.
     *
     * @param index The effect to ask about, below {@link #effectCount()}.
     * @return The cell's owner before the effect; 0 for an unowned cell.
     */
    public int ownerAt(int index) {
        return effects[Objects.checkIndex(index, effectCount) * EFFECT_STRIDE + 2];
    }
}
