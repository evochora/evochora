package org.evochora.runtime.isa;

import org.evochora.runtime.model.Environment;
import org.evochora.runtime.spi.ISerializable;

/**
 * An interface for different strategies to distribute energy in the world.
 * <p>
 * Implements {@link ISerializable} to support simulation checkpointing and resume.
 * Strategies may maintain internal state (e.g., geyser locations) that must be
 * serialized for checkpoints to work correctly.
 * </p>
 */
public interface IEnergyDistributionCreator extends ISerializable {
    /**
     * This method is called by the simulation in each tick to potentially
     * introduce new energy into the environment.
     *
     * @param environment The world environment to be modified.
     * @param currentTick The current tick number of the simulation.
     */
    void distributeEnergy(Environment environment, long currentTick);
}