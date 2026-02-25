package org.evochora.datapipeline.api.services;

import org.evochora.datapipeline.api.memory.SimulationParameters;

/**
 * Interface for the service that owns the current simulation run.
 * <p>
 * Implemented by the simulation engine to expose runtime identity and configuration
 * to the {@link org.evochora.datapipeline.ServiceManager}. This allows the service
 * manager to provide the active run ID for pipeline status queries and to obtain
 * simulation parameters for memory estimation — without coupling to a concrete class.
 * <p>
 * In resume mode, both the run ID and the memory estimation parameters reflect the
 * original run's configuration loaded from checkpoint metadata, not the current
 * configuration file.
 *
 * @see SimulationParameters
 * @see org.evochora.datapipeline.api.memory.IMemoryEstimatable
 */
public interface ISimulationSource {

    /**
     * Returns the unique identifier of the simulation run owned by this service.
     *
     * @return the run ID, never null
     */
    String getRunId();

    /**
     * Returns the simulation parameters used for memory estimation.
     * <p>
     * For fresh runs, these match the current configuration. For resumed runs,
     * these reflect the original run's configuration loaded from checkpoint metadata.
     *
     * @return the definitive simulation parameters for memory estimation
     */
    SimulationParameters getMemoryEstimationParameters();
}
