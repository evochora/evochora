package org.evochora.node;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;
import org.evochora.datapipeline.ServiceManager;
import org.evochora.node.config.ConfigLoader;
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
    private static final String CONFIG_FILE_NAME = "evochora.conf";
    private static final String NODE_CONFIG_PATH = "node";
    private static final String PROCESSES_CONFIG_PATH = "processes";

    private final ServiceRegistry serviceRegistry;
    private final Map<String, IProcess> managedProcesses = new HashMap<>();

    /**
     * Constructs the Node, initializing all core services and processes.
     *
     * @param config The fully resolved application configuration.
     */
    public Node(final Config config) {
        // Create real ServiceManager and delegate to test-friendly constructor
        this(config, new ServiceManager(config));
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
            initializeCoreServices(config, serviceManager);
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
                    LOGGER.info("Starting process '{}'...", name);
                    process.start();
                    LOGGER.info("Process '{}' started successfully.", name);
                } catch (final Exception e) {
                    LOGGER.error("Failed to start process '{}'. The node may be unstable.", name, e);
                }
            });
        }

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
        LOGGER.info("Node started successfully. Running until interrupted.");
    }

    /**
     * Stops all managed processes gracefully. This method is typically called via the shutdown hook.
     */
    public void stop() {
        LOGGER.info("Shutdown sequence initiated...");
        managedProcesses.forEach((name, process) -> {
            try {
                LOGGER.info("Stopping process '{}'...", name);
                process.stop();
                LOGGER.info("Process '{}' stopped successfully.", name);
            } catch (final Exception e) {
                LOGGER.error("Error while stopping process '{}'.", name, e);
            }
        });
        LOGGER.info("All processes stopped. Goodbye.");
    }

    private void initializeCoreServices(final Config config, final ServiceManager serviceManager) {
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
        LOGGER.info("Loading configured processes: {}", processesConfig.keySet());

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
                LOGGER.info("Successfully instantiated process '{}'.", processName);

            } catch (final Exception e) {
                LOGGER.error("Failed to initialize process '{}'. Skipping this process.", processName, e);
                // Continue with the next process instead of failing the entire Node initialization
            }
        }
    }

    /**
     * The main entry point of the application.
     *
     * @param args Command line arguments (currently not used).
     */
    public static void main(final String[] args) {
        try {
            final Config config = ConfigLoader.load(CONFIG_FILE_NAME);
            final Node node = new Node(config);
            node.start();
        } catch (final Exception e) {
            LOGGER.error("A fatal error occurred during node startup. The application will exit.", e);
            System.exit(1);
        }
    }
}