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
    private final Map<String, IService> runningServices = new ConcurrentHashMap<>();
    private final Map<String, IResource> resources = new ConcurrentHashMap<>();
    private final Map<String, List<ResourceBinding>> serviceResourceBindings = new ConcurrentHashMap<>();
    private final List<String> startupSequence;
    private final Map<String, List<PendingBinding>> pendingBindingsMap = new ConcurrentHashMap<>();


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
            log.info("Auto-starting services according to startup sequence...");
            startAll();
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
        Config servicesConfig = config.getConfig("services");
        for (String serviceName : servicesConfig.root().keySet()) {
            try {
                Config serviceDefinition = servicesConfig.getConfig(serviceName);
                String className = serviceDefinition.getString("className");
                Config options = serviceDefinition.hasPath("options") ? serviceDefinition.getConfig("options") : ConfigFactory.empty();

                Map<String, List<IResource>> injectableResources = new HashMap<>();
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
                        IResource resourceToInject = (baseResource instanceof IContextualResource)
                                ? ((IContextualResource) baseResource).getWrappedResource(context)
                                : baseResource;
                        injectableResources.computeIfAbsent(portName, k -> new ArrayList<>()).add(resourceToInject);
                        pendingBindings.add(new PendingBinding(context, baseResource));
                    }
                }
                pendingBindingsMap.put(serviceName, pendingBindings);

                Constructor<?> constructor = Class.forName(className)
                        .getConstructor(String.class, Config.class, Map.class);

                IServiceFactory factory = () -> {
                    try {
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
        if (mainParts.length != 2) throw new IllegalArgumentException("Invalid resource URI: " + uri);
        String usageType = mainParts[0];
        String[] resourceAndParams = mainParts[1].split("\\?", 2);
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
        log.info("Starting all services...");
        List<String> toStart = new ArrayList<>(startupSequence);
        serviceFactories.keySet().stream().filter(s -> !toStart.contains(s)).forEach(toStart::add);
        applyToAllServices(this::startService, toStart);
    }

    public void stopAll() {
        log.info("Stopping all services...");
        List<String> toStop = new ArrayList<>(startupSequence);
        Collections.reverse(toStop);
        runningServices.keySet().stream().filter(s -> !toStop.contains(s)).forEach(toStop::add);
        applyToAllServices(this::stopService, toStop);
    }

    public void pauseAll() {
        log.info("Pausing all services...");
        applyToAllServices(this::pauseService, new ArrayList<>(runningServices.keySet()));
    }

    public void resumeAll() {
        log.info("Resuming all services...");
        applyToAllServices(this::resumeService, new ArrayList<>(runningServices.keySet()));
    }

    public void restartAll() {
        log.info("Restarting all services...");
        stopAll();
        startAll();
    }

    public void startService(String name) {
        // VALIDATION: Check if the service is already running.
        if (runningServices.containsKey(name)) {
            // Throw an exception to enforce explicit commands for destructive actions.
            throw new IllegalStateException("Service '" + name + "' is already running. Use restartService() for an explicit restart.");
        }

        IServiceFactory factory = serviceFactories.get(name);
        if (factory == null) {
            throw new IllegalArgumentException("Service '" + name + "' is not defined.");
        }

        try {
            log.info("Creating a new instance for service '{}'.", name);
            IService newServiceInstance = factory.create();

            List<PendingBinding> pendingBindings = pendingBindingsMap.getOrDefault(name, Collections.emptyList());
            List<ResourceBinding> finalBindings = pendingBindings.stream()
                    .map(pb -> new ResourceBinding(pb.context(), newServiceInstance, pb.baseResource()))
                    .collect(Collectors.toList());
            serviceResourceBindings.put(name, Collections.unmodifiableList(finalBindings));

            runningServices.put(name, newServiceInstance);
            newServiceInstance.start();
            log.info("Service '{}' started successfully with a new instance.", name);
        } catch (Exception e) {
            log.error("Failed to create and start a new instance for service '{}'.", name, e);
            // Clean up maps in case of a startup failure.
            runningServices.remove(name);
            serviceResourceBindings.remove(name);
        }
    }

    public void stopService(String name) {
        IService service = runningServices.remove(name);
        if (service != null) {
            serviceResourceBindings.remove(name);
            stopAndAwait(service, name);
        } else {
            log.warn("Attempted to stop service '{}', but it was not found among running services.", name);
        }
    }

    public void pauseService(String serviceName) {
        getRunningServiceOrFail(serviceName).pause();
    }

    public void resumeService(String serviceName) {
        getRunningServiceOrFail(serviceName).resume();
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
                    log.info("Service '{}' has stopped.", serviceName);
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

    private IService getRunningServiceOrFail(String serviceName) {
        IService service = runningServices.get(serviceName);
        if (service == null) {
            throw new IllegalArgumentException("Service not found or not running: " + serviceName);
        }
        return service;
    }

    public Collection<IService> getAllServices() {
        return Collections.unmodifiableCollection(runningServices.values());
    }

    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new HashMap<>();
        Map<IService.State, Long> serviceStates = runningServices.values().stream()
                .collect(Collectors.groupingBy(IService::getCurrentState, Collectors.counting()));

        long stoppedCount = serviceFactories.size() - runningServices.size();

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
        return runningServices.values().stream()
                .filter(s -> s instanceof IMonitorable)
                .flatMap(s -> ((IMonitorable) s).getErrors().stream())
                .collect(Collectors.toList());
    }

    @Override
    public void clearErrors() {
        runningServices.values().stream()
                .filter(s -> s instanceof IMonitorable)
                .forEach(s -> ((IMonitorable) s).clearErrors());
        log.info("Cleared errors for all monitorable services.");
    }

    @Override
    public boolean isHealthy() {
        boolean servicesOk = runningServices.values().stream().noneMatch(s -> s.getCurrentState() == IService.State.ERROR);
        boolean resourcesOk = serviceResourceBindings.values().stream()
                .flatMap(List::stream)
                .noneMatch(b -> b.resource().getUsageState(b.context().usageType()) == IResource.UsageState.FAILED);
        return servicesOk && resourcesOk;
    }

    public Map<String, List<OperationalError>> getServiceErrors() {
        return runningServices.entrySet().stream()
                .filter(e -> e.getValue() instanceof IMonitorable)
                .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), ((IMonitorable) e.getValue()).getErrors()))
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public List<OperationalError> getServiceErrors(String serviceName) {
        IService service = getRunningServiceOrFail(serviceName);
        if (service instanceof IMonitorable) {
            return ((IMonitorable) service).getErrors();
        }
        return Collections.emptyList();
    }

    public Map<String, ServiceStatus> getAllServiceStatus() {
        return serviceFactories.keySet().stream()
                .collect(Collectors.toMap(Function.identity(), this::getServiceStatus, (v1, v2) -> v1, LinkedHashMap::new));
    }

    public ServiceStatus getServiceStatus(String serviceName) {
        if (!serviceFactories.containsKey(serviceName)) {
            throw new IllegalArgumentException("Service not found: " + serviceName);
        }

        IService service = runningServices.get(serviceName);
        if (service == null) {
            return new ServiceStatus(IService.State.STOPPED, Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());
        }

        List<ResourceBinding> resourceBindings = serviceResourceBindings.getOrDefault(serviceName, Collections.emptyList());
        Map<String, Number> serviceMetrics = (service instanceof IMonitorable) ? ((IMonitorable) service).getMetrics() : Collections.emptyMap();
        List<OperationalError> errors = (service instanceof IMonitorable) ? ((IMonitorable) service).getErrors() : Collections.emptyList();

        return new ServiceStatus(
                service.getCurrentState(),
                serviceMetrics,
                errors,
                resourceBindings
        );
    }
}