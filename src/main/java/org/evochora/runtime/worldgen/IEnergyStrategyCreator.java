package org.evochora.runtime.worldgen;

import java.util.Map;
import org.evochora.runtime.internal.services.IRandomProvider;

/**
 * A functional interface for creating energy distribution strategies.
 */
@FunctionalInterface
public interface IEnergyStrategyCreator {
    /**
     * Creates a new energy distribution creator.
     * @param params The parameters for the strategy.
     * @param randomProvider The random number provider.
     * @return The created energy distribution creator.
     */
    IEnergyDistributionCreator create(Map<String, Object> params, IRandomProvider randomProvider);
}