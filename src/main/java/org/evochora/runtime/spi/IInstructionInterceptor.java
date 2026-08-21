package org.evochora.runtime.spi;

/**
 * Interface for plugins that intercept and potentially modify instructions
 * during the Plan phase, before conflict resolution and execution.
 * <p>
 * Interceptors run as part of the Plan phase and are invoked concurrently
 * from multiple worker threads on the same instance. Implementations must
 * be thread-safe (e.g. no shared mutable state, or proper synchronization).
 * Each thread receives its own {@link InterceptionContext}, but the interceptor
 * instance is shared. Interceptors have read-write access to the organism
 * and can modify or replace the planned instruction and its operands.
 * <p>
 * Multiple interceptors are called in configuration order (chaining).
 * Each interceptor sees the result of the previous one.
 * <p>
 * To skip an instruction, replace it with NOP (zero cost, instant-skip).
 * <p>
 * Implementations must provide a constructor with signature:
 * {@code (IRandomProvider rng, com.typesafe.config.Config options)}
 *
 * <h2>Randomness</h2>
 * {@link #intercept} runs inside the parallel wave of a tick, where a value taken from a shared
 * random source would be handed out in scheduling order and make the run irreproducible.
 * Randomness inside {@code intercept} therefore comes exclusively from
 * {@code context.getOrganism().getRandom()}: its values depend only on seed, tick and organism.
 * The {@code IRandomProvider} passed to the constructor serves the sequential hooks a plugin may
 * implement in addition (for example {@link IBirthHandler}); a draw from it inside
 * {@code intercept} fails with a {@link org.evochora.runtime.ParallelWaveViolation}, which
 * aborts the tick.
 *
 * @see InterceptionContext
 * @see ITickPlugin
 */
public interface IInstructionInterceptor extends ISimulationPlugin {

    /**
     * Intercepts a planned instruction before conflict resolution.
     * <p>
     * Called once per organism per tick, after instruction planning
     * and operand resolution, but before conflict resolution.
     *
     * @param context Provides access to organism, instruction, and operands
     */
    void intercept(InterceptionContext context);
}
