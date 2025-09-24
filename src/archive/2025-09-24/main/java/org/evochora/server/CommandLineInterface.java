package org.evochora.server;

import org.evochora.server.config.ConfigLoader;
import org.evochora.server.config.SimulationConfiguration;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.internal.LinearizedProgramArtifact;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.isa.Instruction;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonLocation;

/**
 * Simple CLI to control the Evochora simulation pipeline.
 */
public final class CommandLineInterface {

    private static final Logger log = LoggerFactory.getLogger(CommandLineInterface.class);
    
    /**
     * Formats a JSON parsing exception into a user-friendly error message.
     * 
     * @param e the exception
     * @param configPath the path to the config file
     * @return a formatted error message
     */
    private static String formatConfigError(Exception e, String configPath) {
        // If the exception message already contains our formatted error, use it directly
        String message = e.getMessage();
        if (message != null && message.contains("Invalid JSON in")) {
            return message;
        }
        
        // For other exceptions, just return a simple message
        return "Failed to load '" + configPath + "': " + message;
    }
    
    private ITickMessageQueue queue;
    private SimulationConfiguration cfg;
    private ServiceManager serviceManager;

    public CommandLineInterface() {
        // Default constructor
    }

    public CommandLineInterface(SimulationConfiguration config) {
        this.cfg = config;
    }

    public static void main(String[] args) {
        // Suppress SLF4J replay warnings
        System.setProperty("slf4j.replay.warn", "false");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        
        try {
            // Check if we have command line arguments
            if (args.length > 0) {
                // Check if first argument is --config (for interactive mode only)
                if (args[0].equals("--config")) {
                    if (args.length != 2) {
                        System.err.println("Error: --config requires exactly one file path");
                        System.err.println("Usage: java -jar evochora.jar --config <config-file>");
                        System.exit(2);
                    }
                    // Store config path and run in interactive mode
                    System.setProperty("evochora.config.path", args[1]);
                    CommandLineInterface cli = new CommandLineInterface();
                    cli.run();
                } else {
                    // Batch mode with other commands (like compile)
                    // Check if --config appears anywhere in batch mode arguments
                    for (String arg : args) {
                        if (arg.equals("--config")) {
                            System.err.println("Error: --config is only supported in interactive mode");
                            System.err.println("Usage: java -jar evochora.jar --config <config-file>");
                            System.exit(2);
                        }
                    }
                    CommandLineInterface cli = new CommandLineInterface();
                    cli.runBatch(args);
                }
            } else {
                // Interactive mode without arguments
                CommandLineInterface cli = new CommandLineInterface();
                cli.run();
            }
        } catch (Exception e) {
            System.err.println("CLI error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    
    /**
     * Runs the CLI in batch mode with command line arguments.
     * 
     * @param args Command line arguments
     * @throws Exception if an error occurs
     */
    public void runBatch(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("No command specified");
            System.exit(2);
        }
        
        String command = args[0].toLowerCase();
        
        switch (command) {
            case "compile":
                if (args.length < 2) {
                    System.err.println("Usage: compile <file> [--env=<dimensions>[:<toroidal>]]");
                    System.err.println("  --env=<dimensions>[:<toroidal>]  Environment (e.g., 1000x1000:toroidal or 1000x1000:flat)");
                    System.err.println("  toroidal defaults to true if not specified");
                    System.exit(2);
                }
                runCompile(args);
                break;
                
            default:
                System.err.println("Unknown command: " + command);
                System.err.println("Available commands: compile");
                System.exit(2);
        }
    }
    
    /**
     * Compiles an assembly file and outputs ProgramArtifact as JSON.
     * 
     * @param args Command line arguments (file path and optional --world parameter)
     * @throws Exception if compilation fails
     */
    private void runCompile(String[] args) throws Exception {
        String filePath = args[1];
        EnvironmentProperties envProps = parseEnvironmentProperties(args);
        try {
            // Check if file exists
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                System.err.println("Error: File not found: " + filePath);
                System.exit(2);
            }
            
            // Read source file
            List<String> sourceLines = Files.readAllLines(path);
            
            // Initialize instruction set
            Instruction.init();
            
            // Create compiler
            Compiler compiler = new Compiler();
            
            // Use parsed environment properties
            
            // Compile the source
            ProgramArtifact artifact = compiler.compile(sourceLines, filePath, envProps);
            
            // Convert to linearized version for JSON serialization
            LinearizedProgramArtifact linearized = artifact.toLinearized(envProps);
            
            // Serialize to JSON
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(linearized);
            
            // Output JSON to stdout
            System.out.println(json);
            
        } catch (CompilationException e) {
            // Compilation errors go to stderr
            System.err.println("Compilation error:");
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            // Other errors go to stderr
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Parses environment properties from command line arguments.
     * 
     * @param args Command line arguments
     * @return EnvironmentProperties object
     */
    private EnvironmentProperties parseEnvironmentProperties(String[] args) {
        // Default: 1000x1000, toroidal
        int[] defaultDimensions = {1000, 1000};
        boolean defaultToroidal = true;
        
        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith("--env=")) {
                String envSpec = args[i].substring(6); // Remove "--env="
                try {
                    return parseEnvironmentSpec(envSpec);
                } catch (Exception e) {
                    System.err.println("Error parsing environment: " + e.getMessage());
                    System.err.println("Expected format: 1000x1000:toroidal or 1000x1000:flat");
                    System.exit(2);
                }
            }
        }
        
        return new EnvironmentProperties(defaultDimensions, defaultToroidal);
    }
    
    /**
     * Parses environment specification string.
     * 
     * @param envSpec Environment specification (e.g., "1000x1000:toroidal" or "1000x1000:flat")
     * @return EnvironmentProperties object
     * @throws IllegalArgumentException if format is invalid
     */
    private EnvironmentProperties parseEnvironmentSpec(String envSpec) {
        String[] parts = envSpec.split(":");
        if (parts.length < 1 || parts.length > 2) {
            throw new IllegalArgumentException("Environment specification must be <dimensions>[:<toroidal>]");
        }
        
        // Parse dimensions
        String[] dimensionParts = parts[0].split("x");
        if (dimensionParts.length < 2) {
            throw new IllegalArgumentException("Dimensions must have at least 2 parts (e.g., 1000x1000)");
        }
        
        int[] dimensions = new int[dimensionParts.length];
        for (int i = 0; i < dimensionParts.length; i++) {
            try {
                dimensions[i] = Integer.parseInt(dimensionParts[i]);
                if (dimensions[i] <= 0) {
                    throw new IllegalArgumentException("Dimensions must be positive");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid dimension: " + dimensionParts[i]);
            }
        }
        
        // Parse toroidal (default: true)
        boolean toroidal = true;
        if (parts.length == 2) {
            String toroidalSpec = parts[1].toLowerCase();
            if ("toroidal".equals(toroidalSpec)) {
                toroidal = true;
            } else if ("flat".equals(toroidalSpec)) {
                toroidal = false;
            } else {
                throw new IllegalArgumentException("Toroidal must be 'toroidal' or 'flat', got: " + parts[1]);
            }
        }
        
        return new EnvironmentProperties(dimensions, toroidal);
    }
    
    public void run() throws Exception {
        // ASCII Art Logo and scientific claim - show FIRST before any service logs
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
        
        if (this.cfg == null) {
            // First, configure logging with fallback configuration
            SimulationConfiguration fallbackConfig = ConfigLoader.loadDefaultWithFallback();
            ServiceManager.applyLoggingConfiguration(fallbackConfig, "fallback");
            
            // Now try to load the real config
            String configPath = System.getProperty("evochora.config.path");
            
            if (configPath != null) {
                // Custom config file specified
                log.debug("Trying to load custom config file: {}", configPath);
                try {
                    this.cfg = ConfigLoader.loadFromFile(Path.of(configPath));
                    log.info("Successfully loaded config file: {}", configPath);
                    // Apply logging configuration from the real config
                    ServiceManager.applyLoggingConfiguration(this.cfg);
                } catch (Exception e) {
                    log.error(formatConfigError(e, configPath));
                    log.info("Using fallback configuration");
                    this.cfg = fallbackConfig;
                }
            } else {
                // Use default config file
                log.debug("Trying to load default config file from resources");
                try {
                    this.cfg = ConfigLoader.loadDefault();
                    log.info("Successfully loaded default config file from resources: org/evochora/config/config.jsonc");
                    // Apply logging configuration from the real config
                    ServiceManager.applyLoggingConfiguration(this.cfg);
                } catch (Exception e) {
                    String errorMessage = formatConfigError(e, "org/evochora/config/config.jsonc");
                    log.error(errorMessage);
                    log.info("Using fallback configuration");
                    this.cfg = fallbackConfig;
                }
            }
        }
        
        // Create queue with configured message count limit
        int maxMessageCount = 10000; // 10000 messages default
        if (this.cfg.pipeline != null && this.cfg.pipeline.simulation != null) {
            if (this.cfg.pipeline.simulation.maxMessageCount != null) {
                maxMessageCount = this.cfg.pipeline.simulation.maxMessageCount;
            }
        }
        
        this.queue = new InMemoryTickQueue(maxMessageCount);
        
        this.serviceManager = new ServiceManager(queue, cfg);

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Evochora CLI ready. Commands: start | pause | resume | status | help | exit");

        while (true) {
            System.err.print(">>> ");
            String line = reader.readLine();
            if (line == null) break;
            String cmd = line.trim();
            
            if (cmd.equalsIgnoreCase("start")) {
                System.out.println("Starting all services...");
                serviceManager.startAll();
                // Don't print success message - ServiceManager logs the actual status
                
            } else if (cmd.startsWith("start ")) {
                String serviceName = cmd.substring(6).trim().toLowerCase();
                System.out.println("Starting " + serviceName + "...");
                serviceManager.startService(serviceName);
                
            } else if (cmd.equalsIgnoreCase("pause")) {
                System.out.println("Pausing all services...");
                serviceManager.pauseAll();
                System.out.println("All services paused");
                
            } else if (cmd.startsWith("pause ")) {
                String serviceName = cmd.substring(6).trim().toLowerCase();
                System.out.println("Pausing " + serviceName + "...");
                serviceManager.pauseService(serviceName);
                
            } else if (cmd.equalsIgnoreCase("resume")) {
                System.out.println("Resuming all services...");
                serviceManager.resumeAll();
                System.out.println("All services resumed");
                
            } else if (cmd.startsWith("resume ")) {
                String serviceName = cmd.substring(6).trim().toLowerCase();
                System.out.println("Resuming " + serviceName + "...");
                serviceManager.resumeService(serviceName);
                
            } else if (cmd.equalsIgnoreCase("status")) {
                System.out.println(serviceManager.getStatus());
                
            } else if (cmd.startsWith("loglevel ")) {
                String[] parts = cmd.substring(9).trim().split("\\s+");
                if (parts.length == 1) {
                    String level = parts[0].toUpperCase();
                    if (level.equals("RESET")) {
                        serviceManager.resetLogLevels();
                        System.out.println("Log levels reset to config.jsonc values");
                    } else if (serviceManager.isValidLogLevel(level)) {
                        serviceManager.setDefaultLogLevel(level);
                    } else {
                        System.out.println("Invalid log level: " + level);
                        System.out.println("Valid levels: TRACE, DEBUG, INFO, WARN, ERROR");
                    }
                } else if (parts.length == 2) {
                    // loglevel sim INFO (specific logger)
                    String loggerAlias = parts[0].toLowerCase();
                    String level = parts[1].toUpperCase();
                    if (serviceManager.isValidLogLevel(level)) {
                        serviceManager.setLogLevel(loggerAlias, level);
                    } else {
                        System.out.println("Invalid log level: " + level);
                        System.out.println("Valid levels: TRACE, DEBUG, INFO, WARN, ERROR");
                    }
                } else {
                    System.out.println("Usage: loglevel [logger] [level] or loglevel reset");
                    System.out.println("Examples: loglevel DEBUG, loglevel sim INFO, loglevel cli WARN, loglevel reset");
                }
            } else if (cmd.equalsIgnoreCase("loglevel")) {
                // Show current log levels
                System.out.println("Current log levels:");
                System.out.printf("%-12s %s%n", "Logger", "Level");
                System.out.printf("%-12s %s%n", "------", "-----");
                System.out.printf("%-12s %s%n", "default", serviceManager.getCurrentLogLevel("default"));
                System.out.printf("%-12s %s%n", "sim", serviceManager.getCurrentLogLevel("sim"));
                System.out.printf("%-12s %s%n", "persist", serviceManager.getCurrentLogLevel("persist"));
                System.out.printf("%-12s %s%n", "indexer", serviceManager.getCurrentLogLevel("indexer"));
                System.out.printf("%-12s %s%n", "web", serviceManager.getCurrentLogLevel("web"));
                System.out.printf("%-12s %s%n", "cli", serviceManager.getCurrentLogLevel("cli"));
                
            } else if (cmd.equalsIgnoreCase("help")) {
                System.out.println("Evochora CLI Commands:");
                System.out.println();
                System.out.printf("%-18s %s%n", "  start [service]", "Start all services or a specific service");
                System.out.printf("%-18s %s%n", "", "Available services: sim, persist, indexer, web");
                System.out.println();
                System.out.printf("%-18s %s%n", "  pause [service]", "Pause all services or a specific service");
                System.out.printf("%-18s %s%n", "", "Services will stop processing but maintain their state");
                System.out.println();
                System.out.printf("%-18s %s%n", "  resume [service]", "Resume all services or a specific service");
                System.out.printf("%-18s %s%n", "", "Services will continue from where they were paused");
                System.out.println();
                System.out.printf("%-18s %s%n", "  status", "Show current status of all services");
                System.out.printf("%-18s %s%n", "", "Displays running state, health, and configuration");
                System.out.println();
                System.out.printf("%-18s %s%n", "  loglevel", "Show current log levels for all loggers");
                System.out.printf("%-18s %s%n", "  loglevel [level]", "Set default log level (TRACE, DEBUG, INFO, WARN, ERROR)");
                System.out.printf("%-18s %s%n", "  loglevel [logger] [level]", "Set log level for specific logger");
                System.out.printf("%-18s %s%n", "", "Available loggers: sim, persist, indexer, web, cli");
                System.out.printf("%-18s %s%n", "  loglevel reset", "Reset all log levels to config.jsonc values");
                System.out.println();
                System.out.printf("%-18s %s%n", "  help", "Show this detailed help information");
                System.out.println();
                System.out.printf("%-18s %s%n", "  exit", "Shutdown all services and exit the CLI");
                
            } else if (cmd.equalsIgnoreCase("exit") || cmd.equalsIgnoreCase("quit")) {
                break;
                
            } else if (cmd.isEmpty()) {
                continue;
            } else {
                System.out.println("Unknown command. Type 'help' for detailed information or use: start | pause | resume | status | exit");
            }
        }

        // Cleanup
        System.out.println("Shutting down services...");
        serviceManager.stopAll();
    }
}