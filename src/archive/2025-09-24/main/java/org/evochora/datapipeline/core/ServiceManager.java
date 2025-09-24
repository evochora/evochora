package org.evochora.datapipeline.core;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueType;
import org.evochora.datapipeline.api.channels.IInputChannel;
import org.evochora.datapipeline.api.channels.IOutputChannel;
import org.evochora.datapipeline.api.services.ChannelMetrics;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.api.services.ServiceStatus;
import org.evochora.datapipeline.api.services.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The central orchestrator for the data pipeline. It is responsible for building the
 * entire pipeline from a configuration object and managing the lifecycle of all services.
 */
public class ServiceManager {

    private static final Logger log = LoggerFactory.getLogger(ServiceManager.class);
    private final Map<String, Object> resources = new HashMap<>();
    private final Map<String, IService> services = new HashMap<>();
    private final Map<String, Thread> serviceThreads = new HashMap<>();
    private final List<AbstractChannelBinding<?>> channelBindings = new ArrayList<>();
    private final Map<String, ChannelMetrics> channelMetrics = new ConcurrentHashMap<>();
    private ScheduledExecutorService metricsExecutor;
    private volatile boolean stopped = false;
    private volatile boolean servicesStarted = false;
    private final boolean autoStart;
    private final List<String> startupSequence;
    private final boolean enableMetrics;
    private final int updateIntervalSeconds;

    /**
     * Constructs a ServiceManager for a pipeline defined by the given configuration.
     *
     * @param rootConfig The root configuration object for the pipeline.
     */
    public ServiceManager(Config rootConfig) {
        log.info("Initializing ServiceManager...");
        applyLoggingConfiguration(rootConfig);
        
        Config pipelineConfig = rootConfig.getConfig("pipeline");
        this.autoStart = pipelineConfig.hasPath("autoStart") && pipelineConfig.getBoolean("autoStart");
        this.startupSequence = pipelineConfig.hasPath("startupSequence")
                ? pipelineConfig.getStringList("startupSequence")
                : new ArrayList<>();
        
        Config metricsConfig = pipelineConfig.hasPath("metrics") ? pipelineConfig.getConfig("metrics") : ConfigFactory.empty();
        this.enableMetrics = metricsConfig.hasPath("enableMetrics") && metricsConfig.getBoolean("enableMetrics");
        this.updateIntervalSeconds = metricsConfig.hasPath("updateIntervalSeconds") ? metricsConfig.getInt("updateIntervalSeconds") : 1;

        buildPipeline(pipelineConfig);
        
        log.debug("ServiceManager initialized with {} resources and {} services (autoStart: {}, startupSequence: {}, enableMetrics: {})",
                resources.size(), services.size(), autoStart, startupSequence, enableMetrics);
        
        if (autoStart) {
            log.info("Auto-starting services in sequence: {}", startupSequence);
            startAll();
        }
    }

    private void buildPipeline(Config pipelineConfig) {
        // 1. Instantiate all resources
        if (pipelineConfig.hasPath("resources")) {
            Config resourcesConfig = pipelineConfig.getConfig("resources");
            for (String resourceName : resourcesConfig.root().keySet()) {
                try {
                    Config resourceDefinition = resourcesConfig.getConfig(resourceName);
                    String className = resourceDefinition.getString("className");
                    Config options = resourceDefinition.hasPath("options") ? resourceDefinition.getConfig("options") : ConfigFactory.empty();

                    Object resource;
                    try {
                        Constructor<?> constructor = Class.forName(className).getConstructor(Config.class);
                        resource = constructor.newInstance(options);
                    } catch (NoSuchMethodException e) {
                        log.debug("No constructor with Config found for resource '{}', trying no-arg constructor.", resourceName);
                        Constructor<?> constructor = Class.forName(className).getConstructor();
                        resource = constructor.newInstance();
                    }
                    resources.put(resourceName, resource);
                    log.debug("Instantiated resource '{}' of type {}", resourceName, className);
                } catch (Exception e) {
                    log.error("Failed to instantiate resource '{}': {}", resourceName, e.getMessage(), e);
                }
            }
        }
        log.info("Instantiated {} resources.", resources.size());

        // 2. Instantiate all services
        if (pipelineConfig.hasPath("services")) {
            Config servicesConfig = pipelineConfig.getConfig("services");
            for (String serviceName : servicesConfig.root().keySet()) {
                try {
                    Config serviceDefinition = servicesConfig.getConfig(serviceName);
                    String className = serviceDefinition.getString("className");
                    Config options = serviceDefinition.hasPath("options") ? serviceDefinition.getConfig("options") : ConfigFactory.empty();

                    // Resolve dependencies for this service
                    Map<String, List<Object>> serviceResources = new HashMap<>();
                    if (serviceDefinition.hasPath("resources")) {
                        Config serviceResourcesConfig = serviceDefinition.getConfig("resources");
                        for (String portName : serviceResourcesConfig.root().keySet()) {
                            List<String> resourceNames = getResourceNames(serviceResourcesConfig, portName);
                            List<Object> resolvedResources = new ArrayList<>();
                            for (String resourceName : resourceNames) {
                                Object resource = resources.get(resourceName);
                                if (resource != null) {
                                    resolvedResources.add(resource);
                                } else {
                                    log.error("Service '{}' references unknown resource '{}' for port '{}'", serviceName, resourceName, portName);
                                }
                            }
                            serviceResources.put(portName, resolvedResources);
                        }
                    }

                    // Instantiate service
                    Constructor<?> constructor = Class.forName(className).getConstructor(Config.class, Map.class);
                    IService service = (IService) constructor.newInstance(options, serviceResources);
                    services.put(serviceName, service);
                    log.debug("Instantiated service '{}' of type {}", serviceName, className);

                    // Create metric bindings for channels
                    serviceResources.forEach((portName, resolved) -> {
                        resolved.forEach(resource -> {
                            if (resource instanceof IInputChannel) {
                                InputChannelBinding<?> binding = new InputChannelBinding<>(serviceName, portName, resource.toString(), (IInputChannel<?>) resource);
                                channelBindings.add(binding);
                            } else if (resource instanceof IOutputChannel) {
                                OutputChannelBinding<?> binding = new OutputChannelBinding<>(serviceName, portName, resource.toString(), (IOutputChannel<?>) resource);
                                channelBindings.add(binding);
                            }
                        });
                    });

                } catch (Exception e) {
                    log.error("Failed to instantiate service '{}': {}", serviceName, e.getMessage(), e);
                }
            }
        }
        log.info("Instantiated {} services.", services.size());
        
        // 3. Start metrics collection
        startMetricsCollection(pipelineConfig);
    }

    private List<String> getResourceNames(Config config, String key) {
        if (config.getValue(key).valueType() == ConfigValueType.STRING) {
            return Collections.singletonList(config.getString(key));
        } else {
            return config.getStringList(key);
        }
    }

    private static void applyLoggingConfiguration(Config config) {
        if (!config.hasPath("logging")) {
            return;
        }
        Config loggingConfig = config.getConfig("logging");

        try {
            Object loggerContext = LoggerFactory.getILoggerFactory();
            Class<?> levelClass = Class.forName("ch.qos.logback.classic.Level");
            Class<?> loggerClass = Class.forName("ch.qos.logback.classic.Logger");

            String defaultLevel = loggingConfig.hasPath("default-level") 
                    ? loggingConfig.getString("default-level") 
                    : "INFO";
            Object level = levelClass.getMethod("toLevel", String.class).invoke(null, defaultLevel.toUpperCase());
            Object rootLogger = loggerContext.getClass().getMethod("getLogger", String.class).invoke(loggerContext, Logger.ROOT_LOGGER_NAME);
            loggerClass.getMethod("setLevel", levelClass).invoke(rootLogger, level);
            log.debug("Applied default log level from config: {}", defaultLevel);

            if (loggingConfig.hasPath("levels")) {
                Config levelsConfig = loggingConfig.getConfig("levels");
                for (Map.Entry<String, Object> entry : levelsConfig.root().unwrapped().entrySet()) {
                    String loggerName = entry.getKey();
                    String logLevel = entry.getValue().toString();
                    Object loggerLevel = levelClass.getMethod("toLevel", String.class).invoke(null, logLevel.toUpperCase());
                    Object logger = loggerContext.getClass().getMethod("getLogger", String.class).invoke(loggerContext, loggerName);
                    loggerClass.getMethod("setLevel", levelClass).invoke(logger, loggerLevel);
                    log.debug("Applied log level from config: {} = {}", loggerName, logLevel);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to apply logging configuration from HOCON config", e);
        }
    }
    
    private void startMetricsCollection(Config pipelineConfig) {
        if (!enableMetrics) {
            log.debug("Metrics collection disabled by configuration");
            return;
        }
        
        metricsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-collector");
            t.setDaemon(true);
            return t;
        });
        
        metricsExecutor.scheduleAtFixedRate(this::collectMetrics, updateIntervalSeconds, updateIntervalSeconds, TimeUnit.SECONDS);
        log.debug("Started metrics collection with {} second interval", updateIntervalSeconds);
    }
    
    private void resetChannelBindingErrorCounts() {
        for (AbstractChannelBinding<?> binding : channelBindings) {
            binding.resetErrorCount();
        }
        log.debug("Reset error counts for {} channel bindings", channelBindings.size());
    }

    private void collectMetrics() {
        for (AbstractChannelBinding<?> binding : channelBindings) {
            try {
                long messageCount = binding.getAndResetCount();
                long errorCount = binding.getErrorCount();
                double messagesPerSecond = (double) messageCount / updateIntervalSeconds;
                
                String key = String.format("%s:%s:%s", binding.getServiceName(), binding.getChannelName(), binding.getDirection());
                channelMetrics.put(key, ChannelMetrics.withCurrentTimestamp(messagesPerSecond, (int) errorCount));
            } catch (Exception e) {
                log.warn("Failed to collect metrics for binding {}: {}", binding, e.getMessage(), e);
            }
        }
    }

    public void startAll() {
        if (servicesStarted) {
            log.debug("Services already started, skipping duplicate startAll() call");
            return;
        }
        
        log.debug("Starting services...");
        resetChannelBindingErrorCounts();
        servicesStarted = true;
        
        List<String> servicesToStart = startupSequence.isEmpty() ? new ArrayList<>(services.keySet()) : startupSequence;
        
        for (String serviceName : servicesToStart) {
            IService service = services.get(serviceName);
            if (service == null) {
                log.warn("Service in startup sequence '{}' not configured", serviceName);
                continue;
            }
            
            if (service.getServiceStatus().state() == State.STOPPED) {
                log.debug("Starting service: {}", serviceName);
                Thread thread = new Thread(service::start);
                thread.setName(serviceName);
                serviceThreads.put(serviceName, thread);
                thread.start();
                
                if (!startupSequence.isEmpty()) {
                    waitForServiceToStart(serviceName, service);
                }
            } else {
                log.debug("Service {} already running", serviceName);
            }
        }
        log.debug("Service startup initiated");
    }

    private void waitForServiceToStart(String serviceName, IService service) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = 10000; // 10 second timeout
        
        while (service.getServiceStatus().state() == State.STOPPED) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                log.warn("Timeout waiting for service '{}' to start after {}ms", serviceName, timeoutMs);
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for service '{}' to start", serviceName);
                break;
            }
        }
        
        if (service.getServiceStatus().state() != State.STOPPED) {
            log.debug("Service '{}' started successfully", serviceName);
        }
    }

    public void stopAll() {
        if (stopped) return;
        
        log.info("Stopping all services...");
        services.values().forEach(IService::stop);
        
        boolean allStopped = true;
        for (Thread thread : serviceThreads.values()) {
            try {
                thread.join(1000);
                if (thread.isAlive()) {
                    thread.interrupt();
                    allStopped = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                allStopped = false;
            }
        }
        
        serviceThreads.clear();
        stopped = true;
        
        if (metricsExecutor != null) {
            metricsExecutor.shutdown();
            try {
                if (!metricsExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    metricsExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                metricsExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        log.info(allStopped ? "All services stopped" : "Some services may still be running");
    }

    public void pauseService(String serviceName) {
        IService service = services.get(serviceName);
        if (service != null) service.pause();
        else log.warn("Service not found: {}", serviceName);
    }

    public void resumeService(String serviceName) {
        IService service = services.get(serviceName);
        if (service != null) service.resume();
        else log.warn("Service not found: {}", serviceName);
    }

    public void pauseAll() {
        services.values().forEach(IService::pause);
        log.info("All services paused.");
    }

    public void resumeAll() {
        services.values().forEach(IService::resume);
        log.info("All services resumed.");
    }

    public void startService(String serviceName) {
        IService service = services.get(serviceName);
        if (service != null) {
            Thread thread = new Thread(service::start);
            thread.setName(serviceName);
            serviceThreads.put(serviceName, thread);
            thread.start();
        } else {
            log.warn("Service not found: {}", serviceName);
        }
    }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("========================================================================================\n");
        sb.append(String.format("%-20s | %-7s | %-4s | %-11s | %s%n", "SERVICE / CHANNEL", "STATE", "I/O", "QUEUE", "ACTIVITY"));
        sb.append("========================================================================================\n");
        
        services.forEach((serviceName, service) -> {
            ServiceStatus status = service.getServiceStatus();
            sb.append(String.format("%-20s | %-7s | %-4s | %-11s | %s%n", serviceName, status.state(), "", "", service.getActivityInfo()));
            
            status.channelBindings().forEach(binding -> {
                String prefix = "└─ ";
                String channelInfo = prefix + binding.channelName();
                String queueInfo = getQueueInfo(binding.channelName());
                String activityInfo = "disabled";
                if (enableMetrics) {
                    String metricsKey = String.format("%s:%s:%s", serviceName, binding.channelName(), binding.direction());
                    ChannelMetrics metrics = channelMetrics.get(metricsKey);
                    if (metrics != null) {
                        activityInfo = String.format("(%.1f/s, %d errors)", metrics.messagesPerSecond(), metrics.errorCount());
                    } else {
                        activityInfo = "(0.0/s)";
                    }
                }
                sb.append(String.format("%-20s | %-7s | %-4s | %-11s | %s%n", channelInfo, binding.state(), binding.direction(), queueInfo, activityInfo));
            });
            sb.append("---------------------------------------------------------------------------------------\n");
        });
        
        sb.append("========================================================================================\n");
        return sb.toString();
    }
    
    private String getQueueInfo(String channelName) {
        Object resource = resources.get(channelName);
        if (resource instanceof org.evochora.datapipeline.api.channels.IMonitorableChannel) {
            org.evochora.datapipeline.api.channels.IMonitorableChannel mc = (org.evochora.datapipeline.api.channels.IMonitorableChannel) resource;
            return String.format("%d / %d", mc.getBacklogSize(), mc.getCapacity());
        }
        return "N/A";
    }

    public List<ServiceStatus> getPipelineStatus() {
        List<ServiceStatus> statuses = new ArrayList<>();
        services.values().forEach(service -> statuses.add(service.getServiceStatus()));
        return statuses;
    }

    public Map<String, IService> getServices() { return services; }
    public Map<String, Object> getResources() { return resources; }
    public Map<String, Thread> getServiceThreads() { return serviceThreads; }
}
