package org.evochora.runtime.spi;

import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;

/**
 * Interface for plugins that handle organism birth events.
 * <p>
 * Birth handlers are called in the synchronous post-Execute phase of each tick,
 * once for every newborn organism created by FORK/FRKI/FRKS during that tick.
 * Handlers run after ownership transfer but before genome hash computation,
 * allowing them to modify the newborn's body (e.g., gene duplication) with
 * the final hash reflecting all modifications.
 * </p>
 * <p>
 * Unlike {@link IDeathHandler}, birth handlers receive full environment access
 * rather than a restricted context, because they may need to scan the newborn's
 * entire owned cell set.
 * </p>
 * <p>
 * <b>Thread Safety:</b> Birth handlers run in the sequential post-Execute phase,
 * outside the parallelizable Execute loop. No additional synchronization is needed.
 * </p>
 * <p>
 * Implementations must provide a constructor with signature:
 * {@code (IRandomProvider rng, com.typesafe.config.Config options)}
 * </p>
 *
 * @see IDeathHandler
 * @see ITickPlugin
 */
public interface IBirthHandler extends ISimulationPlugin {

    /**
 * Handle a newborn organism immediately after transfer of ownership and before its genome hash is computed.
 *
 * <p>Invoked once per newborn during the sequential post-Execute phase; implementations may modify the child's
 * body or owned cells. Any changes made here will be reflected in the final genome hash. The provided
 * Environment grants full read/write access to inspect or mutate simulation state related to the child.</p>
 *
 * @param child the newly born organism
 * @param environment the simulation environment with full read/write access
 */
    void onBirth(Organism child, Environment environment);
}