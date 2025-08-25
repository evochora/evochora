package org.evochora.server;

import org.evochora.runtime.isa.Instruction;
import org.evochora.server.engine.SimulationEngine;
import org.evochora.server.config.ConfigLoader;
import org.evochora.server.config.SimulationConfiguration;
import org.evochora.server.persistence.PersistenceService;
import org.evochora.server.indexer.DebugIndexer;
import org.evochora.server.http.DebugServer;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.server.engine.StatusMetricsRegistry;

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

    public static void main(String[] args) {
        // Suppress SLF4J replay warnings
        System.setProperty("slf4j.replay.warn", "false");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        
        try {
            CommandLineInterface cli = new CommandLineInterface();
            cli.run();
        } catch (Exception e) {
            System.err.println("CLI error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    public void run() throws Exception {
        this.queue = new InMemoryTickQueue();
        this.cfg = ConfigLoader.loadDefault();
        
        // Service references for status and shutdown
        final SimulationEngine[] simRef = new SimulationEngine[1];
        final PersistenceService[] persistRef = new PersistenceService[1];
        final DebugIndexer[] indexerRef = new DebugIndexer[1];
        final DebugServer[] serverRef = new DebugServer[1];

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Evochora CLI ready. Commands: start | pause | status | exit");

        while (true) {
            System.err.print(">>> ");
            String line = reader.readLine();
            if (line == null) break;
            String cmd = line.trim();
            
            if (cmd.equalsIgnoreCase("start")) {
                // Check if services are already running but paused
                if (simRef[0] != null && simRef[0].isRunning() && simRef[0].isPaused()) {
                    System.out.println("Resuming simulation...");
                    simRef[0].resume();
                    
                    // Resume other services if they were paused
                    if (persistRef[0] != null && persistRef[0].isPaused()) {
                        System.out.println("Resuming persistence service...");
                        persistRef[0].resume();
                    }
                    if (indexerRef[0] != null && indexerRef[0].isPaused()) {
                        System.out.println("Resuming debug indexer...");
                        indexerRef[0].resume();
                    }
                    
                    System.out.println("Pipeline resumed successfully");
                    continue;
                }
                
                // Check if services are already running
                if (simRef[0] != null && simRef[0].isRunning()) {
                    System.out.println("Pipeline is already running. Use 'pause' to pause or 'exit' to shutdown.");
                    continue;
                }
                
                // Start services in separate threads
                Thread simThread = new Thread(() -> {
                    simRef[0] = new SimulationEngine(this.queue, this.cfg.simulation.environment.shape, this.cfg.simulation.environment.toroidal);
                    simRef[0].setSeed(this.cfg.simulation.seed);
                    simRef[0].setOrganismDefinitions(this.cfg.simulation.organisms);
                    simRef[0].setEnergyStrategies(this.cfg.simulation.energyStrategies);
                    simRef[0].start();
                }, "SimulationEngine");
                
                Thread persistThread = new Thread(() -> {
                    int batchSize = this.cfg.pipeline.persistence != null ? this.cfg.pipeline.persistence.batchSize : 1000;
                    persistRef[0] = new PersistenceService(this.queue, this.cfg.simulation.environment.shape, batchSize);
                    persistRef[0].start();
                }, "PersistenceService");
                
                // Start debug indexer (will wait for raw data)
                final String[] timestampRef = new String[1];
                Thread indexerThread = new Thread(() -> {
                    // Wait for persistence service to create its database first
                    try {
                        Thread.sleep(1000); // Give persistence service time to start
                        String rawDbPath = persistRef[0].getDbFilePath().toString();
                        // Extract timestamp from path like "runs\sim_run_20250825_052155_raw.sqlite"
                        int rawIndex = rawDbPath.lastIndexOf("_raw");
                        int timestampStart = rawDbPath.lastIndexOf("_", rawIndex - 1) + 1;
                        timestampRef[0] = rawDbPath.substring(timestampStart, rawIndex);
                        
                        // Get batch size from indexer config
                        int indexerBatchSize = this.cfg.pipeline.indexer != null ? this.cfg.pipeline.indexer.batchSize : 1000;
                        indexerRef[0] = new DebugIndexer(rawDbPath, indexerBatchSize);
                        indexerRef[0].start();
                    } catch (Exception e) {
                        System.err.println("Failed to start debug indexer: " + e.getMessage());
                    }
                }, "DebugIndexer");
                
                // Start debug server
                Thread serverThread = new Thread(() -> {
                    int port = (this.cfg.pipeline.server != null && this.cfg.pipeline.server.port != null) ? this.cfg.pipeline.server.port : 7070;
                    // Wait for debug indexer to create its database and set timestamp
                    try {
                        // Wait for timestamp to be available
                        while (timestampRef[0] == null && !Thread.currentThread().isInterrupted()) {
                            Thread.sleep(100);
                        }
                        if (timestampRef[0] != null) {
                            String debugDbPath = "runs/sim_run_" + timestampRef[0] + "_debug.sqlite";
                            serverRef[0] = new DebugServer();
                            serverRef[0].start(debugDbPath, port);
                        }
                    } catch (Exception e) {
                        System.err.println("Failed to start debug server: " + e.getMessage());
                    }
                }, "DebugServer");
                
                simThread.start();
                persistThread.start();
                indexerThread.start();
                serverThread.start();
                
                // Wait for all services to actually start
                try {
                    Thread.sleep(2000); // Give services time to initialize
                    System.out.println("Pipeline started successfully");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
            } else if (cmd.startsWith("start ")) {
                // Start specific service
                String serviceName = cmd.substring(6).toLowerCase();
                switch (serviceName) {
                    case "simulation":
                        if (simRef[0] == null || !simRef[0].isRunning()) {
                            simRef[0] = new SimulationEngine(this.queue, this.cfg.simulation.environment.shape, this.cfg.simulation.environment.toroidal);
                            simRef[0].setSeed(this.cfg.simulation.seed);
                            simRef[0].setOrganismDefinitions(this.cfg.simulation.organisms);
                            simRef[0].setEnergyStrategies(this.cfg.simulation.energyStrategies);
                            simRef[0].start();
                            System.out.println("Simulation started successfully");
                        } else {
                            System.out.println("Simulation is already running.");
                        }
                        break;
                    case "persist":
                    case "persistence":
                        if (persistRef[0] == null || !persistRef[0].isRunning()) {
                            int batchSize = this.cfg.pipeline.persistence != null ? this.cfg.pipeline.persistence.batchSize : 1000;
                            persistRef[0] = new PersistenceService(this.queue, this.cfg.simulation.environment.shape, batchSize);
                            persistRef[0].start();
                            System.out.println("PersistenceService started successfully");
                        } else {
                            System.out.println("PersistenceService is already running.");
                        }
                        break;
                    case "indexer":
                        if (indexerRef[0] == null || !indexerRef[0].isRunning()) {
                            if (persistRef[0] != null) {
                                String rawDbPath = persistRef[0].getDbFilePath().toString();
                                int indexerBatchSize = this.cfg.pipeline.indexer != null ? this.cfg.pipeline.indexer.batchSize : 1000;
                                indexerRef[0] = new DebugIndexer(rawDbPath, indexerBatchSize);
                                indexerRef[0].start();
                                System.out.println("DebugIndexer started successfully");
                            } else {
                                System.out.println("PersistenceService must be running first to start DebugIndexer.");
                            }
                        } else {
                            System.out.println("DebugIndexer is already running.");
                        }
                        break;
                    case "server":
                        if (serverRef[0] == null || !serverRef[0].isRunning()) {
                            if (indexerRef[0] != null) {
                                // Extract timestamp from indexer's database path
                                String rawDbPath = persistRef[0].getDbFilePath().toString();
                                int rawIndex = rawDbPath.lastIndexOf("_raw");
                                int timestampStart = rawDbPath.lastIndexOf("_", rawIndex - 1) + 1;
                                String timestamp = rawDbPath.substring(timestampStart, rawIndex);
                                String debugDbPath = "runs/sim_run_" + timestamp + "_debug.sqlite";
                                
                                int port = (this.cfg.pipeline.server != null && this.cfg.pipeline.server.port != null) ? this.cfg.pipeline.server.port : 7070;
                                serverRef[0] = new DebugServer();
                                serverRef[0].start(debugDbPath, port);
                                System.out.println("DebugServer started successfully on port " + port);
                            } else {
                                System.out.println("DebugIndexer must be running first to start DebugServer.");
                            }
                        } else {
                            System.out.println("DebugServer is already running.");
                        }
                        break;
                    default:
                        System.out.println("Unknown service: " + serviceName + ". Available: simulation | persist | indexer | server");
                        break;
                }
                
            } else if (cmd.equalsIgnoreCase("pause")) {
                if (simRef[0] != null && simRef[0].isRunning()) {
                    System.out.println("Pausing simulation...");
                    simRef[0].pause();
                    
                    // Persistence and indexer will automatically pause when no ticks are available
                    // and wake up periodically to check for new ticks
                    System.out.println("Simulation paused. Persistence and indexer will auto-pause when idle.");
                    System.out.println("Use 'start' to resume simulation or 'exit' to shutdown.");
                    
                } else {
                    System.out.println("Simulation is not running or already paused.");
                }
                
            } else if (cmd.startsWith("pause ")) {
                // Pause specific service
                String serviceName = cmd.substring(6).toLowerCase();
                switch (serviceName) {
                    case "simulation":
                        if (simRef[0] != null && simRef[0].isRunning()) {
                            System.out.println("Pausing simulation...");
                            simRef[0].pause();
                            System.out.println("Simulation paused.");
                        } else {
                            System.out.println("Simulation is not running or already paused.");
                        }
                        break;
                    case "persist":
                    case "persistence":
                        if (persistRef[0] != null && persistRef[0].isRunning()) {
                            System.out.println("Pausing PersistenceService...");
                            persistRef[0].pause();
                            System.out.println("PersistenceService paused.");
                        } else {
                            System.out.println("PersistenceService is not running or already paused.");
                        }
                        break;
                    case "indexer":
                        if (indexerRef[0] != null && indexerRef[0].isRunning()) {
                            System.out.println("Pausing DebugIndexer...");
                            indexerRef[0].pause();
                            System.out.println("DebugIndexer paused.");
                        } else {
                            System.out.println("DebugIndexer is not running or already paused.");
                        }
                        break;
                    case "server":
                        if (serverRef[0] != null && serverRef[0].isRunning()) {
                            System.out.println("Pausing DebugServer...");
                            serverRef[0].stop();
                            System.out.println("DebugServer paused.");
                        } else {
                            System.out.println("DebugServer is not running or already paused.");
                        }
                        break;
                    default:
                        System.out.println("Unknown service: " + serviceName + ". Available: simulation | persist | indexer | server");
                        break;
                }
                
            } else if (cmd.equalsIgnoreCase("status")) {
                if (simRef[0] != null) {
                    System.out.println("Simulation: " + simRef[0].getStatus());
                } else {
                    System.out.println("Simulation: NOT_STARTED");
                }
                
                if (persistRef[0] != null) {
                    System.out.println("Persistence: " + persistRef[0].getStatus());
                } else {
                    System.out.println("Persistence: NOT_STARTED");
                }
                
                if (indexerRef[0] != null) {
                    System.out.println("DebugIndexer: " + indexerRef[0].getStatus());
                } else {
                    System.out.println("DebugIndexer: NOT_STARTED");
                }
                
                if (serverRef[0] != null) {
                    System.out.println("DebugServer: " + serverRef[0].getStatus());
                } else {
                    System.out.println("DebugServer: NOT_STARTED");
                }
                
            } else if (cmd.equalsIgnoreCase("exit") || cmd.equalsIgnoreCase("quit")) {
                break;
                
            } else if (cmd.startsWith("exit ")) {
                // Exit specific service
                String serviceName = cmd.substring(5).toLowerCase();
                switch (serviceName) {
                    case "simulation":
                        if (simRef[0] != null) {
                            simRef[0].shutdown();
                            System.out.println("Simulation shutdown.");
                        } else {
                            System.out.println("Simulation is not running.");
                        }
                        break;
                    case "persist":
                    case "persistence":
                        if (persistRef[0] != null) {
                            persistRef[0].shutdown();
                            System.out.println("PersistenceService shutdown.");
                        } else {
                            System.out.println("PersistenceService is not running.");
                        }
                        break;
                    case "indexer":
                        if (indexerRef[0] != null) {
                            indexerRef[0].shutdown();
                            System.out.println("DebugIndexer shutdown.");
                        } else {
                            System.out.println("DebugIndexer is not running.");
                        }
                        break;
                    case "server":
                        if (serverRef[0] != null) {
                            serverRef[0].stop();
                            System.out.println("DebugServer shutdown.");
                        } else {
                            System.out.println("DebugServer is not running.");
                        }
                        break;
                    default:
                        System.out.println("Unknown service: " + serviceName + ". Available: simulation | persist | indexer | server");
                        break;
                }
                
            } else if (cmd.isEmpty()) {
                continue;
            } else {
                System.out.println("Unknown command. Available: start [service] | pause [service] | status | exit [service]");
            }
        }

        // Cleanup - wait for services to finish naturally
        if (simRef[0] != null) {
            simRef[0].shutdown();
            // Wait for simulation to finish
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (persistRef[0] != null) {
            persistRef[0].shutdown();
            // Wait for persistence to finish
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (indexerRef[0] != null) {
            indexerRef[0].shutdown();
            // Wait for indexer to finish
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (serverRef[0] != null) {
            serverRef[0].stop();
            // Wait for server to finish
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("CLI shutdown complete");
    }
}