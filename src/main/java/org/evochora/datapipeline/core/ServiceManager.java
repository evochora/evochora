package org.evochora.datapipeline.core;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.api.services.ServiceStatus;
import org.evochora.datapipeline.services.AbstractService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The central orchestrator for the data pipeline. It is responsible for building the
 * entire pipeline from a configuration object and managing the lifecycle of all services.
 */
public class ServiceManager {

    private static final Logger log = LoggerFactory.getLogger(ServiceManager.class);
    private final Map<String, Object> channels = new HashMap<>();
    private final Map<String, IService> services = new HashMap<>();
    private final Map<String, Thread> serviceThreads = new HashMap<>();
    private volatile boolean stopped = false;

    /**
     * Constructs a ServiceManager for a pipeline defined by the given configuration.
     *
     * @param rootConfig The root configuration object for the pipeline.
     */
    public ServiceManager(Config rootConfig) {
        log.info("Initializing ServiceManager...");
        applyLoggingConfiguration(rootConfig);
        buildPipeline(rootConfig);
        log.info("ServiceManager initialized with {} channels and {} services", 
                channels.size(), services.size());
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

        // 3. Wire Services and Channels
        for (String serviceName : servicesConfig.root().keySet()) {
            Config serviceConfig = servicesConfig.getConfig(serviceName);
            IService service = services.get(serviceName);

            if (serviceConfig.hasPath("inputs")) {
                for (String channelName : serviceConfig.getStringList("inputs")) {
                    Object channel = channels.get(channelName);
                    if (service instanceof AbstractService) {
                        ((AbstractService) service).addInputChannel(channelName, (org.evochora.datapipeline.api.channels.IInputChannel<?>) channel);
                    }
                }
            }

            if (serviceConfig.hasPath("outputs")) {
                for (String channelName : serviceConfig.getStringList("outputs")) {
                    Object channel = channels.get(channelName);
                    if (service instanceof AbstractService) {
                        ((AbstractService) service).addOutputChannel(channelName, (org.evochora.datapipeline.api.channels.IOutputChannel<?>) channel);
                    }
                }
            }
        }
    }

    /**
     * Starts all managed services, each in its own thread.
     */
    public void startAll() {
        log.info("Starting all services...");
        for (Map.Entry<String, IService> entry : services.entrySet()) {
            String serviceName = entry.getKey();
            IService service = entry.getValue();
            Thread thread = new Thread(service::start);
            thread.setName(serviceName);
            serviceThreads.put(serviceName, thread);
            thread.start();
        }
        // Individual services will log when they are actually started
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
                String activityInfo = String.format("(%.1f/s)", binding.messagesPerSecond());
                
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
        // This would be implemented based on the actual service type
        // For now, return a placeholder
        if (serviceName.contains("simulation")) {
            return "Not implemented";
        } else if (serviceName.contains("merger")) {
            return "Not implemented";
        } else {
            return "";
        }
    }
    
    private String getQueueInfo(String channelName) {
        Object channel = channels.get(channelName);
        if (channel instanceof org.evochora.datapipeline.api.channels.IMonitorableChannel) {
            org.evochora.datapipeline.api.channels.IMonitorableChannel monitorableChannel = 
                    (org.evochora.datapipeline.api.channels.IMonitorableChannel) channel;
            long size = monitorableChannel.getQueueSize();
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
