package org.evochora.datapipeline;

import java.lang.reflect.Constructor;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.evochora.compiler.api.CompilationException;
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IContextualResource;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.IResourceInitializer;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.api.services.IServiceFactory;
import org.evochora.datapipeline.api.services.ResourceBinding;
import org.evochora.datapipeline.api.services.ServiceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public class ServiceManager implements IMonitorable {

    private static final Logger log = LoggerFactory.getLogger(ServiceManager.class);

    private final Config pipelineConfig;
    private final Map<String, IServiceFactory> serviceFactories = new ConcurrentHashMap<>();
    // Contains all service instances (RUNNING, PAUSED, STOPPED, ERROR)
    // Services are removed only on explicit restart or during shutdown
    private final Map<String, IService> services = new ConcurrentHashMap<>();
    private final Map<String, IResource> resources = new ConcurrentHashMap<>();
    private final Map<String, List<ResourceBinding>> serviceResourceBindings = new ConcurrentHashMap<>();
    private final List<String> startupSequence;
    private final Map<String, List<PendingBinding>> pendingBindingsMap = new ConcurrentHashMap<>();
    // Stores the wrapped resources currently being created for a service (used to coordinate between factory and bindings)
    private final Map<String, Map<String, List<IResource>>> activeWrappedResources = new ConcurrentHashMap<>();

    public ServiceManager(Config rootConfig) {
        this.pipelineConfig = loadPipelineConfig(rootConfig);
        log.info("Initializing ServiceManager...");

        // Run resource initializers BEFORE loading any resource classes
        // This allows initializers to set system properties that drivers read at load time
        runResourceInitializers(this.pipelineConfig);

        instantiateResources(this.pipelineConfig);
        buildServiceFactories(this.pipelineConfig);

        if (pipelineConfig.hasPath("startupSequence")) {
            this.startupSequence = pipelineConfig.getStringList("startupSequence");
        } else {
            this.startupSequence = Collections.emptyList();
        }

        log.info("ServiceManager initialized with {} resources and {} service factories.", resources.size(), serviceFactories.size());

        // Auto-start services if configured
        boolean autoStart = pipelineConfig.hasPath("autoStart")
                ? pipelineConfig.getBoolean("autoStart")
                : true; // default to true for production readiness

        if (autoStart && !this.startupSequence.isEmpty()) {
            log.info("\u001B[34m========== Service Startup ==========\u001B[0m");
            startAllInternal();
            
            // Perform memory estimation AFTER services are instantiated
            // This allows iterating over actual service instances
            performMemoryEstimation(this.pipelineConfig);
        } else if (!autoStart) {
            log.info("Auto-start is disabled. Services must be started manually via API.");
        } else {
            log.info("No startup sequence defined. Services must be started manually via API.");
        }
    }

    private Config loadPipelineConfig(Config rootConfig) {
        if (!rootConfig.hasPath("pipeline")) {
            throw new IllegalArgumentException("Configuration must contain 'pipeline' section");
        }
        return rootConfig.getConfig("pipeline");
    }

    /**
     * Runs resource initializers before any resource classes are loaded.
     * <p>
     * Initializers are used for early system-level configuration that must happen
     * before certain drivers are loaded (e.g., H2's temp directory must be set
     * before the H2 driver is loaded because H2 caches the value at load time).
     * <p>
     * The method:
     * <ol>
     *   <li>Scans all resource definitions for {@code init} blocks</li>
     *   <li>Deduplicates by {@code init.className} (each initializer runs once)</li>
     *   <li>Instantiates and runs each initializer</li>
     * </ol>
     *
     * @param config The pipeline configuration
     */
    private void runResourceInitializers(Config config) {
        if (!config.hasPath("resources")) {
            return;
        }

        Config resourcesConfig = config.getConfig("resources");
        
        // Collect and deduplicate initializers by className
        Map<String, Config> initializersByClass = new LinkedHashMap<>();
        
        for (String resourceName : resourcesConfig.root().keySet()) {
            try {
                Config resourceDefinition = resourcesConfig.getConfig(resourceName);
                if (resourceDefinition.hasPath("init")) {
                    Config initConfig = resourceDefinition.getConfig("init");
                    if (initConfig.hasPath("className")) {
                        String className = initConfig.getString("className");
                        // First one wins (for consistent behavior)
                        if (!initializersByClass.containsKey(className)) {
                            Config options = initConfig.hasPath("options") 
                                ? initConfig.getConfig("options") 
                                : ConfigFactory.empty();
                            initializersByClass.put(className, options);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Skipping init block scan for resource '{}': {}", resourceName, e.getMessage());
            }
        }

        if (initializersByClass.isEmpty()) {
            return;
        }

        // Run each initializer once, collect successfully executed ones for summary log
        List<String> executedInitializers = new ArrayList<>();
        for (Map.Entry<String, Config> entry : initializersByClass.entrySet()) {
            String className = entry.getKey();
            Config options = entry.getValue();
            
            try {
                log.debug("Running resource initializer: {}", className);
                IResourceInitializer initializer = (IResourceInitializer) Class.forName(className)
                    .getDeclaredConstructor()
                    .newInstance();
                initializer.initialize(options);
                // Extract simple class name for readable log
                String simpleName = className.substring(className.lastIndexOf('.') + 1);
                executedInitializers.add(simpleName);
            } catch (ClassNotFoundException e) {
                log.error("Resource initializer class not found: {}", className);
            } catch (ClassCastException e) {
                log.error("Resource initializer '{}' does not implement IResourceInitializer", className);
            } catch (Exception e) {
                log.error("Failed to run resource initializer '{}': {}", className, e.getMessage());
            }
        }
        
        if (!executedInitializers.isEmpty()) {
            log.info("Resource initializers executed: {}", String.join(", ", executedInitializers));
        }
    }

    private void instantiateResources(Config config) {
        if (!config.hasPath("resources")) {
            log.debug("No resources configured.");
            return;
        }
        log.info("\u001B[34m========== Resource Initialization ==========\u001B[0m");
        Config resourcesConfig = config.getConfig("resources");
        for (String resourceName : resourcesConfig.root().keySet()) {
            try {
                Config resourceDefinition = resourcesConfig.getConfig(resourceName);
                String className = resourceDefinition.getString("className");
                Config options = resourceDefinition.hasPath("options")
                        ? resourceDefinition.getConfig("options")
                        : ConfigFactory.empty();

                IResource resource = (IResource) Class.forName(className)
                        .getConstructor(String.class, Config.class)
                        .newInstance(resourceName, options);
                resources.put(resourceName, resource);
                log.info("Instantiated resource '{}' of type {}", resourceName, className);
            } catch (Exception e) {
                // Extract root cause for clear error message (no stack trace)
                Throwable cause = e;
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                String errorMsg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                log.error("Failed to instantiate resource '{}': {}. Skipping this resource.", resourceName, errorMsg);
            }
        }
    }

    private void buildServiceFactories(Config config) {
        if (!config.hasPath("services")) {
            log.debug("No services configured.");
            return;
        }
        log.info("\u001B[34m========== Service Initialization ==========\u001B[0m");
        Config servicesConfig = config.getConfig("services");
        for (String serviceName : servicesConfig.root().keySet()) {
            try {
                Config serviceDefinition = servicesConfig.getConfig(serviceName);
                String className = serviceDefinition.getString("className");
                Config options = serviceDefinition.hasPath("options") ? serviceDefinition.getConfig("options") : ConfigFactory.empty();

                List<PendingBinding> pendingBindings = new ArrayList<>();
                if (serviceDefinition.hasPath("resources")) {
                    Config resourcesConfig = serviceDefinition.getConfig("resources");
                    for (Map.Entry<String, com.typesafe.config.ConfigValue> entry : resourcesConfig.root().entrySet()) {
                        String portName = entry.getKey();
                        String resourceUri = entry.getValue().unwrapped().toString();
                        ResourceContext context = parseResourceUri(resourceUri, serviceName, portName);
                        IResource baseResource = resources.get(context.resourceName());
                        if (baseResource == null) {
                            throw new IllegalArgumentException(String.format("Service '%s' references unknown resource '%s' for port '%s'", serviceName, context.resourceName(), portName));
                        }
                        pendingBindings.add(new PendingBinding(context, baseResource));
                    }
                }
                pendingBindingsMap.put(serviceName, pendingBindings);

                final String factoryServiceName = serviceName;  // Effectively final for lambda
                Constructor<?> constructor = Class.forName(className)
                        .getConstructor(String.class, Config.class, Map.class);

                IServiceFactory factory = () -> {
                    try {
                        // Use wrapped resources from activeWrappedResources (populated by startService)
                        Map<String, List<IResource>> injectableResources = activeWrappedResources.get(factoryServiceName);
                        if (injectableResources == null) {
                            throw new IllegalStateException("No wrapped resources prepared for service: " + factoryServiceName);
                        }
                        return (IService) constructor.newInstance(factoryServiceName, options, injectableResources);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to create an instance of service '" + factoryServiceName + "'", e);
                    }
                };
                serviceFactories.put(serviceName, factory);
                log.info("Built factory for service '{}' of type {}", serviceName, className);
            } catch (Exception e) {
                // Extract root cause for clear error message (no stack trace)
                Throwable cause = e;
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                String errorMsg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                log.error("Failed to build factory for service '{}': {}. Skipping this service.", serviceName, errorMsg);
            }
        }
    }

    private ResourceContext parseResourceUri(String uri, String serviceName, String portName) {
        String[] mainParts = uri.split(":", 2);

        String usageType;
        String resourceAndParamsStr;

        if (mainParts.length == 2) {
            // Format: "usageType:resourceName?params"
            usageType = mainParts[0];
            resourceAndParamsStr = mainParts[1];
        } else {
            // Format: "resourceName?params" (non-contextual resource)
            usageType = null;
            resourceAndParamsStr = uri;
        }

        String[] resourceAndParams = resourceAndParamsStr.split("\\?", 2);
        String resourceName = resourceAndParams[0];
        Map<String, String> params = new HashMap<>();
        if (resourceAndParams.length > 1) {
            Arrays.stream(resourceAndParams[1].split("&"))
                  .map(p -> p.split("=", 2))
                  .filter(p -> p.length == 2)
                  .forEach(p -> params.put(p[0], p[1]));
        }
        return new ResourceContext(serviceName, portName, usageType, resourceName, Collections.unmodifiableMap(params));
    }

    private record PendingBinding(ResourceContext context, IResource baseResource) {}

    private void applyToAllServices(Consumer<String> action, List<String> serviceNames) {
        for (String serviceName : serviceNames) {
            try {
                action.accept(serviceName);
            } catch (IllegalStateException | IllegalArgumentException e) {
                log.warn("Could not perform action on service '{}': {}", serviceName, e.getMessage());
            }
        }
    }

    public void startAll() {
        log.info("\u001B[34m========== Starting Services ==========\u001B[0m");
        startAllInternal();
    }
    
    private void startAllInternal() {
        List<String> toStart = new ArrayList<>(startupSequence);
        //serviceFactories.keySet().stream().filter(s -> !toStart.contains(s)).forEach(toStart::add);
        applyToAllServices(this::startService, toStart);
    }

    public void stopAll() {
        log.info("\u001B[34m========== Stopping Services ==========\u001B[0m");
        List<String> toStop = new ArrayList<>(startupSequence);
        Collections.reverse(toStop);
        services.keySet().stream().filter(s -> !toStop.contains(s)).forEach(toStop::add);
        // Filter to only stop services that are in a stoppable state (RUNNING or PAUSED)
        // This excludes one-shot services that have already stopped themselves
        List<String> actuallyStoppable = toStop.stream()
                .filter(name -> {
                    IService service = services.get(name);
                    if (service == null) return false;
                    IService.State state = service.getCurrentState();
                    return state == IService.State.RUNNING || state == IService.State.PAUSED;
                })
                .collect(Collectors.toList());
        applyToAllServices(this::stopService, actuallyStoppable);
        
        // NOTE: Resources are NOT closed here to allow restart via HTTP API.
        // Resources are only closed during JVM shutdown via shutdown() method.
    }
    
    /**
     * Performs a complete shutdown: stops all services and closes all resources.
     * <p>
     * This method should only be called during JVM shutdown (via shutdown hook in Node),
     * NOT during normal stop/start cycling via HTTP API.
     * <p>
     * Once resources are closed, they cannot be reopened, making restart impossible.
     */
    public void shutdown() {
        stopAll();
        closeAllResources();
    }
    
    /**
     * Closes all resources to ensure clean shutdown.
     * <p>
     * This method is called after all services have been stopped to ensure that
     * resources (especially databases) are properly closed and data is flushed.
     * With DB_CLOSE_ON_EXIT=FALSE, H2 will not close automatically, so we must
     * explicitly close all resources here.
     * <p>
     * Resources that implement {@link AutoCloseable} (H2Database, H2TopicResource)
     * will close their own wrappers before shutting down connection pools.
     * Other resources (e.g., in-memory queues) do not require explicit shutdown.
     */
    private void closeAllResources() {
        log.info("\u001B[34m========== Closing Resource ==========\u001B[0m");
        
        for (Map.Entry<String, IResource> entry : resources.entrySet()) {
            String resourceName = entry.getKey();
            IResource resource = entry.getValue();
            
            if (resource instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) resource).close();
                    log.info("Closed resource: {}", resourceName);
                } catch (Exception e) {
                    log.error("Failed to close resource '{}': {}", resourceName, e.getMessage());
                }
            } else {
                log.debug("Resource '{}' does not implement AutoCloseable, skipping", resourceName);
            }
        }
    }

    public void pauseAll() {
        log.info("Pausing all services...");
        applyToAllServices(this::pauseService, new ArrayList<>(services.keySet()));
    }

    public void resumeAll() {
        log.info("Resuming all services...");
        applyToAllServices(this::resumeService, new ArrayList<>(services.keySet()));
    }

    public void restartAll() {
        log.info("Restarting all services...");
        stopAll();
        startAll();
    }

    /**
     * Registers a custom factory for a service, overriding the config-based factory.
     * <p>
     * This is useful for resume mode where a service needs to be created with
     * pre-existing state rather than fresh from configuration.
     * <p>
     * <strong>Important:</strong> This method must be called BEFORE {@link #startAll()}
     * or {@link #startService(String)} for the custom factory to take effect.
     *
     * @param serviceName The name of the service to override
     * @param factory The custom factory that will create the service instance
     * @throws IllegalArgumentException if the serviceName is not defined in config
     */
    public void registerCustomFactory(String serviceName, IServiceFactory factory) {
        if (!serviceFactories.containsKey(serviceName)) {
            throw new IllegalArgumentException(
                "Cannot register custom factory for unknown service: " + serviceName +
                ". The service must be defined in the pipeline configuration.");
        }
        serviceFactories.put(serviceName, factory);
        log.info("Registered custom factory for service '{}'", serviceName);
    }

    public void startService(String name) {
        // VALIDATION: Check if the service already exists and its state
        IService existing = services.get(name);
        if (existing != null) {
            IService.State state = existing.getCurrentState();
            // Services that have finished (STOPPED/ERROR) can be restarted
            if (state == IService.State.STOPPED || state == IService.State.ERROR) {
                log.debug("Removing previous instance of service '{}' (state: {}) before creating new instance", name, state);
                services.remove(name);
                serviceResourceBindings.remove(name);
            } else {
                // Service is still RUNNING or PAUSED
                throw new IllegalStateException("Service '" + name + "' is already running (state: " + state + "). Use restartService() for an explicit restart.");
            }
        }

        IServiceFactory factory = serviceFactories.get(name);
        if (factory == null) {
            throw new IllegalArgumentException("Service '" + name + "' is not defined.");
        }

        try {
            log.debug("Creating a new instance for service '{}'.", name);

            // Step 1: Create wrapped resources ONCE and store them for both injection and bindings
            List<PendingBinding> pendingBindings = pendingBindingsMap.getOrDefault(name, Collections.emptyList());
            Map<String, List<IResource>> wrappedResourcesMap = new HashMap<>();
            Map<ResourceContext, IResource> contextToWrappedResource = new HashMap<>();

            for (PendingBinding pb : pendingBindings) {
                IResource wrappedResource = (pb.baseResource() instanceof IContextualResource)
                        ? ((IContextualResource) pb.baseResource()).getWrappedResource(pb.context())
                        : pb.baseResource();
                wrappedResourcesMap.computeIfAbsent(pb.context().portName(), k -> new ArrayList<>()).add(wrappedResource);
                contextToWrappedResource.put(pb.context(), wrappedResource);
            }

            // Step 2: Store wrapped resources for factory to use
            activeWrappedResources.put(name, wrappedResourcesMap);

            try {
                // Step 3: Create service instance (factory will use wrapped resources from activeWrappedResources)
                IService newServiceInstance = factory.create();

                // Step 4: Create ResourceBindings using the SAME wrapped resource instances
                List<ResourceBinding> finalBindings = pendingBindings.stream()
                        .map(pb -> new ResourceBinding(pb.context(), newServiceInstance, contextToWrappedResource.get(pb.context())))
                        .collect(Collectors.toList());
                serviceResourceBindings.put(name, Collections.unmodifiableList(finalBindings));

                services.put(name, newServiceInstance);

                newServiceInstance.start();
            } finally {
                // Step 5: Clean up temporary map
                activeWrappedResources.remove(name);
            }
        } catch (OutOfMemoryError e) {
            // Clean up maps in case of a startup failure.
            services.remove(name);
            serviceResourceBindings.remove(name);
            activeWrappedResources.remove(name);

            // Provide friendly, actionable error message with memory calculation
            String errorMsg = "Failed to start service '" + name + "': Insufficient memory.";

            // Try to calculate memory requirements for simulation-engine
            if (name.equals("simulation-engine") && pipelineConfig.hasPath("services.simulation-engine.options.environment.shape")) {
                try {
                    List<Integer> shape = pipelineConfig.getIntList("services.simulation-engine.options.environment.shape");
                    long totalCells = shape.stream().mapToLong(Integer::longValue).reduce(1L, (a, b) -> a * b);
                    long estimatedMemoryGB = ((totalCells * 8) / (1024 * 1024 * 1024)) + 4; // 8 bytes per cell + 4GB overhead
                    errorMsg += " World size " + shape + " requires ~" + estimatedMemoryGB + " GB. Increase heap with -Xmx" + estimatedMemoryGB + "g or reduce world size.";
                } catch (Exception ex) {
                    errorMsg += " Increase heap size with -Xmx16g or reduce world size in configuration.";
                }
            } else {
                errorMsg += " Increase heap size with -Xmx16g";
            }

            log.error(errorMsg);
            // Don't throw - just log and return. Service remains in stopped state.
            return;
        } catch (RuntimeException e) {
            // Clean up maps in case of a startup failure.
            services.remove(name);
            serviceResourceBindings.remove(name);
            activeWrappedResources.remove(name);

            // Unwrap reflection exceptions to find root cause
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }

            // Check if this is an OutOfMemoryError wrapped in RuntimeException (from reflection)
            if (cause instanceof OutOfMemoryError && name.equals("simulation-engine") && pipelineConfig.hasPath("services.simulation-engine.options.environment.shape")) {
                try {
                    List<Integer> shape = pipelineConfig.getIntList("services.simulation-engine.options.environment.shape");
                    long totalCells = shape.stream().mapToLong(Integer::longValue).reduce(1L, (a, b) -> a * b);
                    long estimatedMemoryGB = ((totalCells * 8) / (1024 * 1024 * 1024)) + 4; // 8 bytes per cell + 4GB overhead
                    String errorMsg = "Failed to start service '" + name + "': Insufficient memory. World size " + shape +
                        " requires ~" + estimatedMemoryGB + " GB. Increase heap with -Xmx" + estimatedMemoryGB + "g or reduce world size.";
                    log.error(errorMsg);
                    return;
                } catch (Exception ex) {
                    log.error("Failed to start service '{}': Insufficient memory. Increase heap size with -Xmx16g or reduce world size.", name);
                    return;
                }
            }

            // Check if this is a compilation error (CompilationException contains formatted error messages)
            if (cause instanceof CompilationException) {
                log.error("Compilation failed for service '{}':\n{}", name, cause.getMessage());
                // Don't throw - just log and return. Service remains in stopped state.
                return;
            }

            // Check if this is a configuration error (IllegalArgumentException or NegativeArraySizeException)
            if (cause instanceof IllegalArgumentException) {
                String errorMsg = "Configuration error for service '" + name + "': " + cause.getMessage();
                log.error(errorMsg);
                // Don't throw - just log and return. Service remains in stopped state.
                return;
            }

            if (cause instanceof NegativeArraySizeException && name.equals("simulation-engine") && pipelineConfig.hasPath("services.simulation-engine.options.environment.shape")) {
                try {
                    List<Integer> shape = pipelineConfig.getIntList("services.simulation-engine.options.environment.shape");
                    long totalCells = shape.stream().mapToLong(Integer::longValue).reduce(1L, (a, b) -> a * b);
                    String errorMsg = "Configuration error for service '" + name + "': World size " + shape +
                        " is too large (" + String.format("%,d", totalCells) + " cells). Java arrays are limited to " +
                        String.format("%,d", Integer.MAX_VALUE) + " elements. Reduce world dimensions.";
                    log.error(errorMsg);
                    return;
                } catch (Exception ex) {
                    log.error("Configuration error for service '{}': World dimensions cause integer overflow. Reduce world size.", name);
                    return;
                }
            }

            // Re-throw other runtime exceptions
            log.error("Failed to create and start a new instance for service '{}'.", name, e);
            throw e;
        } catch (Exception e) {
            log.error("Failed to create and start a new instance for service '{}'.", name, e);
            // Clean up maps in case of a startup failure.
            services.remove(name);
            serviceResourceBindings.remove(name);
            activeWrappedResources.remove(name);
        }
    }

    public void stopService(String name) {
        IService service = services.get(name);
        if (service == null) {
            log.warn("Attempted to stop service '{}', but it was not found among services.", name);
            return;
        }
        
        log.info("Stopping service '{}'...", name);
        service.stop();  // Already blocks until thread terminates (max 5 sec)
        
        // Check final state and log
        IService.State finalState = service.getCurrentState();
        if (finalState == IService.State.STOPPED) {
            log.debug("Service '{}' stopped successfully.", name);
        } else if (finalState == IService.State.ERROR) {
            log.warn("Service '{}' stopped with ERROR state.", name);
        } else {
            log.error("Service '{}' in unexpected state after stop(): {}", name, finalState);
        }
        
        // NOTE: Service remains in 'services' map with State STOPPED for monitoring.
        // It will be removed on next startService() or during shutdown().
    }

    public void pauseService(String serviceName) {
        getServiceOrFail(serviceName).pause();
    }

    public void resumeService(String serviceName) {
        getServiceOrFail(serviceName).resume();
    }

    public void restartService(String serviceName) {
        log.info("Restarting service '{}'...", serviceName);
        stopService(serviceName);
        startService(serviceName);
    }

    private IService getServiceOrFail(String serviceName) {
        IService service = services.get(serviceName);
        if (service == null) {
            throw new IllegalArgumentException("Service not found: " + serviceName);
        }
        return service;
    }

    public Collection<IService> getAllServices() {
        return Collections.unmodifiableCollection(services.values());
    }

    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new HashMap<>();
        Map<IService.State, Long> serviceStates = services.values().stream()
                .collect(Collectors.groupingBy(IService::getCurrentState, Collectors.counting()));

        long stoppedCount = serviceFactories.size() - services.size();

        metrics.put("services_total", (long) serviceFactories.size());
        metrics.put("services_running", serviceStates.getOrDefault(IService.State.RUNNING, 0L));
        metrics.put("services_paused", serviceStates.getOrDefault(IService.State.PAUSED, 0L));
        metrics.put("services_stopped", serviceStates.getOrDefault(IService.State.STOPPED, 0L) + stoppedCount);
        metrics.put("services_error", serviceStates.getOrDefault(IService.State.ERROR, 0L));

        Map<IResource.UsageState, Long> resourceStates = serviceResourceBindings.values().stream()
                .flatMap(List::stream)
                .map(binding -> binding.resource().getUsageState(binding.context().usageType()))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        metrics.put("resources_total", resources.size());
        metrics.put("resources_active", resourceStates.getOrDefault(IResource.UsageState.ACTIVE, 0L));
        metrics.put("resources_waiting", resourceStates.getOrDefault(IResource.UsageState.WAITING, 0L));
        metrics.put("resources_failed", resourceStates.getOrDefault(IResource.UsageState.FAILED, 0L));

        return Collections.unmodifiableMap(metrics);
    }

    @Override
    public List<OperationalError> getErrors() {
        return services.values().stream()
                .filter(s -> s instanceof IMonitorable)
                .flatMap(s -> ((IMonitorable) s).getErrors().stream())
                .collect(Collectors.toList());
    }

    @Override
    public void clearErrors() {
        services.values().stream()
                .filter(s -> s instanceof IMonitorable)
                .forEach(s -> ((IMonitorable) s).clearErrors());
        log.info("Cleared errors for all monitorable services.");
    }

    @Override
    public boolean isHealthy() {
        boolean servicesOk = services.values().stream().noneMatch(s -> s.getCurrentState() == IService.State.ERROR);
        boolean resourcesOk = serviceResourceBindings.values().stream()
                .flatMap(List::stream)
                .noneMatch(b -> b.resource().getUsageState(b.context().usageType()) == IResource.UsageState.FAILED);
        return servicesOk && resourcesOk;
    }

    public Map<String, List<OperationalError>> getServiceErrors() {
        return services.entrySet().stream()
                .filter(e -> e.getValue() instanceof IMonitorable)
                .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), ((IMonitorable) e.getValue()).getErrors()))
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public List<OperationalError> getServiceErrors(String serviceName) {
        IService service = getServiceOrFail(serviceName);
        if (service instanceof IMonitorable) {
            return ((IMonitorable) service).getErrors();
        }
        return Collections.emptyList();
    }

    public Map<String, ServiceStatus> getAllServiceStatus() {
        return serviceFactories.keySet().stream()
                .collect(Collectors.toMap(Function.identity(), this::getServiceStatus, (v1, v2) -> v1, LinkedHashMap::new));
    }

    public Map<String, IResource> getAllResourceStatus() {
        return Collections.unmodifiableMap(resources);
    }

    /**
     * Gets a resource with type-safe access.
     * <p>
     * Allows cross-process resource access (e.g., HttpServerProcess accessing
     * database resources created for pipeline services).
     * 
     * @param name Resource name from configuration
     * @param expectedType Expected type of the resource
     * @param <T> Resource type
     * @return The resource instance, cast to expected type
     * @throws IllegalArgumentException if resource not found or wrong type
     */
    public <T> T getResource(String name, Class<T> expectedType) {
        IResource resource = resources.get(name);

        if (resource == null) {
            throw new IllegalArgumentException(
                "Resource '" + name + "' not found. Available resources: " + resources.keySet()
            );
        }

        if (!expectedType.isInstance(resource)) {
            throw new IllegalArgumentException(
                "Resource '" + name + "' is " + resource.getClass().getName() +
                " but expected " + expectedType.getName()
            );
        }

        return expectedType.cast(resource);
    }

    public ServiceStatus getServiceStatus(String serviceName) {
        if (!serviceFactories.containsKey(serviceName)) {
            throw new IllegalArgumentException("Service not found: " + serviceName);
        }

        IService service = services.get(serviceName);
        if (service == null) {
            return new ServiceStatus(IService.State.STOPPED, true, Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());
        }

        List<ResourceBinding> resourceBindings = serviceResourceBindings.getOrDefault(serviceName, Collections.emptyList());
        Map<String, Number> serviceMetrics = (service instanceof IMonitorable) ? ((IMonitorable) service).getMetrics() : Collections.emptyMap();
        List<OperationalError> errors = (service instanceof IMonitorable) ? ((IMonitorable) service).getErrors() : Collections.emptyList();
        boolean healthy = (service instanceof IMonitorable) ? ((IMonitorable) service).isHealthy() : (service.getCurrentState() != IService.State.ERROR);

        return new ServiceStatus(
                service.getCurrentState(),
                healthy,
                serviceMetrics,
                errors,
                resourceBindings
        );
    }
    
    // ==================== Memory Estimation ====================
    
    /**
     * Performs worst-case memory estimation for all configured components.
     * <p>
     * Collects estimates from all resources AND services that implement
     * {@link IMemoryEstimatable} and compares the total against available heap.
     * <p>
     * <strong>IMPORTANT:</strong> This method must be called AFTER services are
     * instantiated (after startAllInternal) to access service instances.
     * <p>
     * <strong>Output:</strong>
     * <ul>
     *   <li>INFO log if estimated memory fits within -Xmx</li>
     *   <li>WARN log if estimated memory exceeds -Xmx</li>
     *   <li>DEBUG log with per-component breakdown</li>
     * </ul>
     * <p>
     * <strong>Worst-Case Assumptions:</strong>
     * <ul>
     *   <li>100% environment occupancy (all cells filled)</li>
     *   <li>Maximum configured organisms alive simultaneously</li>
     *   <li>All queues and buffers at full capacity</li>
     * </ul>
     *
     * @param config Pipeline configuration containing simulation parameters
     */
    private void performMemoryEstimation(Config config) {
        // Extract simulation parameters from config
        SimulationParameters params = extractSimulationParameters(config);
        if (params == null) {
            log.debug("Memory estimation skipped: simulation-engine not configured");
            return;
        }
        
        log.info("\u001B[34m========== Memory Estimation (WORST-CASE: 100%% occupancy) ==========\u001B[0m");
        
        // Collect estimates from all IMemoryEstimatable components
        List<MemoryEstimate> allEstimates = new ArrayList<>();
        Map<MemoryEstimate.Category, Long> categoryTotals = new EnumMap<>(MemoryEstimate.Category.class);
        
        // 1. Resources (queues, trackers, databases)
        for (Map.Entry<String, IResource> entry : resources.entrySet()) {
            if (entry.getValue() instanceof IMemoryEstimatable) {
                List<MemoryEstimate> estimates = ((IMemoryEstimatable) entry.getValue()).estimateWorstCaseMemory(params);
                allEstimates.addAll(estimates);
                for (MemoryEstimate estimate : estimates) {
                    categoryTotals.merge(estimate.category(), estimate.estimatedBytes(), Long::sum);
                }
            }
        }
        
        // 2. Services (PersistenceService, Indexers, SimulationEngine, etc.)
        for (Map.Entry<String, IService> entry : services.entrySet()) {
            if (entry.getValue() instanceof IMemoryEstimatable) {
                List<MemoryEstimate> estimates = ((IMemoryEstimatable) entry.getValue()).estimateWorstCaseMemory(params);
                allEstimates.addAll(estimates);
                for (MemoryEstimate estimate : estimates) {
                    categoryTotals.merge(estimate.category(), estimate.estimatedBytes(), Long::sum);
                }
            }
        }
        
        // 3. Add JVM baseline overhead (thread stacks, class metadata, GC overhead)
        long jvmOverhead = 512 * 1024 * 1024; // ~512 MB baseline
        allEstimates.add(new MemoryEstimate(
            "JVM-overhead",
            jvmOverhead,
            "Thread stacks, class metadata, GC overhead, native memory",
            MemoryEstimate.Category.JVM_OVERHEAD
        ));
        categoryTotals.merge(MemoryEstimate.Category.JVM_OVERHEAD, jvmOverhead, Long::sum);
        
        // Calculate total from services and resources
        long serviceEstimatedBytes = allEstimates.stream().mapToLong(MemoryEstimate::estimatedBytes).sum();
        
        // Add fixed overhead for Node processes (HttpServerProcess, H2ConsoleProcess, H2TcpServerProcess)
        // These are not part of ServiceManager but consume significant heap:
        // - HttpServerProcess: Jetty thread pool (200 threads × ~1MB) + request buffers = ~250 MB
        // - H2ConsoleProcess: Small embedded HTTP server = ~20 MB
        // - H2TcpServerProcess: TCP server for DB connections = ~30 MB
        long nodeProcessOverhead = 300L * 1024 * 1024; // 300 MB for Node processes
        
        long totalEstimatedBytes = serviceEstimatedBytes + nodeProcessOverhead;
        long maxHeapBytes = Runtime.getRuntime().maxMemory();
        
        // Log detailed breakdown at DEBUG level
        log.debug("Memory estimation breakdown (WORST-CASE: 100%% environment, {} max organisms):", params.maxOrganisms());
        for (MemoryEstimate estimate : allEstimates) {
            log.debug("  {} → {}: {}", estimate.componentName(), estimate.formattedBytes(), estimate.explanation());
        }
        log.debug("  Node processes (fixed overhead) → {}", SimulationParameters.formatBytes(nodeProcessOverhead));
        
        // Log category totals
        log.debug("Category totals:");
        for (Map.Entry<MemoryEstimate.Category, Long> entry : categoryTotals.entrySet()) {
            log.debug("  {}: {}", entry.getKey().getDisplayName(), SimulationParameters.formatBytes(entry.getValue()));
        }
        log.debug("  Node processes: {}", SimulationParameters.formatBytes(nodeProcessOverhead));
        
        // Calculate recommended heap with 30% safety margin
        long recommendedHeapBytes = (long) (totalEstimatedBytes * 1.3);
        
        // Log summary with INFO or WARN depending on heap availability
        if (totalEstimatedBytes <= maxHeapBytes) {
            log.info("Memory estimate: {} (peak: {} services + {} Node overhead), available heap: {} (-Xmx) ✓",
                SimulationParameters.formatBytes(totalEstimatedBytes),
                services.size(),
                SimulationParameters.formatBytes(nodeProcessOverhead),
                SimulationParameters.formatBytes(maxHeapBytes));
            log.info("  Note: Services started later via API are NOT included in this estimate.");
        } else {
            log.warn("⚠️ MEMORY WARNING: Estimated peak {} exceeds available heap {}",
                SimulationParameters.formatBytes(totalEstimatedBytes),
                SimulationParameters.formatBytes(maxHeapBytes));
            log.warn("  Based on {} services + {} Node overhead (services started via API NOT included)",
                services.size(), SimulationParameters.formatBytes(nodeProcessOverhead));
            
            // Detailed breakdown at WARN level
            log.warn("  Memory breakdown by component:");
            for (MemoryEstimate estimate : allEstimates) {
                log.warn("    {} → {}: {}", estimate.componentName(), estimate.formattedBytes(), estimate.explanation());
            }
            log.warn("    Node processes (fixed) → {}: HttpServer, H2Console, H2TcpServer",
                SimulationParameters.formatBytes(nodeProcessOverhead));
            
            log.warn("  RECOMMENDATION: -Xmx{} (estimate + 30%% safety margin)",
                formatHeapRecommendation(recommendedHeapBytes));
            log.warn("  To reduce memory requirements:");
            log.warn("    - Decrease tick-queue capacity (currently configured in resources)");
            log.warn("    - Decrease persistence maxBatchSize (currently in services)");
            log.warn("    - Decrease indexer insertBatchSize (currently in services)");
        }
    }
    
    /**
     * Extracts simulation parameters from pipeline configuration.
     * <p>
     * Searches all configured services for a SimulationEngine class and extracts
     * environment shape from its configuration. This approach works regardless of
     * the service name used in the configuration.
     * <p>
     * Estimates maxOrganisms based on environment size (since there's no explicit config for it).
     *
     * @param config Pipeline configuration
     * @return SimulationParameters or null if no SimulationEngine service is configured
     */
    private SimulationParameters extractSimulationParameters(Config config) {
        if (!config.hasPath("services")) {
            return null;
        }
        
        try {
            Config servicesConfig = config.getConfig("services");
            
            // Iterate over all configured services to find SimulationEngine
            for (String serviceName : servicesConfig.root().keySet()) {
                Config serviceConfig = servicesConfig.getConfig(serviceName);
                
                if (!serviceConfig.hasPath("className")) {
                    continue;
                }
                
                String className = serviceConfig.getString("className");
                
                // Check if this is a SimulationEngine (by class name suffix)
                if (className.endsWith(".SimulationEngine") && 
                    serviceConfig.hasPath("options.environment.shape")) {
                    
                    // Extract environment shape
                    List<Integer> shapeList = serviceConfig.getIntList("options.environment.shape");
                    int[] shape = shapeList.stream().mapToInt(Integer::intValue).toArray();
                    
                    // Calculate total cells (use long to avoid overflow for large worlds)
                    long totalCells = 1L;
                    for (int dim : shape) {
                        totalCells *= dim;
                    }
                    
                    // Read maxOrganisms from config (default: 5000)
                    int maxOrganisms = serviceConfig.hasPath("options.maxOrganisms")
                        ? serviceConfig.getInt("options.maxOrganisms")
                        : 5000; // Default: 5000 organisms (memory-optimized)
                    
                    // Read delta compression parameters
                    int samplingInterval = serviceConfig.hasPath("options.samplingInterval")
                        ? serviceConfig.getInt("options.samplingInterval")
                        : SimulationParameters.DEFAULT_SAMPLING_INTERVAL;
                    int accumulatedDeltaInterval = serviceConfig.hasPath("options.accumulatedDeltaInterval")
                        ? serviceConfig.getInt("options.accumulatedDeltaInterval")
                        : SimulationParameters.DEFAULT_ACCUMULATED_DELTA_INTERVAL;
                    int snapshotInterval = serviceConfig.hasPath("options.snapshotInterval")
                        ? serviceConfig.getInt("options.snapshotInterval")
                        : SimulationParameters.DEFAULT_SNAPSHOT_INTERVAL;
                    int chunkInterval = serviceConfig.hasPath("options.chunkInterval")
                        ? serviceConfig.getInt("options.chunkInterval")
                        : SimulationParameters.DEFAULT_CHUNK_INTERVAL;
                    double estimatedDeltaRatio = serviceConfig.hasPath("options.estimatedDeltaRatio")
                        ? serviceConfig.getDouble("options.estimatedDeltaRatio")
                        : SimulationParameters.DEFAULT_ESTIMATED_DELTA_RATIO;
                    
                    log.debug("Found SimulationEngine '{}' with environment shape {}, maxOrganisms={}, ticksPerChunk={}", 
                        serviceName, Arrays.toString(shape), maxOrganisms,
                        samplingInterval * accumulatedDeltaInterval * snapshotInterval * chunkInterval);
                    return new SimulationParameters(
                        shape, totalCells, maxOrganisms,
                        samplingInterval, accumulatedDeltaInterval,
                        snapshotInterval, chunkInterval, estimatedDeltaRatio
                    );
                }
            }
            
            log.debug("No SimulationEngine service found in configuration");
            return null;
        } catch (Exception e) {
            log.debug("Could not extract simulation parameters: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Formats a byte count as a human-readable heap recommendation (e.g., "8g" or "2048m").
     *
     * @param bytes The recommended heap size in bytes
     * @return Formatted string suitable for -Xmx parameter
     */
    private String formatHeapRecommendation(long bytes) {
        long gb = bytes / (1024 * 1024 * 1024);
        if (gb >= 1) {
            // Round up to next GB
            return (gb + 1) + "g";
        } else {
            long mb = bytes / (1024 * 1024);
            // Round up to next 256 MB
            return ((mb / 256 + 1) * 256) + "m";
        }
    }
}