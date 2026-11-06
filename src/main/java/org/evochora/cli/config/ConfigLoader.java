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
 *   <li>Override layer supplied by the caller (see
 *       {@link #resolve(File, Config, ConfigMessageHandler)}); empty unless a caller passes one</li>
 *   <li>Java system properties ({@code -Dkey=value}), among them {@code evochora.home}, the
 *       project folder (see {@link #resolve(File, ConfigMessageHandler)})</li>
 *   <li>Environment variables (raw names, available for {@code ${?ENV_VAR}} substitutions)</li>
 *   <li>User configuration file (e.g., {@code config/evochora.conf} or {@code config/local.conf})</li>
 *   <li>Default reference configuration ({@code reference.conf} on the classpath)</li>
 * </ol>
 * <p>
 * The above describes <em>source priority</em> within a resolved config. See
 * {@link #resolve(File, ConfigMessageHandler)} for how the user config file itself is discovered
 * (4-level file discovery cascade).
 * <p>
 * Uses {@link ConfigFactory#defaultReferenceUnresolved()} to defer substitution resolution
 * until after all layers are composed. This ensures that user overrides of values referenced
 * by substitutions (e.g., {@code pipeline.tuning.samplingInterval}) propagate correctly to
 * all downstream references in {@code reference.conf}.
 *
 * @see #resolve(File, ConfigMessageHandler) for the standard 4-level config file discovery cascade
 */
public final class ConfigLoader {

    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_FILE_NAME = "evochora.conf";
    private static final String LIB_DIR = "lib";

    /** The configuration path and system property that hold the project folder. */
    public static final String HOME_KEY = "evochora.home";

    /** The project folder this loader put into the system properties, so that a later call names its source. */
    private static ProjectFolder detected;

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
     * <p>
     * <strong>Thread Safety:</strong> Implementations do not need to be thread-safe;
     * the callback is invoked only from the thread calling {@link #resolve}.
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
     * The folder an Evochora project lives in, against which the configuration resolves its own
     * paths, and where it was taken from.
     *
     * @param directory the absolute project folder.
     * @param source    how it was found, for the message at startup.
     */
    public record ProjectFolder(File directory, String source) {
    }

    /**
     * Finds the project folder: the value of {@code -Devochora.home} if given, else the directory
     * of the installation the running JAR belongs to, else the working directory. An installation
     * is a directory whose {@code lib/} holds the JAR; an unpacked archive is one, and so is the
     * output of {@code installDist}. Classes run from a build or an IDE belong to none.
     *
     * @param homeProperty     the value of the system property {@code evochora.home}, or {@code null}.
     * @param codeLocation     the JAR or classes directory the loader was loaded from, or {@code null}.
     * @param workingDirectory the working directory of the process.
     * @return the project folder with its source.
     */
    static ProjectFolder detectProjectFolder(final String homeProperty, final File codeLocation,
                                             final File workingDirectory) {
        if (homeProperty != null && !homeProperty.isBlank()) {
            return new ProjectFolder(new File(homeProperty).getAbsoluteFile(), "-D" + HOME_KEY);
        }
        if (codeLocation != null && codeLocation.isFile()) {
            final File libDir = codeLocation.getAbsoluteFile().getParentFile();
            if (libDir != null && LIB_DIR.equals(libDir.getName()) && libDir.getParentFile() != null) {
                return new ProjectFolder(libDir.getParentFile(), "the installation");
            }
        }
        return new ProjectFolder(workingDirectory.getAbsoluteFile(), "the working directory");
    }

    /**
     * Resolves configuration using the standard 4-level fallback cascade:
     * <ol>
     *   <li><strong>Explicit file:</strong> config file passed via CLI {@code --config} option,
     *       relative to the working directory</li>
     *   <li><strong>System property:</strong> {@code -Dconfig.file} JVM argument, relative to the
     *       working directory</li>
     *   <li><strong>Project folder:</strong> {@code config/evochora.conf} in the folder
     *       {@link #detectProjectFolder} finds</li>
     *   <li><strong>Classpath defaults:</strong> {@code reference.conf} only</li>
     * </ol>
     * <p>
     * Whichever file is taken, the project folder is set as the system property
     * {@code evochora.home} unless it is given already, and a message names it and where it came
     * from. It is a system property rather than a layer of this loader because the configuration
     * library requires {@code reference.conf} to resolve on its own, and of the sources outside
     * that file it consults only the system properties.
     * <p>
     * At each level, system properties and environment variables still take precedence over
     * the discovered config file (see class-level documentation for the full priority hierarchy).
     *
     * <strong>Thread Safety:</strong> This method is not thread-safe. It is intended to be called
     * once during application startup from the main thread.
     *
     * @param explicitConfigFile config file from CLI option, or {@code null} for auto-discovery.
     * @param handler            callback for resolution progress messages (must not be {@code null}).
     * @return the fully resolved application {@link Config}.
     * @throws IllegalArgumentException                if an explicitly specified config file
     *                                                 (via parameter or {@code -Dconfig.file}) does not exist.
     * @throws com.typesafe.config.ConfigException     if the configuration cannot be parsed or resolved.
     */
    public static Config resolve(final File explicitConfigFile, final ConfigMessageHandler handler) {
        return resolve(explicitConfigFile, ConfigFactory.empty(), handler);
    }

    /**
     * Resolves configuration through the same cascade as
     * {@link #resolve(File, ConfigMessageHandler)}, with one additional layer on top of every
     * other source.
     * <p>
     * The overrides sit above the system properties in whichever branch of the cascade is taken,
     * so a value given there wins over the system properties, the environment, the user
     * configuration file and {@code reference.conf} alike. The overrides are merged before
     * substitutions are resolved, which has two consequences: a value they set is seen by every
     * {@code ${...}} reference to it further down (an override of {@code pipeline.runId} reaches
     * {@code pipeline.services.simulation-engine.options.resume.runId}), and the overrides may
     * themselves carry substitutions that point into the lower layers (setting
     * {@code pipeline.tuning = ${profiles.sampled}} selects a profile defined in
     * {@code reference.conf}).
     * <p>
     * The overrides must therefore be <em>unresolved</em>, as
     * {@link ConfigFactory#parseString(String)} returns them; a config that has already been
     * resolved carries no substitutions to resolve here.
     *
     * @param explicitConfigFile config file from CLI option, or {@code null} for auto-discovery.
     * @param overrides          the layer placed above every other source; empty for none.
     * @param handler            callback for resolution progress messages (must not be {@code null}).
     * @return the fully resolved application {@link Config}.
     * @throws IllegalArgumentException                if an explicitly specified config file
     *                                                 (via parameter or {@code -Dconfig.file}) does not exist.
     * @throws com.typesafe.config.ConfigException     if the configuration cannot be parsed or resolved.
     */
    public static Config resolve(final File explicitConfigFile, final Config overrides,
                                 final ConfigMessageHandler handler) {
        final ProjectFolder projectFolder = projectFolder();
        handler.log(MessageLevel.INFO, "Project folder: " + projectFolder.directory()
                + ", taken from " + projectFolder.source());

        // 1) Explicit CLI option --config
        if (explicitConfigFile != null) {
            if (!explicitConfigFile.exists()) {
                throw new IllegalArgumentException(
                        "Configuration file not found: " + explicitConfigFile.getAbsolutePath());
            }
            handler.log(MessageLevel.INFO,
                    "Using configuration file specified via --config: " + explicitConfigFile.getAbsolutePath());
            return loadFromFile(explicitConfigFile, overrides);
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
            return loadFromFile(systemConfigFile, overrides);
        }

        // 3) config/evochora.conf in the project folder
        final File projectConfigFile = new File(new File(projectFolder.directory(), CONFIG_DIR), CONFIG_FILE_NAME);
        if (projectConfigFile.exists()) {
            handler.log(MessageLevel.INFO,
                    "Using configuration file of the project folder: " + projectConfigFile.getAbsolutePath());
            return loadFromFile(projectConfigFile, overrides);
        }

        // 4) Fall back to classpath defaults only
        handler.log(MessageLevel.WARN,
                "No '" + CONFIG_DIR + "/" + CONFIG_FILE_NAME + "' found in the project folder. "
                        + "Using default configuration from classpath.");
        return loadDefaults(overrides);
    }

    /**
     * Loads configuration from a file, merged with classpath defaults.
     *
     * @param configFile the configuration file to load.
     * @return the fully resolved application {@link Config}.
     */
    static Config loadFromFile(final File configFile) {
        return loadFromFile(configFile, ConfigFactory.empty());
    }

    /**
     * Loads configuration from a file, merged with classpath defaults, with an override layer
     * above the system properties.
     *
     * @param configFile the configuration file to load.
     * @param overrides  the layer placed above every other source; empty for none.
     * @return the fully resolved application {@link Config}.
     */
    static Config loadFromFile(final File configFile, final Config overrides) {
        return overrides
            .withFallback(ConfigFactory.systemProperties())
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
        return loadDefaults(ConfigFactory.empty());
    }

    /**
     * Loads configuration from classpath defaults only (no user config file), with an override
     * layer above the system properties.
     *
     * @param overrides the layer placed above every other source; empty for none.
     * @return the fully resolved application {@link Config}.
     */
    static Config loadDefaults(final Config overrides) {
        return overrides
            .withFallback(ConfigFactory.systemProperties())
            .withFallback(ConfigFactory.systemEnvironment())
            .withFallback(ConfigFactory.defaultReferenceUnresolved())
            .resolve();
    }

    /**
     * The project folder of this process, set as the system property {@code evochora.home} if
     * it is not given. A property this loader set itself keeps the source it was found by.
     */
    private static synchronized ProjectFolder projectFolder() {
        final String given = System.getProperty(HOME_KEY);
        if (detected != null && detected.directory().getPath().equals(given)) {
            return detected;
        }
        final ProjectFolder folder = detectProjectFolder(given, codeLocation(), new File("").getAbsoluteFile());
        if (given == null || given.isBlank()) {
            System.setProperty(HOME_KEY, folder.directory().getPath());
            ConfigFactory.invalidateCaches();
            detected = folder;
        }
        return folder;
    }

    /**
     * The JAR or classes directory this class was loaded from.
     *
     * @return the location, or {@code null} if the runtime does not say.
     */
    private static File codeLocation() {
        try {
            final ProtectionDomain protectionDomain = ConfigLoader.class.getProtectionDomain();
            final CodeSource codeSource = protectionDomain == null ? null : protectionDomain.getCodeSource();
            final URL location = codeSource == null ? null : codeSource.getLocation();
            return location == null ? null : new File(location.toURI());
        } catch (Exception ignored) {
            // Without a location the working directory is the project folder.
            return null;
        }
    }
}
