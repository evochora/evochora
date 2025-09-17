package org.evochora.datapipeline.core;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.api.services.ServiceStatus;
import org.evochora.datapipeline.services.BaseService;
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

    /**
     * Constructs a ServiceManager for a pipeline defined by the given configuration.
     *
     * @param rootConfig The root configuration object for the pipeline.
     */
    public ServiceManager(Config rootConfig) {
        applyLoggingConfiguration(rootConfig);
        buildPipeline(rootConfig);
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

            if (loggingConfig.hasPath("root")) {
                String rootLevel = loggingConfig.getString("root");
                Object level = levelClass.getMethod("toLevel", String.class).invoke(null, rootLevel.toUpperCase());
                Object rootLogger = loggerContext.getClass().getMethod("getLogger", String.class).invoke(loggerContext, Logger.ROOT_LOGGER_NAME);
                loggerClass.getMethod("setLevel", levelClass).invoke(rootLogger, level);
                log.debug("Applied root log level from config: {}", rootLevel);
            }

            if (loggingConfig.hasPath("loggers")) {
                Config loggersConfig = loggingConfig.getConfig("loggers");
                for (Map.Entry<String, Object> entry : loggersConfig.root().unwrapped().entrySet()) {
                    String loggerName = entry.getKey();
                    String logLevel = entry.getValue().toString();
                    Object level = levelClass.getMethod("toLevel", String.class).invoke(null, logLevel.toUpperCase());
                    Object logger = loggerContext.getClass().getMethod("getLogger", String.class).invoke(loggerContext, loggerName);
                    loggerClass.getMethod("setLevel", levelClass).invoke(logger, level);
                    log.debug("Applied log level from config: {} = {}", loggerName, logLevel);
                }
            }
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
                    if (service instanceof BaseService) {
                        ((BaseService) service).addInputChannel(channelName, (org.evochora.datapipeline.api.channels.IInputChannel<?>) channel);
                    }
                }
            }

            if (serviceConfig.hasPath("outputs")) {
                for (String channelName : serviceConfig.getStringList("outputs")) {
                    Object channel = channels.get(channelName);
                    if (service instanceof BaseService) {
                        ((BaseService) service).addOutputChannel(channelName, (org.evochora.datapipeline.api.channels.IOutputChannel<?>) channel);
                    }
                }
            }
        }
    }

    /**
     * Starts all managed services, each in its own thread.
     */
    public void startAll() {
        for (Map.Entry<String, IService> entry : services.entrySet()) {
            String serviceName = entry.getKey();
            IService service = entry.getValue();
            Thread thread = new Thread(service::start);
            thread.setName(serviceName);
            serviceThreads.put(serviceName, thread);
            thread.start();
        }
    }

    /**
     * Stops all managed services and ensures their threads are properly shut down.
     */
    public void stopAll() {
        for (IService service : services.values()) {
            service.stop();
        }
        for (Thread thread : serviceThreads.values()) {
            try {
                thread.join(1000); // Wait for thread to die
                if (thread.isAlive()) {
                    thread.interrupt(); // Force interruption if it's stuck
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        serviceThreads.clear();
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
        }
    }

    /**
     * Retrieves the status of all managed services.
     *
     * @return A list of {@link ServiceStatus} objects.
     */
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
