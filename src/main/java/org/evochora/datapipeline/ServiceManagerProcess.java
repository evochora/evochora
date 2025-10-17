package org.evochora.datapipeline;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.node.processes.AbstractProcess;
import org.evochora.node.spi.IServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * A Node process that wraps {@link ServiceManager} and manages its lifecycle.
 * This process exposes the ServiceManager instance to other processes via dependency injection.
 *
 * <p>This wrapper ensures that ServiceManager is properly started and stopped as part of
 * the Node's process lifecycle, fixing the issue where services were not shut down gracefully.</p>
 *
 * <p>Configuration structure (placed under node.processes.pipeline):</p>
 * <pre>
 * pipeline {
 *   className = "org.evochora.datapipeline.ServiceManagerProcess"
 *   options {
 *     autoStart = true
 *     startupSequence = [...]
 *     resources { ... }
 *     services { ... }
 *   }
 * }
 * </pre>
 */
public class ServiceManagerProcess extends AbstractProcess implements IServiceProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceManagerProcess.class);

    private final ServiceManager serviceManager;

    /**
     * Constructs a new ServiceManagerProcess.
     *
     * @param processName The name of this process instance from the configuration.
     * @param dependencies Dependencies injected by the Node (currently none required).
     * @param options The pipeline configuration (resources, services, etc.).
     */
    public ServiceManagerProcess(final String processName, final Map<String, Object> dependencies, final Config options) {
        super(processName, dependencies, options);

        // Create a root config that wraps the options under "pipeline" key
        // This maintains compatibility with ServiceManager's existing constructor
        final Config rootConfig = ConfigFactory.empty()
            .withValue("pipeline", options.root());

        this.serviceManager = new ServiceManager(rootConfig);
        LOGGER.info("ServiceManagerProcess '{}' initialized.", processName);
    }

    @Override
    public void start() {
        LOGGER.debug("ServiceManagerProcess '{}' start() called. ServiceManager handles auto-start internally.", processName);
        // ServiceManager auto-starts services in its constructor if autoStart=true
        // No explicit action needed here
    }

    @Override
    public void stop() {
        LOGGER.info("Stopping ServiceManagerProcess '{}'...", processName);
        serviceManager.stopAll(); // Stops services and closes resources
        LOGGER.info("ServiceManagerProcess '{}' stopped.", processName);
    }

    @Override
    public Object getExposedService() {
        return serviceManager;
    }
}
