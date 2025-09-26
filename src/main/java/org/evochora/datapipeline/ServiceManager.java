package org.evochora.datapipeline;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.*;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.api.services.ResourceBinding;
import org.evochora.datapipeline.api.services.ServiceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ServiceManager implements IMonitorable {

    private static final Logger log = LoggerFactory.getLogger(ServiceManager.class);

    private final Config pipelineConfig;
    private final Map<String, IService> services = new ConcurrentHashMap<>();
    private final Map<String, IResource> resources = new ConcurrentHashMap<>();
    private final Map<String, List<ResourceBinding>> serviceResourceBindings = new ConcurrentHashMap<>();
    private final List<String> startupSequence;

    public ServiceManager(Config rootConfig) {
        this.pipelineConfig = loadPipelineConfig(rootConfig);
        log.info("Initializing ServiceManager...");

        instantiateResources(this.pipelineConfig);
        instantiateServices(this.pipelineConfig);

        if (pipelineConfig.hasPath("startupSequence")) {
            this.startupSequence = pipelineConfig.getStringList("startupSequence");
        } else {
            this.startupSequence = Collections.emptyList();
        }

        log.info("ServiceManager initialized with {} resources and {} services.", resources.size(), services.size());
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
                log.error("Failed to instantiate resource: {}", resourceName, e);
                throw new RuntimeException("Failed to instantiate resource: " + resourceName, e);
            }
        }
    }

    private void instantiateServices(Config config) {
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

                IService service = (IService) Class.forName(className)
                        .getConstructor(String.class, Config.class, Map.class)
                        .newInstance(serviceName, options, injectableResources);
                services.put(serviceName, service);

                List<ResourceBinding> finalBindings = pendingBindings.stream()
                        .map(pb -> new ResourceBinding(pb.context(), service, pb.baseResource()))
                        .collect(Collectors.toList());
                serviceResourceBindings.put(serviceName, Collections.unmodifiableList(finalBindings));

                log.info("Instantiated service '{}' of type {}", serviceName, className);
            } catch (Exception e) {
                log.error("Failed to instantiate service: {}", serviceName, e);
                throw new RuntimeException("Failed to instantiate service: " + serviceName, e);
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

    public void startAll() {
        log.info("Starting all services...");
        if (startupSequence.isEmpty()) {
            services.keySet().forEach(this::startService);
        } else {
            startupSequence.forEach(this::startService);
            services.keySet().stream()
                    .filter(serviceName -> !startupSequence.contains(serviceName))
                    .forEach(this::startService);
        }
    }

    public void stopAll() {
        log.info("Stopping all services...");
        List<String> shutdownSequence = new ArrayList<>(
            startupSequence.isEmpty() ? new ArrayList<>(services.keySet()) : startupSequence
        );
        Collections.reverse(shutdownSequence);
        shutdownSequence.forEach(this::stopService);
        services.keySet().stream()
                .filter(serviceName -> !shutdownSequence.contains(serviceName))
                .forEach(this::stopService);
    }

    public void pauseAll() {
        log.info("Pausing all services...");
        services.keySet().forEach(this::pauseService);
    }

    public void resumeAll() {
        log.info("Resuming all services...");
        services.keySet().forEach(this::resumeService);
    }

    public void restartAll() {
        log.info("Restarting all services...");
        stopAll();
        startAll();
    }

    public void startService(String serviceName) {
        getServiceOrFail(serviceName).start();
    }

    public void stopService(String serviceName) {
        getServiceOrFail(serviceName).stop();
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

    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new HashMap<>();
        Map<IService.State, Long> serviceStates = services.values().stream()
                .collect(Collectors.groupingBy(IService::getCurrentState, Collectors.counting()));

        metrics.put("services_total", services.size());
        metrics.put("services_running", serviceStates.getOrDefault(IService.State.RUNNING, 0L));
        metrics.put("services_paused", serviceStates.getOrDefault(IService.State.PAUSED, 0L));
        metrics.put("services_stopped", serviceStates.getOrDefault(IService.State.STOPPED, 0L));
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
        return services.keySet().stream()
                .collect(Collectors.toMap(Function.identity(), this::getServiceStatus, (v1, v2) -> v1, LinkedHashMap::new));
    }

    public ServiceStatus getServiceStatus(String serviceName) {
        IService service = getServiceOrFail(serviceName);
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