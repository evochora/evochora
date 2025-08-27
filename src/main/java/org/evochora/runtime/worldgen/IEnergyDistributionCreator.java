package org.evochora.runtime.worldgen;

import org.evochora.runtime.model.Environment;

/**
 * An interface for different strategies to distribute energy in the world.
 */
public interface IEnergyDistributionCreator {
    /**
     * This method is called by the simulation in each tick to potentially
     * introduce new energy into the environment.
     *
     * @param environment The world environment to be modified.
     * @param currentTick The current tick number of the simulation.
     */
    void distributeEnergy(Environment environment, long currentTick);
}