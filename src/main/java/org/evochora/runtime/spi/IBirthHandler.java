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
     * Called once for each newborn organism, after ownership transfer and before
     * genome hash computation.
     *
     * @param child The newly born organism.
     * @param environment The simulation environment (full read/write access).
     */
    void onBirth(Organism child, Environment environment);
}
