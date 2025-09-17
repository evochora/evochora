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
    name = "datapipeline",
    mixinStandardHelpOptions = true,
    version = "Evochora Data Pipeline 1.0",
    description = "Manages and runs the Evochora data pipeline services.",
    subcommands = { CompileCommand.class, CommandLine.HelpCommand.class }
)
public class CommandLineInterface implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(CommandLineInterface.class);

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
        log.info("Data Pipeline CLI started.");

        Config finalConfig = loadConfiguration();
        log.info("Configuration loaded. Initializing ServiceManager...");

        ServiceManager serviceManager = new ServiceManager(finalConfig);

        // Add a shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received. Stopping services...");
            serviceManager.stopAll();
            log.info("All services stopped.");
        }));

        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .history(new DefaultHistory())
                .build();

        String prompt = "datapipeline> ";
        while (true) {
            String line;
            try {
                line = lineReader.readLine(prompt);
                if (line == null) {
                    break;
                }

                String[] parts = line.trim().split("\\s+");
                String command = parts[0].toLowerCase();

                switch (command) {
                    case "start":
                        if (parts.length > 1) {
                            serviceManager.startService(parts[1]);
                        } else {
                            serviceManager.startAll();
                        }
                        break;
                    case "stop":
                        serviceManager.stopAll();
                        break;
                    case "pause":
                        if (parts.length > 1) {
                            serviceManager.pauseService(parts[1]);
                        } else {
                            serviceManager.pauseAll();
                        }
                        break;
                    case "resume":
                        if (parts.length > 1) {
                            serviceManager.resumeService(parts[1]);
                        } else {
                            serviceManager.resumeAll();
                        }
                        break;
                    case "status":
                        System.out.println(serviceManager.getStatus());
                        break;
                    case "help":
                        printHelp();
                        break;
                    case "exit":
                        serviceManager.stopAll();
                        return 0;
                    default:
                        log.warn("Unknown command: {}. Type 'help' for a list of commands.", command);
                        break;
                }
            } catch (UserInterruptException e) {
                // Ctrl+C
                serviceManager.stopAll();
                return 0;
            } catch (EndOfFileException e) {
                // Ctrl+D
                serviceManager.stopAll();
                return 0;
            }
        }
        return 0;
    }

    private void printHelp() {
        System.out.println("Available commands:");
        System.out.println("  start [service] - Start all services or a specific service.");
        System.out.println("  stop            - Stop all services.");
        System.out.println("  pause [service] - Pause all services or a specific service.");
        System.out.println("  resume [service]- Resume all services or a specific service.");
        System.out.println("  status          - Show the status of all services.");
        System.out.println("  help            - Show this help message.");
        System.out.println("  exit            - Stop all services and exit the application.");
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
     * If no subcommand is specified, it enters interactive mode.
     * </p>
     *
     * @param args The command-line arguments passed to the application.
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new CommandLineInterface()).execute(args);
        // Don't exit if we are in interactive mode (exit code 0 from call())
        // Picocli will exit automatically for subcommands.
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
