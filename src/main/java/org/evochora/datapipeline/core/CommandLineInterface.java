package org.evochora.datapipeline.core;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
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
    name = "evochora",
    mixinStandardHelpOptions = true,
    version = "Evochora Data Pipeline 1.0",
    description = "The command center for the Evochora Data Pipeline.",
    subcommands = { org.evochora.datapipeline.core.cli.RunCommand.class, org.evochora.datapipeline.core.cli.CompileCommand.class, CommandLine.HelpCommand.class }
)
public class CommandLineInterface implements Callable<Integer> {
    
    // Static initializer to set logging format BEFORE any loggers are created
    static {
        setLoggingFormatFromDefaultConfig();
    }

    private static final Logger log = LoggerFactory.getLogger(CommandLineInterface.class);

    @Option(names = {"-c", "--config"}, description = "Path to the HOCON configuration file.")
    File configFile;

    @Option(names = "-D", mapFallbackValue = "", description = "Override a HOCON configuration value. For example: -Dpipeline.services.simulation.enabled=false")
    Map<String, String> hoconOverrides;
    
    @Option(names = "--headless", description = "Starts the application in non-interactive (headless) mode.")
    boolean headless;
    
    @Option(names = "--service", description = "When in headless mode, starts only this specific service. This is a convenience for containerized deployments.")
    String serviceName;

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
        // Display Evochora logo FIRST (only in interactive mode, not headless)
        if (!headless) {
            System.out.println();
            System.out.println("  ________      ______   _____ _    _  ____  _____            ");
            System.out.println(" |  ____\\ \\    / / __ \\ / ____| |  | |/ __ \\|  __ \\     /\\    ");
            System.out.println(" | |__   \\ \\  / / |  | | |    | |__| | |  | | |__) |   /  \\   ");
            System.out.println(" |  __|   \\ \\/ /| |  | | |    |  __  | |  | |  _  /   / /\\ \\  ");
            System.out.println(" | |____   \\  / | |__| | |____| |  | | |__| | | \\ \\  / ____ \\ ");
            System.out.println(" |______|   \\/   \\____/ \\_____|_|  |_|\\____/|_|  \\_\\/_/    \\_\\");
            System.out.println();
            System.out.println("           Advanced Evolutionary Simulation Platform");
            System.out.println();
        }
        
        // Default behavior: run the pipeline (equivalent to "evochora run")
        org.evochora.datapipeline.core.cli.RunCommand runCommand = new org.evochora.datapipeline.core.cli.RunCommand();
        runCommand.configFile = configFile;
        runCommand.hoconOverrides = hoconOverrides;
        runCommand.headless = headless;
        runCommand.serviceName = serviceName;
        return runCommand.call();
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

    
    private static String findConfigFileFromArgs(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--config".equals(args[i]) || "-c".equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }
    
    private static void setLoggingFormatFromDefaultConfig() {
        try {
            // Try to load default evochora.conf file
            java.io.File configFile = new java.io.File("evochora.conf");
            
            if (configFile.exists()) {
                com.typesafe.config.Config config = com.typesafe.config.ConfigFactory.parseFile(configFile)
                        .withFallback(com.typesafe.config.ConfigFactory.defaultReference())
                        .resolve();
                
                if (config.hasPath("logging.format")) {
                    String format = config.getString("logging.format");
                    System.setProperty("evochora.logging.format", 
                        "plain".equalsIgnoreCase(format) ? "STDOUT_PLAIN" : "STDOUT");
                }
            }
        } catch (Exception e) {
            // Ignore config loading errors, use default
        }
    }
    
    private static void setLoggingFormatFromConfig(String configFilePath) {
        try {
            // Load config file
            java.io.File configFile = configFilePath != null ? 
                new java.io.File(configFilePath) : 
                new java.io.File("evochora.conf");
            
            if (configFile.exists()) {
                com.typesafe.config.Config config = com.typesafe.config.ConfigFactory.parseFile(configFile)
                        .withFallback(com.typesafe.config.ConfigFactory.defaultReference())
                        .resolve();
                
                if (config.hasPath("logging.format")) {
                    String format = config.getString("logging.format");
                    System.setProperty("evochora.logging.format", 
                        "plain".equalsIgnoreCase(format) ? "STDOUT_PLAIN" : "STDOUT");
                }
            }
        } catch (Exception e) {
            // Ignore config loading errors, use default
        }
    }
    
    /**
     * The main entry point for the Evochora Data Pipeline CLI.
     * <p>
     * This method initializes the command-line interface and handles the execution
     * of the application. It sets up logging configuration early and delegates
     * to the picocli framework for argument parsing and command execution.
     *
     * @param args Command-line arguments passed to the application.
     */
    public static void main(String[] args) {
        // CRITICAL: Set logging format BEFORE any logger classes are loaded
        // Parse command line to find config file
        String configFilePath = findConfigFileFromArgs(args);
        setLoggingFormatFromConfig(configFilePath);
        
        // Parse command line arguments and execute
        int exitCode = new CommandLine(new CommandLineInterface()).execute(args);
        System.exit(exitCode);
    }
    
}
