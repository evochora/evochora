package org.evochora.datapipeline.api.memory;

/**
 * Interface for components that can provide the actual simulation parameters used at runtime.
 * <p>
 * Implemented by services that determine the definitive simulation configuration,
 * such as the simulation engine. In resume mode, the parameters reflect the original
 * run's configuration (from checkpoint metadata), not the current configuration file.
 * <p>
 * Used by {@link org.evochora.datapipeline.ServiceManager} to obtain correct
 * {@link SimulationParameters} for memory estimation regardless of whether the
 * simulation is a fresh run or a resumed one.
 *
 * @see SimulationParameters
 * @see IMemoryEstimatable
 */
public interface ISimulationParameterSource {

    /**
     * Returns the simulation parameters actually used by this component.
     * <p>
     * For fresh runs, these match the current configuration. For resumed runs,
     * these reflect the original run's configuration loaded from checkpoint metadata.
     *
     * @return the definitive simulation parameters for memory estimation
     */
    SimulationParameters getSimulationParameters();
}
