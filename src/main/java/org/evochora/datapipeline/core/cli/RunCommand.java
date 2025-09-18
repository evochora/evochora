package org.evochora.datapipeline.core.cli;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.core.ServiceManager;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The run command for starting the Evochora data pipeline.
 * <p>
 * This command loads the pipeline configuration, initializes the ServiceManager,
 * and either starts the pipeline in interactive mode or headless mode.
 * </p>
 */
@Command(
    name = "run",
    mixinStandardHelpOptions = true,
    description = "Runs the data pipeline."
)
public class RunCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(RunCommand.class);

    @Option(names = {"-c", "--config"}, description = "Path to the HOCON configuration file. Default: ./evochora.conf")
    public File configFile;

    @Option(names = "--headless", description = "Starts the application in non-interactive (headless) mode.")
    public boolean headless;

    @Option(names = "--service", description = "When in headless mode, starts only this specific service. This is a convenience for containerized deployments.")
    public String serviceName;

    @Option(names = "-D", mapFallbackValue = "", description = "Override a HOCON configuration value. For example: -Dpipeline.services.simulation.enabled=false")
    public Map<String, String> hoconOverrides;

    @Override
    public Integer call() throws Exception {
        // Note: Logging format is already configured in CommandLineInterface.main()
        Config finalConfig = loadConfiguration();
        
        // Output configuration file (after logo)
        String configFilePath = configFile != null ? 
            configFile.getAbsolutePath() : 
            "evochora.conf (default)";
        System.out.println("Using configuration file: " + configFilePath);
        
        log.info("Data Pipeline CLI started.");
        log.info("Configuration loaded. Initializing ServiceManager...");

        ServiceManager serviceManager = new ServiceManager(finalConfig);

        // Set up graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            serviceManager.stopAll();
        }));

        if (headless) {
            return runHeadless(serviceManager);
        } else {
            return runInteractive(serviceManager);
        }
    }

    private Integer runHeadless(ServiceManager serviceManager) {
        if (serviceName != null) {
            log.info("Starting service in headless mode: {}", serviceName);
            serviceManager.startService(serviceName);
        } else {
            serviceManager.startAll();
        }

        // Keep the main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Interrupted, shutting down...");
            serviceManager.stopAll();
        }
        return 0;
    }

    private Integer runInteractive(ServiceManager serviceManager) throws Exception {
        log.info("Starting pipeline in interactive mode...");
        serviceManager.startAll();

        Terminal terminal;
        try {
            // Temporarily suppress JLine warnings
            java.util.logging.Logger jlineLogger = java.util.logging.Logger.getLogger("org.jline");
            java.util.logging.Level originalLevel = jlineLogger.getLevel();
            jlineLogger.setLevel(java.util.logging.Level.SEVERE);
            
            terminal = TerminalBuilder.builder()
                    .system(true)
                    .jna(true)
                    .jansi(true)
                    .build();
            
            // Restore original log level
            jlineLogger.setLevel(originalLevel);
        } catch (Exception e) {
            // Fallback to dumb terminal if system terminal is not available (e.g., in IntelliJ)
            terminal = TerminalBuilder.builder()
                    .dumb(true)
                    .build();
        }

        LineReader lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .history(new DefaultHistory())
                .build();

        String prompt = "evochora> ";
        while (true) {
            String line;
            try {
                line = lineReader.readLine(prompt);
                if (line == null) {
                    break;
                }

                String[] parts = line.trim().split("\\s+");
                String command = parts[0].toLowerCase();

                // Skip empty commands (just pressing Enter)
                if (command.isEmpty()) {
                    continue;
                }

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
                    case "quit":
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

    private Config loadConfiguration() {
        // Apply CLI overrides if provided
        if (hoconOverrides != null && !hoconOverrides.isEmpty()) {
            for (Map.Entry<String, String> entry : hoconOverrides.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            }
        }
        
        // Load from file if specified, otherwise look for default evochora.conf
        File configToLoad = configFile;
        if (configToLoad == null) {
            // Default to ./evochora.conf if no config file specified
            configToLoad = new File("evochora.conf");
        }
        
        Config config;
        if (configToLoad.exists()) {
            config = ConfigFactory.parseFile(configToLoad)
                    .withFallback(ConfigFactory.defaultReference())
                    .resolve();
        } else {
            // Fallback to reference.conf if no evochora.conf found
            config = ConfigFactory.defaultReference().resolve();
        }
        
        // Logging format is handled by system property set in CommandLineInterface.main()
        
        return config;
    }


    private void printHelp() {
        System.out.println("Available commands:");
        System.out.println("  start [serviceName]  - Start all services or a specific service");
        System.out.println("  stop                 - Stop all services and exit");
        System.out.println("  pause [serviceName] - Pause all services or a specific service");
        System.out.println("  resume [serviceName] - Resume all services or a specific service");
        System.out.println("  status               - Show status of all services");
        System.out.println("  help                 - Show this help");
        System.out.println("  exit/quit            - Stop all services and exit");
    }
}
