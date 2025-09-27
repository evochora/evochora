package org.evochora.node.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsible for loading the application's HOCON configuration from various sources,
 * respecting a defined order of precedence:
 * 1. System Properties
 * 2. Environment Variables (mapped to dot-notation)
 * 3. A specified configuration file (e.g., 'evochora.conf')
 * 4. Default reference configuration (reference.conf on the classpath)
 */
public final class ConfigLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigLoader.class);

    /**
     * Loads the application configuration.
     *
     * @param configFileName The name of the configuration file to load from the filesystem/classpath.
     * @return The fully resolved application {@link Config}.
     */
    public static Config load(final String configFileName) {
        LOGGER.info("Loading configuration...");

        // 1. Load default reference configuration (reference.conf)
        final Config referenceConfig = ConfigFactory.defaultReference();
        LOGGER.debug("Loaded reference.conf.");

        // 2. Load the main configuration file (e.g., evochora.conf)
        final Config fileConfig = ConfigFactory.parseResources(configFileName)
            .withFallback(ConfigFactory.parseFile(new java.io.File(configFileName)));
        if (fileConfig.isEmpty()) {
            LOGGER.warn("Configuration file '{}' not found or is empty. Using defaults.", configFileName);
        } else {
            LOGGER.info("Loaded configuration from '{}'.", configFileName);
        }

        // 3. Load configuration from environment variables (mapped to dot-notation)
        // Example: EVOCHOA_PIPELINE_STARTUPSEQUENCE becomes evochora.pipeline.startupsequence
        final Config envConfig = ConfigFactory.systemEnvironment();
        LOGGER.debug("Loaded environment variables.");


        // 4. Load from Java system properties (-Dkey=value)
        final Config systemPropertiesConfig = ConfigFactory.systemProperties();
        LOGGER.debug("Loaded system properties.");

        // Combine them all, with system properties having the highest precedence.
        final Config resolvedConfig = systemPropertiesConfig
            .withFallback(envConfig)
            .withFallback(fileConfig)
            .withFallback(referenceConfig)
            .resolve();

        LOGGER.info("Configuration loaded successfully.");
        return resolvedConfig;
    }
}