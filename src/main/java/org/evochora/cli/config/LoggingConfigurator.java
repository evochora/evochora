package org.evochora.cli.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.typesafe.config.Config;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Configures the application's logging system based on HOCON configuration.
 * This class reads logging settings from the configuration and applies them
 * to the Logback logging framework at runtime.
 * 
 * <h2>Configuration Structure:</h2>
 * <pre>
 * logging {
 *   format = "PLAIN"  # "PLAIN", "DETAILED" or "JSON"
 *   default-level = "WARN"  # Default log level for all loggers
 *   levels {
 *     # Specific logger levels - override the default for particular components
 *     "org.evochora.datapipeline.ServiceManager" = "INFO"
 *   }
 * }
 * </pre>
 */
public final class LoggingConfigurator {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(LoggingConfigurator.class);
    private static final String LOGGING_CONFIG_PATH = "logging";
    private static final String FORMAT_KEY = "format";
    private static final String DEFAULT_LEVEL_KEY = "default-level";
    private static final String LEVELS_KEY = "levels";

    private static boolean loggingConfigured = false;

    /**
     * Configures the logging system based on the provided configuration.
     * This method is idempotent - calling it multiple times has no additional effect.
     *
     * @param config The application configuration containing logging settings.
     */
    public static void configure(final Config config) {
        if (loggingConfigured) {
            LOGGER.debug("Logging already configured, skipping.");
            return;
        }

        if (!config.hasPath(LOGGING_CONFIG_PATH)) {
            LOGGER.debug("No logging configuration found, using Logback defaults.");
            loggingConfigured = true;
            return;
        }

        try {
            final Config loggingConfig = config.getConfig(LOGGING_CONFIG_PATH);
            final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

            // Configure format
            configureFormat(loggingConfig, context);

            // Configure default log level
            configureDefaultLevel(loggingConfig, context);

            // Configure specific logger levels
            configureSpecificLevels(loggingConfig, context);

            loggingConfigured = true;
            LOGGER.debug("Logging configuration applied successfully.");

        } catch (final Exception e) {
            LOGGER.error("Failed to configure logging, using Logback defaults.", e);
            loggingConfigured = true; // Prevent retry attempts
        }
    }

    /**
     * Names the appender of {@code logback.xml} that writes the given format: {@code PLAIN} is
     * date, time, level and message, {@code DETAILED} adds the thread and the logger that wrote
     * the line, {@code JSON} is one JSON object per line.
     *
     * @param format the value of {@code logging.format}, in any case
     * @return the name of the appender
     * @throws IllegalArgumentException if the value is none of the three formats
     */
    public static String appenderFor(final String format) {
        return switch (format.toUpperCase(java.util.Locale.ROOT)) {
            case "PLAIN" -> "CONSOLE_PLAIN";
            case "DETAILED" -> "CONSOLE_DETAILED";
            case "JSON" -> "CONSOLE";
            default -> throw new IllegalArgumentException("Unknown logging.format '" + format
                    + "'. Accepted values: PLAIN, DETAILED, JSON");
        };
    }

    /**
     * Configures the log format. A logging block without a format leaves the format as Logback
     * has it, as a configuration without a logging block does.
     */
    private static void configureFormat(final Config loggingConfig, final LoggerContext context) {
        if (!loggingConfig.hasPath(FORMAT_KEY)) {
            return;
        }
        final String format = loggingConfig.getString(FORMAT_KEY);
        final String appender = appenderFor(format);

        // Set both context property and system property for maximum compatibility
        context.putProperty("evochora.logging.format", appender);
        System.setProperty("evochora.logging.format", appender);
        LOGGER.debug("Configured logging format: {}", format);
    }


    /**
     * Configures the default log level for all loggers.
     * Skips configuration in test environment to preserve logback-test.xml settings.
     */
    private static void configureDefaultLevel(final Config loggingConfig, final LoggerContext context) {
        // Skip default level configuration if logback-test.xml exists (test environment)
        // This allows test-specific logging configuration to take precedence
        if (LoggingConfigurator.class.getClassLoader().getResource("logback-test.xml") != null) {
            LOGGER.debug("Skipping default level configuration (test environment detected via logback-test.xml).");
            return;
        }

        if (loggingConfig.hasPath(DEFAULT_LEVEL_KEY)) {
            final String levelStr = loggingConfig.getString(DEFAULT_LEVEL_KEY);
            final Level level = Level.toLevel(levelStr, Level.WARN);

            final Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
            rootLogger.setLevel(level);

            LOGGER.debug("Configured default log level: {}", level);
        }
    }

    /**
     * Configures specific logger levels as defined in the configuration.
     */
    private static void configureSpecificLevels(final Config loggingConfig, final LoggerContext context) {
        if (!loggingConfig.hasPath(LEVELS_KEY)) {
            LOGGER.debug("No specific logger levels configured.");
            return;
        }

        final Config levelsConfig = loggingConfig.getConfig(LEVELS_KEY);
        int configuredCount = 0;

        for (final Map.Entry<String, com.typesafe.config.ConfigValue> entry : levelsConfig.root().entrySet()) {
            final String loggerName = entry.getKey();
            final String levelName = entry.getValue().unwrapped().toString();
            
            try {
                final Level level = Level.toLevel(levelName);
                final Logger logger = context.getLogger(loggerName);
                logger.setLevel(level);
                configuredCount++;
                
                LOGGER.debug("Configured logger '{}' to level: {}", loggerName, level);
            } catch (final Exception e) {
                LOGGER.warn("Failed to configure logger '{}' with level '{}': {}", loggerName, levelName, e.getMessage());
            }
        }

        LOGGER.debug("Configured {} specific logger levels.", configuredCount);
    }

    /**
     * Resets the logging configuration state. This is primarily useful for testing.
     * In production, this method should not be called after the initial configuration.
     */
    public static void reset() {
        loggingConfigured = false;
    }
}
