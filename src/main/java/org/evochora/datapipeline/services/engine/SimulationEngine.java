package org.evochora.datapipeline.services.engine;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.services.State;
import org.evochora.datapipeline.services.BaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A placeholder for the new SimulationEngine.
 * <p>
 * This class is a temporary implementation that allows the new data pipeline CLI
 * to be tested and used while the real SimulationEngine is being migrated to the
 * new package structure.
 * </p>
 */
public class SimulationEngine extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    /**
     * Constructs a new SimulationEngine.
     * @param config The HOCON configuration for this service.
     */
    public SimulationEngine(Config config) {
        // The config is passed by the ServiceManager, but the BaseService does not have
        // a constructor for it. In a real implementation, this config would be used
        // to configure the simulation.
    }

    @Override
    protected void run() {
        log.info("Placeholder SimulationEngine is running.");
        // The real implementation will have the main simulation loop here.
        // For the placeholder, we can just let it run until stopped.
        while (currentState.get() == State.RUNNING) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("Placeholder SimulationEngine has stopped.");
    }
}
