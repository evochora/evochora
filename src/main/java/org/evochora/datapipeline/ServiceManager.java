package org.evochora.datapipeline;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.*;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.api.services.IServiceFactory;
import org.evochora.datapipeline.api.services.ResourceBinding;
import org.evochora.datapipeline.api.services.ServiceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

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
            log.info("\u001B[34m════════════════════════════════ Service Startup ════════════════════════════════════════\u001B[0m");
            startAllInternal();
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

    private void instantiateResources(Config config) {
        if (!config.hasPath("resources")) {
            log.debug("No resources configured.");
            return;
        }
        log.info("\u001B[34m══════════════════════════════ Resource Initialization ══════════════════════════════════\u001B[0m");
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
                log.error("Failed to instantiate resource '{}': {}. Skipping this resource.", resourceName, e.getMessage(), e);
            }
        }
    }

    private void buildServiceFactories(Config config) {
        if (!config.hasPath("services")) {
            log.debug("No services configured.");
            return;
        }
        log.info("\u001B[34m═══════════════════════════════ Service Initialization ══════════════════════════════════\u001B[0m");
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

                Constructor<?> constructor = Class.forName(className)
                        .getConstructor(String.class, Config.class, Map.class);

                IServiceFactory factory = () -> {
                    try {
                        // Use wrapped resources from activeWrappedResources (populated by startService)
                        Map<String, List<IResource>> injectableResources = activeWrappedResources.get(serviceName);
                        if (injectableResources == null) {
                            throw new IllegalStateException("No wrapped resources prepared for service: " + serviceName);
                        }
                        return (IService) constructor.newInstance(serviceName, options, injectableResources);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to create an instance of service '" + serviceName + "'", e);
                    }
                };
                serviceFactories.put(serviceName, factory);

                log.info("Built factory for service '{}' of type {}", serviceName, className);
            } catch (Exception e) {
                log.error("Failed to build factory for service '{}': {}. Skipping this service.", serviceName, e.getMessage(), e);
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
        log.info("═══════════════════════════════ Starting Services ═══════════════════════════════════════");
        startAllInternal();
    }
    
    private void startAllInternal() {
        List<String> toStart = new ArrayList<>(startupSequence);
        //serviceFactories.keySet().stream().filter(s -> !toStart.contains(s)).forEach(toStart::add);
        applyToAllServices(this::startService, toStart);
    }

    public void stopAll() {
        log.info("\u001B[34m═════════════════════════════════ Stopping Service ══════════════════════════════════════\u001B[0m");
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
        log.info("\u001B[34m════════════════════════════════ Closing Resource ══════════════════════════════════════\u001B[0m");
        
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
        if (service != null) {
            stopAndAwait(service, name);
            // NOTE: Service remains in 'services' map with State STOPPED for monitoring.
            // It will be removed on next startService() or during shutdown().
        } else {
            log.warn("Attempted to stop service '{}', but it was not found among services.", name);
        }
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

    private void stopAndAwait(IService service, String serviceName) {
        log.info("Stopping service '{}'...", serviceName);
        service.stop();

        try {
            // Wait for up to 5 seconds for the service to stop.
            for (int i = 0; i < 100; i++) {
                if (service.getCurrentState() == IService.State.STOPPED) {
                    log.debug("Service '{}' has stopped.", serviceName);
                    return; // Exit successfully
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for service '{}' to stop.", serviceName);
        }

        // If the loop finishes without the service stopping, log a warning.
        if (service.getCurrentState() != IService.State.STOPPED) {
            log.warn("Service '{}' did not stop within the allocated time.", serviceName);
        }
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
}