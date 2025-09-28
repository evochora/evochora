package org.evochora.node;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import org.evochora.datapipeline.ServiceManager;
import org.evochora.node.config.ConfigLoader;
import org.evochora.node.config.LoggingConfigurator;
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
    private Thread shutdownHook;

    /**
     * Constructs the Node, initializing all core services and processes.
     *
     * @param config The fully resolved application configuration.
     */
    public Node(final Config config) {
        this.serviceRegistry = new ServiceRegistry();
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

    /**
     * The main entry point of the application.
     *
     * @param args Command line arguments (currently not used).
     */
    public static void main(final String[] args) {
        try {
            // Set logging format property BEFORE any logging operations
            // This must be done before any LoggerFactory calls
            final java.io.File configFile = new java.io.File(CONFIG_FILE_NAME);
            final Config config;
            if (configFile.exists()) {
                config = ConfigFactory.parseFile(configFile).withFallback(ConfigFactory.load());
            } else {
                config = ConfigFactory.load();
            }
            
            if (config.hasPath("logging.format")) {
                final String format = config.getString("logging.format");
                if ("PLAIN".equalsIgnoreCase(format)) {
                    System.setProperty("evochora.logging.format", "STDOUT_PLAIN");
                } else {
                    System.setProperty("evochora.logging.format", "STDOUT");
                }
            }
            
            // Force Logback to reload configuration after setting the property
            reconfigureLogback();
            
            // Configure logging levels from evochora.conf
            LoggingConfigurator.configure(config);
            
            // Show welcome message if configured (as first output after config evaluation)
            if (config.hasPath("node.show-welcome-message") && config.getBoolean("node.show-welcome-message")) {
                showWelcomeMessage();
            }
            
            // Log which configuration file is being used (immediately after welcome message)
            final java.io.File configFileForLog = new java.io.File(CONFIG_FILE_NAME);
            if (configFileForLog.exists()) {
                LOGGER.info("Using configuration file: {}", configFileForLog.getAbsolutePath());
            } else {
                LOGGER.info("Using default configuration from classpath (file not found: {})", configFileForLog.getAbsolutePath());
            }
            
            // Now create the Node - this will trigger Logback initialization
            final Node node = new Node(config);
            
            node.start();
        } catch (final Exception e) {
            // Use System.err for error logging since we can't rely on Logback yet
            System.err.println("A fatal error occurred during node startup: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void reconfigureLogback() {
        try {
            ch.qos.logback.classic.LoggerContext context = 
                (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
            
            ch.qos.logback.classic.joran.JoranConfigurator configurator = 
                new ch.qos.logback.classic.joran.JoranConfigurator();
            configurator.setContext(context);
            
            // Reset the context
            context.reset();
            
            // Load the logback configuration from classpath
            java.net.URL configUrl = Node.class.getClassLoader().getResource("logback.xml");
            if (configUrl != null) {
                configurator.doConfigure(configUrl);
            }
        } catch (Exception e) {
            System.err.println("Failed to reconfigure Logback: " + e.getMessage());
        }
    }
    
    /**
     * Displays the ASCII art welcome message for Evochora.
     */
    private static void showWelcomeMessage() {
        System.out.println();
        System.out.println("Welcome to...");
        System.out.println("  ________      ______   _____ _    _  ____  _____            ");
        System.out.println(" |  ____\\ \\    / / __ \\ / ____| |  | |/ __ \\|  __ \\     /\\    ");
        System.out.println(" | |__   \\ \\  / / |  | | |    | |__| | |  | | |__) |   /  \\   ");
        System.out.println(" |  __|   \\ \\/ /| |  | | |    |  __  | |  | |  _  /   / /\\ \\  ");
        System.out.println(" | |____   \\  / | |__| | |____| |  | | |__| | | \\ \\  / ____ \\ ");
        System.out.println(" |______|   \\/   \\____/ \\_____|_|  |_|\\____/|_|  \\_\\/_/    \\_\\");
        System.out.println();
        System.out.println("            Advanced Evolution Simulation Platform");
        System.out.println();
    }
}