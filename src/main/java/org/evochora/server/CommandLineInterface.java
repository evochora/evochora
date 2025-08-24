package org.evochora.server;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.server.config.ConfigLoader;
import org.evochora.server.config.SimulationConfiguration;
import org.evochora.server.engine.SimulationEngine;
import org.evochora.server.http.AnalysisWebService;
import org.evochora.server.persistence.PersistenceService;
import org.evochora.server.queue.ITickMessageQueue;
import org.evochora.server.queue.InMemoryTickQueue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.function.Function;
import java.util.stream.Collectors;

// TODO: This class needs a major refactoring to implement the full process control logic from the architecture plan.
// This is a temporary, minimal implementation to get the 'sim' command working.
public class CommandLineInterface {
    private static SimulationEngine sim;
    private static PersistenceService persist;
    private static AnalysisWebService web;
    private static Thread simThread;
    private static Thread persistThread;

    public static void main(String[] args) {
        System.err.println("Welcome to the Evochora server CLI.");
        // For now, we only implement a simplified 'sim' command logic directly here.
        // A full CLI implementation with Picocli will follow.

        // Default paths and configs
        String configPath = "src/main/resources/org/evochora/config/config.json";
        String rawDbPath = "build/sim_run_raw.sqlite";

        // 1. Load Configuration
        SimulationConfiguration config;
        try {
            config = ConfigLoader.loadConfiguration(configPath);
        } catch (IOException e) {
            System.err.println("Failed to load configuration file: " + configPath);
            e.printStackTrace();
            return;
        }

        // 2. Compile Programs
        System.err.println("Compiling programs...");
        Compiler compiler = new Compiler();
        final SimulationConfiguration finalConfig = config;
        Map<String, ProgramArtifact> artifacts;
        try {
            artifacts = Arrays.stream(finalConfig.organisms) // Use Arrays.stream for arrays
                    .map(orgDef -> orgDef.program)
                    .distinct()
                    .collect(Collectors.toMap(Function.identity(), path -> {
                        try {
                            // The compiler expects the file content, not just the path
                            List<String> lines = Files.readAllLines(Paths.get(path));
                            return compiler.compile(lines, path);
                        } catch (IOException | CompilationException e) {
                            throw new RuntimeException("Failed to compile " + path, e);
                        }
                    }));
        } catch (RuntimeException e) {
            System.err.println("A compilation error occurred.");
            e.printStackTrace();
            return;
        }
        System.err.printf("Successfully compiled %d program(s).%n", artifacts.size());

        // 3. Initialize Services
        ITickMessageQueue queue = new InMemoryTickQueue(); // Use the default constructor
        sim = new SimulationEngine(config, artifacts.values(), queue);
        persist = new PersistenceService(queue, rawDbPath);

        // 4. Persist Artifacts
        persist.persistProgramArtifacts(artifacts.values());

        // 5. Start Threads
        simThread = new Thread(sim);
        persistThread = new Thread(persist);
        simThread.start();
        persistThread.start();
        System.err.println("Simulation and Persistence services started.");
        System.err.println("Enter 'exit' to stop.");

        // 6. Wait for user to terminate
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                String line = scanner.nextLine();
                if ("exit".equalsIgnoreCase(line)) {
                    shutdown();
                    break;
                }
            }
        }
    }

    private static void shutdown() {
        System.err.println("Shutting down...");
        if (sim != null) sim.exit();
        if (persistThread != null) persistThread.interrupt(); // Interrupt to stop gracefully

        try {
            if (simThread != null) simThread.join(1000);
            if (persistThread != null) persistThread.join(1000);
        } catch (InterruptedException e) {
            System.err.println("Interrupted during shutdown.");
            Thread.currentThread().interrupt();
        }
        System.err.println("Shutdown complete.");
    }
}
