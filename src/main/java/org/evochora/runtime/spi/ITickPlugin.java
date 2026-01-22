package org.evochora.runtime.spi;

import org.evochora.runtime.Simulation;

/**
 * Interface for plugins that execute once per simulation tick.
 * <p>
 * Tick plugins run at the beginning of each tick, before the Plan-Resolve-Execute
 * cycle. They have full read-write access to the simulation, including:
 * <ul>
 *   <li>Environment (molecules, ownership, etc.) via {@code simulation.getEnvironment()}</li>
 *   <li>All organisms via {@code simulation.getOrganisms()}</li>
 *   <li>Current tick via {@code simulation.getCurrentTick()}</li>
 *   <li>Random provider via {@code simulation.getRandomProvider()}</li>
 * </ul>
 * </p>
 * <p>
 * Plugins are executed sequentially in their configured order. A plugin may use
 * internal multithreading if needed, but must ensure thread-safety itself.
 * </p>
 * <p>
 * Implementations must provide a constructor with signature:
 * {@code (IRandomProvider rng, com.typesafe.config.Config options)}
 * </p>
 *
 * @see ISimulationPlugin
 */
public interface ITickPlugin extends ISimulationPlugin {

    /**
     * Executes the plugin logic for the current tick.
     * <p>
     * Called once per tick, before Plan-Resolve-Execute. The plugin has full
     * access to modify the simulation state.
     * </p>
     *
     * @param simulation The simulation instance providing access to environment and organisms.
     */
    void execute(Simulation simulation);
}