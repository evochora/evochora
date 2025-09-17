package org.evochora.datapipeline.core;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The main command-line interface for the Evochora data pipeline.
 * <p>
 * This class is responsible for parsing command-line arguments, loading the pipeline
 * configuration, and managing the lifecycle of the {@link ServiceManager}. It uses the
 * {@code picocli} library to handle command-line parsing.
 * </p>
 * <p>
 * The CLI supports loading a configuration file and overriding specific settings via
 * command-line arguments, providing a flexible way to run the data pipeline.
 * </p>
 */
@Command(
    name = "datapipeline",
    mixinStandardHelpOptions = true,
    version = "Evochora Data Pipeline 1.0",
    description = "Manages and runs the Evochora data pipeline services."
)
public class CommandLineInterface implements Callable<Integer> {

    @Option(names = {"-c", "--config"}, description = "Path to the HOCON configuration file.")
    File configFile;

    @Option(names = "-D", mapFallbackValue = "", description = "Override a HOCON configuration value. For example: -Dpipeline.services.simulation.enabled=false")
    Map<String, String> hoconOverrides;

    /**
     * The main execution method for the command-line interface, called by picocli.
     * <p>
     * This method orchestrates the entire application lifecycle:
     * <ol>
     *     <li>Loads the configuration based on defaults, file, and CLI arguments.</li>
     *     <li>Instantiates the {@link ServiceManager} with the final configuration.</li>
     *     <li>Sets up a graceful shutdown hook to stop services on exit.</li>
     *     <li>Starts all configured services.</li>
     *     <li>Keeps the main thread alive while the services are running.</li>
     * </ol>
     *
     * @return The exit code of the application. Typically 0 for successful execution.
     * @throws Exception if an unrecoverable error occurs during pipeline execution.
     */
    @Override
    public Integer call() throws Exception {
        System.out.println("Data Pipeline CLI started.");

        Config finalConfig = loadConfiguration();
        System.out.println("Configuration loaded. Initializing ServiceManager...");

        ServiceManager serviceManager = new ServiceManager(finalConfig);

        // Add a shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown signal received. Stopping services...");
            serviceManager.stopAll();
            System.out.println("All services stopped.");
        }));

        System.out.println("Starting services...");
        serviceManager.startAll();
        System.out.println("All services started. The application is now running.");
        System.out.println("Press Ctrl+C to exit.");

        // Keep the main thread alive
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return 0;
    }

    /**
     * Loads and merges configurations from various sources.
     * <p>
     * The method adheres to the following precedence order (highest to lowest):
     * <ol>
     *     <li>Environment variables</li>
     *     <li>Command-line arguments ({@code -Dkey=value})</li>
     *     <li>User-provided HOCON configuration file</li>
     *     <li>Hardcoded defaults from {@code reference.conf}</li>
     * </ol>
     * This ensures that users have multiple, cascading options for configuration.
     *
     * @return The fully resolved and merged {@link Config} object.
     */
    Config loadConfiguration() {
        // Precedence: Hardcoded Defaults -> File -> CLI Arguments -> Environment Variables
        // `withFallback` means the object calling it has precedence.
        // So we build from lowest to highest precedence.

        // 1. Hardcoded defaults in reference.conf
        Config defaultConfig = ConfigFactory.parseResources("reference.conf");

        // 2. User-provided config file
        Config fileConfig = configFile != null
                ? ConfigFactory.parseFile(configFile)
                : ConfigFactory.empty();

        // 3. CLI -D arguments
        Config cliConfig = hoconOverrides != null
                ? ConfigFactory.parseMap(hoconOverrides)
                : ConfigFactory.empty();

        // 4. Environment variables
        // This is a simplified approach. A real implementation might need to
        // map env var names (e.g., PIPELINE_SERVICES_SIM_OPT_ORGANISM) to HOCON paths.
        // For now, we rely on the standard `config.resolve()` which can handle
        // substitutions from env vars, e.g., `key = ${?MY_ENV_VAR}`.
        Config envConfig = ConfigFactory.systemEnvironment();


        // Combine them in the correct order of precedence.
        return envConfig
                .withFallback(cliConfig)
                .withFallback(fileConfig)
                .withFallback(defaultConfig)
                .resolve(); // resolve substitutions
    }

    /**
     * The main entry point for the CLI application.
     * <p>
     * This method initializes and executes the picocli command-line parser.
     * The exit code of the application is determined by the return value of the
     * {@link #call()} method.
     * </p>
     *
     * @param args The command-line arguments passed to the application.
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new CommandLineInterface()).execute(args);
        System.exit(exitCode);
    }
}
