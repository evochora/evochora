package org.evochora.cli.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.io.File;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/**
 * Central configuration loader for all CLI entry points.
 * <p>
 * Composes HOCON configuration from multiple sources with the following precedence
 * (highest to lowest):
 * <ol>
 *   <li>Java system properties ({@code -Dkey=value})</li>
 *   <li>Environment variables (mapped to dot-notation)</li>
 *   <li>User configuration file (e.g., {@code config/evochora.conf} or {@code config/local.conf})</li>
 *   <li>Default reference configuration ({@code reference.conf} on the classpath)</li>
 * </ol>
 * <p>
 * Uses {@link ConfigFactory#defaultReferenceUnresolved()} to defer substitution resolution
 * until after all layers are composed. This ensures that user overrides of values referenced
 * by substitutions (e.g., {@code pipeline.tuning.samplingInterval}) propagate correctly to
 * all downstream references in {@code reference.conf}.
 *
 * @see #resolve(File, ConfigMessageHandler) for the standard 5-level config file discovery cascade
 */
public final class ConfigLoader {

    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_FILE_NAME = "evochora.conf";

    private ConfigLoader() {
    }

    /**
     * Message severity levels for configuration resolution feedback.
     */
    public enum MessageLevel {
        /** Informational progress (e.g., which config file was selected). */
        INFO,
        /** Warning about fallback behavior (e.g., no config file found). */
        WARN
    }

    /**
     * Callback for receiving progress messages during configuration file resolution.
     * <p>
     * Implementations choose how to present messages (e.g., SLF4J logging, console output).
     */
    @FunctionalInterface
    public interface ConfigMessageHandler {

        /**
         * Called with a status message during the config resolution cascade.
         *
         * @param level   the severity of the message.
         * @param message the human-readable description.
         */
        void log(MessageLevel level, String message);
    }

    /**
     * Resolves configuration using the standard 5-level fallback cascade:
     * <ol>
     *   <li><strong>Explicit file:</strong> config file passed via CLI {@code --config} option</li>
     *   <li><strong>System property:</strong> {@code -Dconfig.file} JVM argument</li>
     *   <li><strong>Working directory:</strong> {@code config/evochora.conf} relative to CWD</li>
     *   <li><strong>Installation directory:</strong> {@code APP_HOME/config/evochora.conf}
     *       inferred from the running JAR location</li>
     *   <li><strong>Classpath defaults:</strong> {@code reference.conf} only</li>
     * </ol>
     * <p>
     * At each level, system properties and environment variables still take precedence over
     * the discovered config file (see class-level documentation for the full priority hierarchy).
     *
     * @param explicitConfigFile config file from CLI option, or {@code null} for auto-discovery.
     * @param handler            callback for resolution progress messages.
     * @return the fully resolved application {@link Config}.
     * @throws IllegalArgumentException                if an explicitly specified config file
     *                                                 (via parameter or {@code -Dconfig.file}) does not exist.
     * @throws com.typesafe.config.ConfigException     if the configuration cannot be parsed or resolved.
     */
    public static Config resolve(final File explicitConfigFile, final ConfigMessageHandler handler) {
        // 1) Explicit CLI option --config
        if (explicitConfigFile != null) {
            if (!explicitConfigFile.exists()) {
                throw new IllegalArgumentException(
                        "Configuration file not found: " + explicitConfigFile.getAbsolutePath());
            }
            handler.log(MessageLevel.INFO,
                    "Using configuration file specified via --config: " + explicitConfigFile.getAbsolutePath());
            return loadFromFile(explicitConfigFile);
        }

        // 2) Standard Typesafe Config system property -Dconfig.file
        final String systemConfigPath = System.getProperty("config.file");
        if (systemConfigPath != null && !systemConfigPath.isBlank()) {
            File systemConfigFile = new File(systemConfigPath);
            if (!systemConfigFile.isAbsolute()) {
                systemConfigFile = systemConfigFile.getAbsoluteFile();
            }
            if (!systemConfigFile.exists()) {
                throw new IllegalArgumentException(
                        "Configuration file specified via -Dconfig.file not found: "
                                + systemConfigFile.getAbsolutePath());
            }
            handler.log(MessageLevel.INFO,
                    "Using configuration file specified via -Dconfig.file: "
                            + systemConfigFile.getAbsolutePath());
            return loadFromFile(systemConfigFile);
        }

        // 3) config/evochora.conf in the current working directory
        final File cwdConfigFile = new File(CONFIG_DIR, CONFIG_FILE_NAME);
        if (cwdConfigFile.exists()) {
            handler.log(MessageLevel.INFO,
                    "Using configuration file found in current directory: "
                            + cwdConfigFile.getAbsolutePath());
            return loadFromFile(cwdConfigFile);
        }

        // 4) APP_HOME/config/evochora.conf inferred from the running JAR
        final File installationConfigFile = detectInstallationConfigFile();
        if (installationConfigFile != null) {
            handler.log(MessageLevel.INFO,
                    "Using configuration file from installation directory: "
                            + installationConfigFile.getAbsolutePath());
            return loadFromFile(installationConfigFile);
        }

        // 5) Fall back to classpath defaults only
        handler.log(MessageLevel.WARN,
                "No '" + CONFIG_DIR + "/" + CONFIG_FILE_NAME
                        + "' found in current directory or installation directory. "
                        + "Using default configuration from classpath.");
        return loadDefaults();
    }

    /**
     * Loads configuration from a file, merged with classpath defaults.
     *
     * @param configFile the configuration file to load.
     * @return the fully resolved application {@link Config}.
     */
    static Config loadFromFile(final File configFile) {
        return ConfigFactory.systemProperties()
            .withFallback(ConfigFactory.systemEnvironment())
            .withFallback(ConfigFactory.parseFile(configFile))
            .withFallback(ConfigFactory.defaultReferenceUnresolved())
            .resolve();
    }

    /**
     * Loads configuration from classpath defaults only (no user config file).
     *
     * @return the fully resolved application {@link Config}.
     */
    static Config loadDefaults() {
        return ConfigFactory.systemProperties()
            .withFallback(ConfigFactory.systemEnvironment())
            .withFallback(ConfigFactory.defaultReferenceUnresolved())
            .resolve();
    }

    /**
     * Attempts to detect the Evochora installation directory and its default configuration file.
     * <p>
     * The installation layout created by Gradle's {@code installDist} / {@code distZip} tasks is:
     * <pre>
     *   APP_HOME/
     *     bin/evochora
     *     lib/evochora-*.jar
     *     config/evochora.conf
     * </pre>
     * Infers {@code APP_HOME} from the location of the running JAR (or classes directory during
     * development) and returns {@code APP_HOME/config/evochora.conf} if it exists.
     *
     * @return the detected configuration file, or {@code null} if it cannot be determined
     *         or does not exist.
     */
    private static File detectInstallationConfigFile() {
        try {
            final ProtectionDomain protectionDomain = ConfigLoader.class.getProtectionDomain();
            if (protectionDomain == null) {
                return null;
            }
            final CodeSource codeSource = protectionDomain.getCodeSource();
            if (codeSource == null) {
                return null;
            }
            final URL location = codeSource.getLocation();
            final File jarOrClasses = new File(location.toURI());

            final File appHome;
            if (jarOrClasses.isFile()) {
                // Running from the application JAR under APP_HOME/lib
                final File libDir = jarOrClasses.getParentFile();
                if (libDir == null) {
                    return null;
                }
                appHome = libDir.getParentFile();
            } else {
                // Running from classes directory (e.g., build/classes/java/main/).
                // Unlikely to find config/ here; CWD-based cascade (level 3) handles development.
                appHome = jarOrClasses;
            }

            if (appHome == null) {
                return null;
            }

            final File configDir = new File(appHome, CONFIG_DIR);
            final File configFile = new File(configDir, CONFIG_FILE_NAME);
            return configFile.exists() ? configFile : null;
        } catch (Exception ignored) {
            // Best-effort detection; fall back to other mechanisms on any failure.
            return null;
        }
    }
}
