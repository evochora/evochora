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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Simple CLI to control the Evochora simulation pipeline.
 */
public final class CommandLineInterface {

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
            CommandLineInterface cli = new CommandLineInterface();
            
            // Check if we have command line arguments (batch mode)
            if (args.length > 0) {
                cli.runBatch(args);
            } else {
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
        this.queue = new InMemoryTickQueue();
        if (this.cfg == null) {
            this.cfg = ConfigLoader.loadDefault();
        }
        this.serviceManager = new ServiceManager(queue, cfg);

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Evochora CLI ready. Commands: start | pause | resume | status | exit");
        System.out.println("  start [service] - start all services or specific service");
        System.out.println("  pause [service] - pause all services or specific service");
        System.out.println("  resume [service] - resume all services or specific service");
        System.out.println("  status - show status of all services");
        System.out.println("  exit - shutdown and exit");

        while (true) {
            System.err.print(">>> ");
            String line = reader.readLine();
            if (line == null) break;
            String cmd = line.trim();
            
            if (cmd.equalsIgnoreCase("start")) {
                System.out.println("Starting all services...");
                serviceManager.startAll();
                System.out.println("All services started successfully");
                
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
                System.out.println("Service Status:");
                System.out.println(serviceManager.getStatus());
                
            } else if (cmd.equalsIgnoreCase("exit") || cmd.equalsIgnoreCase("quit")) {
                break;
                
            } else if (cmd.isEmpty()) {
                continue;
            } else {
                System.out.println("Unknown command. Available: start [service] | pause [service] | resume [service] | status | exit");
            }
        }

        // Cleanup
        System.out.println("Shutting down services...");
        serviceManager.stopAll();
        System.out.println("CLI shutdown complete");
    }
}