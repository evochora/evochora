package org.evochora.runtime.spi;

/**
 * Interface for plugins that handle organism death events.
 * <p>
 * Death handlers are called during the Execute phase, immediately after an organism
 * dies (energy reaches zero) and before its ownership is cleared. Handlers have
 * restricted access to only the dying organism's cells via {@link DeathContext}.
 * </p>
 * <p>
 * <b>Thread Safety:</b> The restricted cell access through DeathContext ensures
 * handlers are thread-safe for future parallel execution - each handler only
 * modifies cells owned by the specific dying organism.
 * </p>
 * <p>
 * After all handlers complete, the simulation automatically calls
 * {@code clearOwnershipFor()} - handlers should not call this themselves.
 * </p>
 * <p>
 * Implementations must provide a constructor with signature:
 * {@code (IRandomProvider rng, com.typesafe.config.Config options)}
 * </p>
 *
 * @see DeathContext
 * @see ITickPlugin
 */
public interface IDeathHandler extends ISimulationPlugin {

    /**
     * Called when an organism dies, before ownership is cleared.
     * <p>
     * The handler can read and modify only the dying organism's cells
     * through the provided context. Modifications to other cells are
     * not possible through this API.
     * </p>
     *
     * @param context Provides restricted access to the dying organism's cells
     */
    void onDeath(DeathContext context);
}
