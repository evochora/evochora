package org.evochora.datapipeline.core;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.api.services.ServiceStatus;
import org.evochora.datapipeline.api.services.ChannelMetrics;
import org.evochora.datapipeline.api.services.State;
import org.evochora.datapipeline.services.AbstractService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The central orchestrator for the data pipeline. It is responsible for building the
 * entire pipeline from a configuration object and managing the lifecycle of all services.
 */
public class ServiceManager {

    private static final Logger log = LoggerFactory.getLogger(ServiceManager.class);
    private final Map<String, Object> channels = new HashMap<>();
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
        
        // Parse configuration first
        Config pipelineConfig = rootConfig.getConfig("pipeline");
        this.autoStart = pipelineConfig.hasPath("autoStart") ? pipelineConfig.getBoolean("autoStart") : false;
        this.startupSequence = pipelineConfig.hasPath("startupSequence") ? 
            pipelineConfig.getStringList("startupSequence") : new ArrayList<>();
        
        // Parse metrics configuration
        Config metricsConfig = pipelineConfig.hasPath("metrics") ? pipelineConfig.getConfig("metrics") : ConfigFactory.empty();
        this.enableMetrics = metricsConfig.hasPath("enableMetrics") ? metricsConfig.getBoolean("enableMetrics") : true;
        this.updateIntervalSeconds = metricsConfig.hasPath("updateIntervalSeconds") ? metricsConfig.getInt("updateIntervalSeconds") : 1;
        // Now build pipeline with configuration available
        buildPipeline(rootConfig);
        
        log.info("ServiceManager initialized with {} channels and {} services (autoStart: {}, startupSequence: {}, enableMetrics: {})", 
                channels.size(), services.size(), autoStart, startupSequence, enableMetrics);
        
        // Auto-start services if configured
        if (autoStart) {
            log.info("Auto-starting services in sequence: {}", startupSequence);
            // Note: Channel wiring happens in buildPipeline() before this point
            startAll();
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

            // Apply default level (root logger)
            String defaultLevel = loggingConfig.hasPath("default-level") 
                    ? loggingConfig.getString("default-level") 
                    : "INFO";
            Object level = levelClass.getMethod("toLevel", String.class).invoke(null, defaultLevel.toUpperCase());
            Object rootLogger = loggerContext.getClass().getMethod("getLogger", String.class).invoke(loggerContext, Logger.ROOT_LOGGER_NAME);
            loggerClass.getMethod("setLevel", levelClass).invoke(rootLogger, level);
            log.debug("Applied default log level from config: {}", defaultLevel);

            // Apply specific logger levels
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

            // Format configuration is now handled in RunCommand.loadConfiguration()

        } catch (Exception e) {
            log.warn("Failed to apply logging configuration from HOCON config", e);
        }
    }






    private void buildPipeline(Config rootConfig) {
        Config pipelineConfig = rootConfig.getConfig("pipeline");

        // 1. Instantiate Channels
        Config channelsConfig = pipelineConfig.getConfig("channels");
        for (String channelName : channelsConfig.root().keySet()) {
            try {
                Config channelDefinition = channelsConfig.getConfig(channelName);
                String className = channelDefinition.getString("className");
                Config channelOptions = channelDefinition.getConfig("options");
                Constructor<?> constructor = Class.forName(className).getConstructor(Config.class);
                Object channel = constructor.newInstance(channelOptions);
                channels.put(channelName, channel);
            } catch (Exception e) {
                throw new RuntimeException("Failed to instantiate channel: " + channelName, e);
            }
        }

        // 2. Instantiate Services
        Config servicesConfig = pipelineConfig.getConfig("services");
        for (String serviceName : servicesConfig.root().keySet()) {
            try {
                Config serviceDefinition = servicesConfig.getConfig(serviceName);
                String className = serviceDefinition.getString("className");
                Config serviceOptions = serviceDefinition.hasPath("options")
                        ? serviceDefinition.getConfig("options")
                        : ConfigFactory.empty();
                Constructor<?> constructor = Class.forName(className).getConstructor(Config.class);
                IService service = (IService) constructor.newInstance(serviceOptions);
                services.put(serviceName, service);
            } catch (Exception e) {
                throw new RuntimeException("Failed to instantiate service: " + serviceName, e);
            }
        }

        // 3. Wire Services and Channels with Monitoring Wrappers
        for (String serviceName : servicesConfig.root().keySet()) {
            Config serviceConfig = servicesConfig.getConfig(serviceName);
            IService service = services.get(serviceName);

            if (serviceConfig.hasPath("inputs")) {
                for (String channelName : serviceConfig.getStringList("inputs")) {
                    Object channel = channels.get(channelName);
                    if (service instanceof AbstractService) {
                        // Create input channel binding wrapper
                        InputChannelBinding<?> binding = new InputChannelBinding<>(serviceName, channelName, 
                            (org.evochora.datapipeline.api.channels.IInputChannel<?>) channel);
                        channelBindings.add(binding);
                        ((AbstractService) service).addInputChannel(channelName, binding);
                    }
                }
            }

            if (serviceConfig.hasPath("outputs")) {
                for (String channelName : serviceConfig.getStringList("outputs")) {
                    Object channel = channels.get(channelName);
                    if (service instanceof AbstractService) {
                        // Create output channel binding wrapper
                        OutputChannelBinding<?> binding = new OutputChannelBinding<>(serviceName, channelName, 
                            (org.evochora.datapipeline.api.channels.IOutputChannel<?>) channel);
                        channelBindings.add(binding);
                        ((AbstractService) service).addOutputChannel(channelName, binding);
                    }
                }
            }
        }
        
        // 4. Start Metrics Collection
        startMetricsCollection(pipelineConfig);
    }
    
    /**
     * Starts the metrics collection system with configurable update interval.
     * 
     * @param pipelineConfig The pipeline configuration containing metrics settings
     */
    private void startMetricsCollection(Config pipelineConfig) {
        Config metricsConfig = pipelineConfig.hasPath("metrics") ? pipelineConfig.getConfig("metrics") : ConfigFactory.empty();
        
        if (!enableMetrics) {
            log.debug("Metrics collection disabled by configuration");
            return;
        }
        
        int updateIntervalSeconds = metricsConfig.hasPath("updateIntervalSeconds") ? 
            metricsConfig.getInt("updateIntervalSeconds") : 3;
        
        metricsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-collector");
            t.setDaemon(true);
            return t;
        });
        
        metricsExecutor.scheduleAtFixedRate(this::collectMetrics, updateIntervalSeconds, updateIntervalSeconds, TimeUnit.SECONDS);
        log.debug("Started metrics collection with {} second interval", updateIntervalSeconds);
    }
    
    /**
     * Resets error counts for all channel bindings when services start.
     * This ensures error counts are reset only on service restart, not on every metrics collection.
     */
    private void resetChannelBindingErrorCounts() {
        for (AbstractChannelBinding<?> binding : channelBindings) {
            binding.resetErrorCount();
        }
        log.debug("Reset error counts for {} channel bindings", channelBindings.size());
    }

    /**
     * Collects metrics from all channel bindings and updates the metrics cache.
     * This method runs periodically to calculate throughput values using a simple rate calculation.
     */
    private void collectMetrics() {
        long currentTime = System.currentTimeMillis();
        
        for (AbstractChannelBinding<?> binding : channelBindings) {
            try {
                long messageCount = binding.getAndResetCount();
                long errorCount = binding.getErrorCount();
                
                // Calculate messages per second based on actual time elapsed
                // This is a simple rate calculation, not a moving average
                double messagesPerSecond = 0.0;
                if (messageCount > 0) {
                    // Use the configured update interval for rate calculation
                    messagesPerSecond = (double) messageCount / updateIntervalSeconds;
                }
                
                String key = String.format("%s:%s:%s", binding.getServiceName(), binding.getChannelName(), binding.getDirection());
                channelMetrics.put(key, ChannelMetrics.withCurrentTimestamp(messagesPerSecond, (int) errorCount));
                
            } catch (Exception e) {
                log.warn("Failed to collect metrics for binding {}: {}", binding, e.getMessage());
                // Continue with next binding - don't let metric failures affect pipeline
            }
        }
    }

    /**
     * Starts all managed services, each in its own thread.
     * Uses startupSequence if configured, otherwise starts services in arbitrary order.
     * Skips services that are already running.
     */
    public void startAll() {
        if (servicesStarted) {
            log.debug("Services already started, skipping duplicate startAll() call");
            return;
        }
        
        log.info("Starting services...");
        
        // Reset error counts for all channel bindings before starting services
        resetChannelBindingErrorCounts();
        
        servicesStarted = true;
        
        List<String> servicesToStart = startupSequence.isEmpty() ? 
            new ArrayList<>(services.keySet()) : startupSequence;
        
        for (String serviceName : servicesToStart) {
            IService service = services.get(serviceName);
            if (service == null) {
                log.warn("Service in startup sequence '{}' not configured", serviceName);
                continue;
            }
            
            State currentState = service.getServiceStatus().state();
            if (currentState == State.STOPPED) {
                log.debug("Starting service: {}", serviceName);
                Thread thread = new Thread(service::start);
                thread.setName(serviceName);
                serviceThreads.put(serviceName, thread);
                thread.start();
                
                // If we have a startup sequence, wait for this service to fully start
                if (!startupSequence.isEmpty()) {
                    waitForServiceToStart(serviceName, service);
                }
            } else {
                log.debug("Service {} already running (state: {})", serviceName, currentState);
            }
        }
        
        log.info("Service startup initiated");
    }

    /**
     * Waits for a service to fully start by polling its state until it's no longer STOPPED.
     * This ensures sequential startup when using startupSequence.
     * 
     * @param serviceName The name of the service to wait for
     * @param service The service instance to monitor
     */
    private void waitForServiceToStart(String serviceName, IService service) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = 10000; // 10 second timeout
        
        while (service.getServiceStatus().state() == State.STOPPED) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                log.warn("Timeout waiting for service '{}' to start after {}ms", serviceName, timeoutMs);
                break;
            }
            
            try {
                Thread.sleep(50); // Poll every 50ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for service '{}' to start", serviceName);
                break;
            }
        }
        
        State finalState = service.getServiceStatus().state();
        if (finalState != State.STOPPED) {
            log.debug("Service '{}' started successfully (state: {})", serviceName, finalState);
        }
    }

    /**
     * Stops all managed services and ensures their threads are properly shut down.
     */
    public void stopAll() {
        if (stopped) {
            return; // Already stopped, avoid duplicate logging
        }
        
        log.info("Stopping all services...");
        for (IService service : services.values()) {
            service.stop();
        }
        
        // Wait for all threads to actually finish
        boolean allStopped = true;
        for (Thread thread : serviceThreads.values()) {
            try {
                thread.join(1000); // Wait for thread to die
                if (thread.isAlive()) {
                    thread.interrupt(); // Force interruption if it's stuck
                    allStopped = false; // At least one thread is still alive
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                allStopped = false;
            }
        }
        
        serviceThreads.clear();
        stopped = true;
        
        // Stop metrics collection
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
        
        if (allStopped) {
            log.info("All services stopped");
        } else {
            log.warn("Some services may still be running (forced to stop)");
        }
    }

    /**
     * Pauses a specific service.
     *
     * @param serviceName The name of the service to pause.
     */
    public void pauseService(String serviceName) {
        IService service = services.get(serviceName);
        if (service != null) {
            service.pause();
        } else {
            log.warn("Service not found: {}", serviceName);
        }
    }

    /**
     * Resumes a specific service.
     *
     * @param serviceName The name of the service to resume.
     */
    public void resumeService(String serviceName) {
        IService service = services.get(serviceName);
        if (service != null) {
            service.resume();
        } else {
            log.warn("Service not found: {}", serviceName);
        }
    }

    /**
     * Pauses all managed services.
     */
    public void pauseAll() {
        for (IService service : services.values()) {
            service.pause();
        }
        log.info("All services paused.");
    }

    /**
     * Resumes all managed services.
     */
    public void resumeAll() {
        for (IService service : services.values()) {
            service.resume();
        }
        log.info("All services resumed.");
    }

    /**
     * Starts a specific service by its name.
     *
     * @param serviceName The name of the service to start.
     */
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

    /**
     * Retrieves the status of all managed services.
     *
     * @return A list of {@link ServiceStatus} objects.
     */
    /**
     * Retrieves the status of all managed services as a formatted string.
     *
     * @return A string containing the detailed status of all services and their channel bindings.
     */
    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        
        // Header
        sb.append("========================================================================================\n");
        sb.append(String.format("%-20s | %-7s | %-4s | %-11s | %s%n", 
                "SERVICE / CHANNEL", "STATE", "I/O", "QUEUE", "ACTIVITY"));
        sb.append("========================================================================================\n");
        
        for (Map.Entry<String, IService> entry : services.entrySet()) {
            String serviceName = entry.getKey();
            ServiceStatus status = entry.getValue().getServiceStatus();
            
            // Service header line
            sb.append(String.format("%-20s | %-7s | %-4s | %-11s | %s%n", 
                    serviceName, status.state(), "", "", getServiceActivity(serviceName)));
            
            // Channel bindings
            for (int i = 0; i < status.channelBindings().size(); i++) {
                org.evochora.datapipeline.api.services.ChannelBindingStatus binding = status.channelBindings().get(i);
                String prefix = (i == status.channelBindings().size() - 1) ? "└─ " : "├─ ";
                String channelInfo = prefix + binding.channelName();
                
                String queueInfo = getQueueInfo(binding.channelName());
                
                // Get real metrics from our collection
                String activityInfo;
                if (enableMetrics) {
                    String metricsKey = String.format("%s:%s:%s", serviceName, binding.channelName(), binding.direction());
                    ChannelMetrics metrics = channelMetrics.get(metricsKey);
                    double messagesPerSecond = metrics != null ? metrics.messagesPerSecond() : 0.0;
                    int errorCount = metrics != null ? metrics.errorCount() : 0;
                    
                    if (errorCount > 0) {
                        activityInfo = String.format("(%.1f/s, %d errors)", messagesPerSecond, errorCount);
                    } else {
                        activityInfo = String.format("(%.1f/s)", messagesPerSecond);
                    }
                } else {
                    activityInfo = "disabled";
                }
                
                sb.append(String.format("%-20s | %-7s | %-4s | %-11s | %s%n", 
                        channelInfo, binding.state(), binding.direction(), queueInfo, activityInfo));
            }
            
            // Separator line
            sb.append("---------------------------------------------------------------------------------------\n");
        }
        
        // Footer
        sb.append("========================================================================================\n");
        
        return sb.toString();
    }
    
    private String getServiceActivity(String serviceName) {
        // Let each service decide what to show in the activity column
        IService service = services.get(serviceName);
        if (service != null) {
            return service.getActivityInfo();
        }
        return "";
    }
    
    private String getQueueInfo(String channelName) {
        Object channel = channels.get(channelName);
        if (channel instanceof org.evochora.datapipeline.api.channels.IMonitorableChannel) {
            org.evochora.datapipeline.api.channels.IMonitorableChannel monitorableChannel = 
                    (org.evochora.datapipeline.api.channels.IMonitorableChannel) channel;
            long size = monitorableChannel.getBacklogSize();
            long capacity = monitorableChannel.getCapacity();
            return String.format("%d / %d", size, capacity);
        } else {
            return "N/A";
        }
    }

    public List<ServiceStatus> getPipelineStatus() {
        List<ServiceStatus> statuses = new ArrayList<>();
        for (IService service : services.values()) {
            statuses.add(service.getServiceStatus());
        }
        return statuses;
    }

    public Map<String, IService> getServices() {
        return services;
    }

    public Map<String, Object> getChannels() {
        return channels;
    }

    public Map<String, Thread> getServiceThreads() {
        return serviceThreads;
    }

}
