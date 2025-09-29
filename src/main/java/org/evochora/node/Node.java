package org.evochora.node;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;
import org.evochora.datapipeline.ServiceManager;
import org.evochora.node.spi.IProcess;
import org.evochora.node.spi.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

/**
 * The main entry point for the Evochora application node. This class initializes the system,
 * loads and starts all configured background processes, and manages their lifecycle.
 */
public final class Node {
    private static final Logger LOGGER = LoggerFactory.getLogger(Node.class);
    private static final String NODE_CONFIG_PATH = "node";
    private static final String PROCESSES_CONFIG_PATH = "processes";

    private final ServiceRegistry serviceRegistry;
    private final Map<String, IProcess> managedProcesses = new HashMap<>();
    private Thread shutdownHook;

    /**
     * Constructs the Node, initializing all core services and processes.
     *
     * @param config The fully resolved application configuration.
     */
    public Node(final Config config) {
        this.serviceRegistry = new ServiceRegistry();
        // Register this Node instance so controllers or other components can access it.
        this.serviceRegistry.register(Node.class, this);
        try {
            initializeCoreServices(config);
            initializeProcesses(config);
        } catch (final Exception e) {
            LOGGER.error("Failed to initialize the node.", e);
            throw new IllegalStateException("Node initialization failed", e);
        }
    }

    /**
     * Test-friendly constructor that accepts a ServiceManager instance.
     * This allows tests to inject a mocked ServiceManager for better testability.
     *
     * @param config The fully resolved application configuration.
     * @param serviceManager The ServiceManager instance to use (can be mocked in tests).
     */
    Node(final Config config, final ServiceManager serviceManager) {
        this.serviceRegistry = new ServiceRegistry();
        try {
            initializeCoreServicesWithServiceManager(config, serviceManager);
            initializeProcesses(config);
        } catch (final Exception e) {
            LOGGER.error("Failed to initialize the node.", e);
            throw new IllegalStateException("Node initialization failed", e);
        }
    }

    /**
     * Starts all managed processes and registers a shutdown hook for graceful termination.
     */
    public void start() {
        if (managedProcesses.isEmpty()) {
            LOGGER.warn("No processes configured to start. The node will be idle.");
        } else {
            managedProcesses.forEach((name, process) -> {
                try {
                    LOGGER.debug("Starting process '{}'...", name);
                    process.start();
                    LOGGER.debug("Process '{}' started successfully.", name);
                } catch (final Exception e) {
                    LOGGER.error("Failed to start process '{}'. The node may be unstable.", name, e);
                }
            });
        }

        shutdownHook = new Thread(this::stop, "shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        LOGGER.info("Node started successfully. Running until interrupted.");
    }

    /**
     * Stops all managed processes gracefully. This method is typically called via the shutdown hook.
     */
    public void stop() {
        LOGGER.info("Shutdown sequence initiated...");
        
        // Remove shutdown hook to prevent double execution
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (final IllegalStateException e) {
                // Shutdown hook is already running or JVM is shutting down
                LOGGER.debug("Could not remove shutdown hook: {}", e.getMessage());
            }
        }
        
        managedProcesses.forEach((name, process) -> {
            try {
                LOGGER.debug("Stopping process '{}'...", name);
                process.stop();
                LOGGER.debug("Process '{}' stopped successfully.", name);
            } catch (final Exception e) {
                LOGGER.error("Error while stopping process '{}'.", name, e);
            }
        });
        LOGGER.info("All processes stopped. Goodbye.");
    }

    private void initializeCoreServices(final Config config) {
        LOGGER.info("Initializing core services...");
        // Create and register the ServiceManager instance
        final ServiceManager serviceManager = new ServiceManager(config);
        serviceRegistry.register(ServiceManager.class, serviceManager);
        LOGGER.info("Core services initialized and registered.");
    }

    private void initializeCoreServicesWithServiceManager(final Config config, final ServiceManager serviceManager) {
        LOGGER.info("Initializing core services...");
        // Register the provided ServiceManager instance
        serviceRegistry.register(ServiceManager.class, serviceManager);
        LOGGER.info("Core services initialized and registered.");
    }

    private void initializeProcesses(final Config config) {
        if (!config.hasPath(NODE_CONFIG_PATH) || !config.hasPath(NODE_CONFIG_PATH + "." + PROCESSES_CONFIG_PATH)) {
            LOGGER.warn("Configuration path '{}.{}' not found. No processes will be loaded.", NODE_CONFIG_PATH, PROCESSES_CONFIG_PATH);
            return;
        }

        final ConfigObject processesConfig = config.getObject(NODE_CONFIG_PATH + "." + PROCESSES_CONFIG_PATH);
        LOGGER.debug("Loading configured processes: {}", processesConfig.keySet());

        for (final String processName : processesConfig.keySet()) {
            try {
                final Config processOptions = processesConfig.toConfig().getConfig(processName);
                final String className = processOptions.getString("className");
                final Config options = processOptions.hasPath("options") ? processOptions.getConfig("options") : null;

                LOGGER.debug("Instantiating process '{}' with class '{}'", processName, className);

                final Class<?> processClass = Class.forName(className);
                if (!IProcess.class.isAssignableFrom(processClass)) {
                    throw new IllegalArgumentException("Class " + className + " does not implement IProcess.");
                }

                final Constructor<?> constructor = processClass.getConstructor(ServiceRegistry.class, Config.class);
                final IProcess processInstance = (IProcess) constructor.newInstance(serviceRegistry, options);

                managedProcesses.put(processName, processInstance);
                LOGGER.debug("Successfully instantiated process '{}'.", processName);

            } catch (final Exception e) {
                LOGGER.error("Failed to initialize process '{}'. Skipping this process.", processName, e);
                // Continue with the next process instead of failing the entire Node initialization
            }
        }
    }

}