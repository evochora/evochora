package org.evochora.node;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;
import org.evochora.node.spi.IProcess;
import org.evochora.node.spi.IServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.*;

/**
 * The main entry point for the Evochora application node. This class initializes the system,
 * loads and starts all configured background processes, and manages their lifecycle.
 *
 * <p>Processes can declare dependencies on other processes via the configuration. The Node
 * resolves these dependencies using topological sorting and injects them via constructors.</p>
 */
public final class Node {
    private static final Logger LOGGER = LoggerFactory.getLogger(Node.class);
    private static final String NODE_CONFIG_PATH = "node";
    private static final String PROCESSES_CONFIG_PATH = "processes";

    private final Map<String, IProcess> managedProcesses = new LinkedHashMap<>();
    private Thread shutdownHook;

    /**
     * Constructs the Node, initializing all processes with dependency injection.
     *
     * @param config The fully resolved application configuration.
     */
    public Node(final Config config) {
        try {
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
            LOGGER.info("\u001B[34m========== Management Interfaces ==========\u001B[0m");
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

        // Stop processes in reverse order (LIFO - last created, first stopped)
        LOGGER.info("\u001B[34m========== Management Interface Shutdown ==========\u001B[0m");
        final List<String> processNames = new ArrayList<>(managedProcesses.keySet());
        Collections.reverse(processNames);

        for (final String name : processNames) {
            try {
                LOGGER.debug("Stopping process '{}'...", name);
                managedProcesses.get(name).stop();
                LOGGER.debug("Process '{}' stopped successfully.", name);
            } catch (final Exception e) {
                LOGGER.error("Error while stopping process '{}'.", name, e);
            }
        }
        LOGGER.info("All processes stopped. Goodbye.");
    }

    private void initializeProcesses(final Config config) {
        if (!config.hasPath(NODE_CONFIG_PATH) || !config.hasPath(NODE_CONFIG_PATH + "." + PROCESSES_CONFIG_PATH)) {
            LOGGER.warn("Configuration path '{}.{}' not found. No processes will be loaded.", NODE_CONFIG_PATH, PROCESSES_CONFIG_PATH);
            return;
        }

        final ConfigObject processesConfig = config.getObject(NODE_CONFIG_PATH + "." + PROCESSES_CONFIG_PATH);
        LOGGER.info("Initializing processes...");
        LOGGER.debug("Found {} configured process(es): {}", processesConfig.keySet().size(), processesConfig.keySet());

        // Step 1: Parse all process definitions
        final Map<String, ProcessDefinition> processDefs = new LinkedHashMap<>();
        for (final String processName : processesConfig.keySet()) {
            try {
                final Config processConfig = processesConfig.toConfig().getConfig(processName);
                final String className = processConfig.getString("className");
                final Config options = processConfig.hasPath("options")
                    ? processConfig.getConfig("options")
                    : com.typesafe.config.ConfigFactory.empty();

                final Map<String, String> requires = new HashMap<>();
                if (processConfig.hasPath("require")) {
                    final ConfigObject requireConfig = processConfig.getObject("require");
                    for (final String localName : requireConfig.keySet()) {
                        requires.put(localName, requireConfig.toConfig().getString(localName));
                    }
                }

                processDefs.put(processName, new ProcessDefinition(processName, className, options, requires));
                LOGGER.debug("Parsed process definition: '{}' (class: {}, dependencies: {})",
                    processName, className, requires.isEmpty() ? "none" : requires.values());

            } catch (final Exception e) {
                LOGGER.error("Failed to parse process '{}'. Skipping this process.", processName, e);
            }
        }

        // Step 2: Perform topological sort to determine instantiation order
        final List<String> orderedProcessNames;
        try {
            orderedProcessNames = topologicalSort(processDefs);
            LOGGER.debug("Process instantiation order: {}", orderedProcessNames);
        } catch (final IllegalStateException e) {
            LOGGER.error("Failed to resolve process dependencies: {}", e.getMessage());
            throw e;
        }

        // Step 3: Instantiate processes in dependency order
        final Map<String, Object> exposedServices = new HashMap<>();

        for (final String processName : orderedProcessNames) {
            final ProcessDefinition def = processDefs.get(processName);
            if (def == null) {
                continue; // Shouldn't happen, but be defensive
            }

            try {
                // Resolve dependencies for this process
                final Map<String, Object> injectedDeps = new HashMap<>();
                for (final Map.Entry<String, String> reqEntry : def.requires.entrySet()) {
                    final String localName = reqEntry.getKey();
                    final String sourceProcess = reqEntry.getValue();
                    final Object service = exposedServices.get(sourceProcess);

                    if (service == null) {
                        throw new IllegalStateException(
                            "Process '" + processName + "' requires service from '" + sourceProcess +
                            "' but it is not available. Check configuration order.");
                    }

                    injectedDeps.put(localName, service);
                    LOGGER.debug("Injecting dependency '{}' from process '{}' into '{}'",
                        localName, sourceProcess, processName);
                }

                // Instantiate the process
                LOGGER.debug("Instantiating process '{}' with class '{}'", processName, def.className);
                final Class<?> processClass = Class.forName(def.className);

                if (!IProcess.class.isAssignableFrom(processClass)) {
                    throw new IllegalArgumentException("Class " + def.className + " does not implement IProcess.");
                }

                final Constructor<?> constructor = processClass.getConstructor(
                    String.class, Map.class, Config.class);
                final IProcess processInstance = (IProcess) constructor.newInstance(
                    processName, injectedDeps, def.options);

                managedProcesses.put(processName, processInstance);

                // Collect exposed service if the process implements IServiceProvider
                if (processInstance instanceof IServiceProvider) {
                    final Object exposedService = ((IServiceProvider) processInstance).getExposedService();
                    if (exposedService != null) {
                        exposedServices.put(processName, exposedService);
                        LOGGER.debug("Process '{}' exposes service: {}", processName, exposedService.getClass().getSimpleName());
                    }
                }

                LOGGER.debug("Successfully instantiated process '{}'.", processName);

            } catch (final Exception e) {
                LOGGER.error("Failed to initialize process '{}'. Skipping this process.", processName, e);
            }
        }

        LOGGER.info("Initialized {} process(es) successfully.", managedProcesses.size());
    }

    /**
     * Performs topological sort on process definitions to determine dependency order.
     * Uses Kahn's algorithm to detect cycles and resolve dependencies.
     *
     * @param processDefs The process definitions with their dependencies
     * @return List of process names in dependency order (dependencies first)
     * @throws IllegalStateException if a circular dependency is detected
     */
    private List<String> topologicalSort(final Map<String, ProcessDefinition> processDefs) {
        // Build adjacency list and in-degree map
        final Map<String, Set<String>> dependents = new HashMap<>(); // Who depends on this process
        final Map<String, Integer> inDegree = new HashMap<>();

        // Initialize
        for (final String processName : processDefs.keySet()) {
            dependents.putIfAbsent(processName, new HashSet<>());
            inDegree.putIfAbsent(processName, 0);
        }

        // Build graph
        for (final ProcessDefinition def : processDefs.values()) {
            for (final String requiredProcess : def.requires.values()) {
                if (!processDefs.containsKey(requiredProcess)) {
                    throw new IllegalStateException(
                        "Process '" + def.name + "' depends on '" + requiredProcess +
                        "' which is not defined in the configuration.");
                }
                dependents.get(requiredProcess).add(def.name);
                inDegree.put(def.name, inDegree.get(def.name) + 1);
            }
        }

        // Kahn's algorithm
        final Queue<String> queue = new LinkedList<>();
        final List<String> result = new ArrayList<>();

        // Start with processes that have no dependencies
        for (final Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        while (!queue.isEmpty()) {
            final String current = queue.poll();
            result.add(current);

            // Reduce in-degree for all dependents
            for (final String dependent : dependents.get(current)) {
                final int newDegree = inDegree.get(dependent) - 1;
                inDegree.put(dependent, newDegree);
                if (newDegree == 0) {
                    queue.add(dependent);
                }
            }
        }

        // Check for cycles
        if (result.size() != processDefs.size()) {
            final List<String> remaining = new ArrayList<>(processDefs.keySet());
            remaining.removeAll(result);
            throw new IllegalStateException(
                "Circular dependency detected among processes: " + remaining +
                ". Check the 'require' configuration.");
        }

        return result;
    }

    /**
     * Internal representation of a process definition from configuration.
     */
    private static class ProcessDefinition {
        final String name;
        final String className;
        final Config options;
        final Map<String, String> requires; // local name -> source process name

        ProcessDefinition(final String name, final String className, final Config options,
                         final Map<String, String> requires) {
            this.name = name;
            this.className = className;
            this.options = options;
            this.requires = requires;
        }
    }
}