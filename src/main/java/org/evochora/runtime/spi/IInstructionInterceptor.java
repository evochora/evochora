package org.evochora.runtime.spi;

/**
 * Interface for plugins that intercept and potentially modify instructions
 * during the Plan phase, before conflict resolution and execution.
 * <p>
 * Interceptors run as part of the Plan phase and are parallelizable.
 * They have read-write access to the organism and can modify or replace
 * the planned instruction and its operands.
 * <p>
 * Multiple interceptors are called in configuration order (chaining).
 * Each interceptor sees the result of the previous one.
 * <p>
 * To skip an instruction, replace it with NOP (zero cost, instant-skip).
 * <p>
 * Implementations must provide a constructor with signature:
 * {@code (IRandomProvider rng, com.typesafe.config.Config options)}
 * <p>
 * The IRandomProvider enables deterministic random number generation for
 * reproducible simulations. Use {@code rng.asJavaRandom()} for a Random instance.
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
