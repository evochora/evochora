package org.evochora.server;

import org.evochora.server.engine.SimulationEngine;
import org.evochora.server.persistence.PersistenceService;
import org.evochora.server.indexer.DebugIndexer;
import org.evochora.server.http.DebugServer;
import org.evochora.server.queue.ITickMessageQueue;
import org.evochora.server.config.SimulationConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Centralized service manager for the Evochora pipeline.
 * Handles service lifecycle, auto-pause logic, and provides a clean interface for the CLI.
 */
public final class ServiceManager {
    private static final Logger log = LoggerFactory.getLogger(ServiceManager.class);
    
    // Service references
    private final AtomicReference<SimulationEngine> simulationEngine = new AtomicReference<>();
    private final AtomicReference<PersistenceService> persistenceService = new AtomicReference<>();
    private final AtomicReference<DebugIndexer> indexer = new AtomicReference<>();
    private final AtomicReference<DebugServer> debugServer = new AtomicReference<>();
    
    // Service states
    private final AtomicBoolean simulationRunning = new AtomicBoolean(false);
    private final AtomicBoolean persistenceRunning = new AtomicBoolean(false);
    private final AtomicBoolean indexerRunning = new AtomicBoolean(false);
    private final AtomicBoolean serverRunning = new AtomicBoolean(false);
    
    // Configuration
    private final ITickMessageQueue queue;
    private final SimulationConfiguration config;
    
    public ServiceManager(ITickMessageQueue queue, SimulationConfiguration config) {
        this.queue = queue;
        this.config = config;
    }
    
    /**
     * Start all services in the correct order.
     */
    public void startAll() {
        log.info("Starting all services...");
        
        // Start simulation first
        startSimulation();
        
        // Wait a bit for simulation to initialize
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Start persistence service
        startPersistence();
        
        // Wait for persistence to create database
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Start indexer
        startIndexer();
        
        // Start debug server
        startDebugServer();
        
        log.info("All services started successfully");
    }
    
    /**
     * Start a specific service.
     */
    public void startService(String serviceName) {
        switch (serviceName.toLowerCase()) {
            case "simulation":
                startSimulation();
                break;
            case "persist":
            case "persistence":
                startPersistence();
                break;
            case "indexer":
                startIndexer();
                break;
            case "server":
                startDebugServer();
                break;
            default:
                log.warn("Unknown service: {}", serviceName);
        }
    }
    
    /**
     * Pause all services.
     */
    public void pauseAll() {
        log.info("Pausing all services...");
        
        if (simulationEngine.get() != null && simulationRunning.get()) {
            simulationEngine.get().pause();
        }
        
        if (persistenceService.get() != null && persistenceRunning.get()) {
            persistenceService.get().pause();
        }
        
        if (indexer.get() != null && indexerRunning.get()) {
            indexer.get().pause();
        }
        
        if (debugServer.get() != null && serverRunning.get()) {
            debugServer.get().stop();
            serverRunning.set(false);
        }
        
        log.info("All services paused");
    }
    
    /**
     * Pause a specific service.
     */
    public void pauseService(String serviceName) {
        switch (serviceName.toLowerCase()) {
            case "simulation":
                if (simulationEngine.get() != null && simulationRunning.get()) {
                    simulationEngine.get().pause();
                    log.info("Simulation paused");
                }
                break;
            case "persist":
            case "persistence":
                if (persistenceService.get() != null && persistenceRunning.get()) {
                    persistenceService.get().pause();
                    log.info("Persistence service paused");
                }
                break;
            case "indexer":
                if (indexer.get() != null && indexerRunning.get()) {
                    indexer.get().pause();
                    log.info("Indexer paused");
                }
                break;
            case "server":
                if (debugServer.get() != null && serverRunning.get()) {
                    debugServer.get().stop();
                    serverRunning.set(false);
                    log.info("Debug server stopped");
                }
                break;
            default:
                log.warn("Unknown service: {}", serviceName);
        }
    }
    
    /**
     * Resume all services.
     */
    public void resumeAll() {
        log.info("Resuming all services...");
        
        if (simulationEngine.get() != null && simulationRunning.get()) {
            simulationEngine.get().resume();
        }
        
        if (persistenceService.get() != null && persistenceRunning.get()) {
            persistenceService.get().resume();
        }
        
        if (indexer.get() != null && indexerRunning.get()) {
            indexer.get().resume();
        }
        
        if (debugServer.get() != null && !serverRunning.get()) {
            startDebugServer();
        }
        
        log.info("All services resumed");
    }
    
    /**
     * Resume a specific service.
     */
    public void resumeService(String serviceName) {
        switch (serviceName.toLowerCase()) {
            case "simulation":
                if (simulationEngine.get() != null) {
                    simulationEngine.get().resume();
                    log.info("Simulation resumed");
                } else {
                    log.warn("Simulation service not found, cannot resume");
                }
                break;
            case "persist":
            case "persistence":
                if (persistenceService.get() != null) {
                    persistenceService.get().resume();
                    log.info("Persistence service resumed");
                } else {
                    log.warn("Persistence service not found, cannot resume");
                }
                break;
            case "indexer":
                if (indexer.get() != null) {
                    indexer.get().resume();
                    log.info("Indexer resumed");
                } else {
                    log.warn("Indexer service not found, cannot resume");
                }
                break;
            case "server":
                if (debugServer.get() != null && !serverRunning.get()) {
                    startDebugServer();
                    log.info("Debug server resumed");
                } else if (debugServer.get() == null) {
                    log.warn("Debug server not found, cannot resume");
                } else {
                    log.info("Debug server is already running");
                }
                break;
            default:
                log.warn("Unknown service: {}", serviceName);
        }
    }
    
    /**
     * Stop all services.
     */
    public void stopAll() {
        log.info("Stopping all services...");
        
        if (simulationEngine.get() != null) {
            simulationEngine.get().shutdown();
            simulationRunning.set(false);
        }
        
        if (persistenceService.get() != null) {
            persistenceService.get().shutdown();
            persistenceRunning.set(false);
        }
        
        if (indexer.get() != null) {
            indexer.get().shutdown();
            indexerRunning.set(false);
        }
        
        if (debugServer.get() != null) {
            debugServer.get().stop();
            serverRunning.set(false);
        }
        
        log.info("All services stopped");
    }
    
    /**
     * Get status of all services.
     */
    public String getStatus() {
        StringBuilder status = new StringBuilder();
        
        // Simulation status
        if (simulationEngine.get() != null) {
            status.append("Simulation: ").append(simulationEngine.get().getStatus()).append("\n");
        } else {
            status.append("Simulation: NOT_STARTED\n");
        }
        
        // Persistence status
        if (persistenceService.get() != null) {
            status.append("Persistence: ").append(persistenceService.get().getStatus()).append("\n");
        } else {
            status.append("Persistence: NOT_STARTED\n");
        }
        
        // Indexer status
        if (indexer.get() != null) {
            status.append("Indexer: ").append(indexer.get().getStatus()).append("\n");
        } else {
            status.append("Indexer: NOT_STARTED\n");
        }
        
        // Debug server status
        if (debugServer.get() != null) {
            status.append("DebugServer: ").append(debugServer.get().isRunning() ? "running" : "stopped").append("\n");
        } else {
            status.append("DebugServer: NOT_STARTED\n");
        }
        
        return status.toString();
    }
    
    // Private helper methods
    
    private void startSimulation() {
        if (simulationEngine.get() == null || !simulationRunning.get()) {
            SimulationEngine engine = new SimulationEngine(queue, config.simulation.environment.shape, config.simulation.environment.toroidal);
            engine.setSeed(config.simulation.seed);
            engine.setOrganismDefinitions(config.simulation.organisms);
            engine.setEnergyStrategies(config.simulation.energyStrategies);
            
            // Set auto-pause configuration if available
            if (config.pipeline.simulation != null && config.pipeline.simulation.autoPauseTicks != null) {
                engine.setAutoPauseTicks(config.pipeline.simulation.autoPauseTicks);
            }
            
            simulationEngine.set(engine);
            engine.start();
            simulationRunning.set(true);
            log.info("Simulation engine started");
        }
    }
    
    private void startPersistence() {
        if (persistenceService.get() == null || !persistenceRunning.get()) {
            int batchSize = config.pipeline.persistence != null ? config.pipeline.persistence.batchSize : 1000;
            String jdbcUrl = config.pipeline.persistence != null ? config.pipeline.persistence.jdbcUrl : null;
            PersistenceService service = new PersistenceService(queue, jdbcUrl, config.simulation.environment.shape, batchSize);
            
            persistenceService.set(service);
            service.start();
            persistenceRunning.set(true);
            log.info("Persistence service started");
        }
    }
    
    private void startIndexer() {
        if (indexer.get() == null || !indexerRunning.get()) {
            if (persistenceService.get() != null) {
                String rawDbUrl = persistenceService.get().getJdbcUrl();
                // Generate unique URLs for indexer to avoid shared cache conflicts
                String indexerRawDbUrl = rawDbUrl.replace("memdb_", "memdb_indexer_");
                String indexerDebugDbUrl = indexerRawDbUrl.replace("_raw", "_debug");
                int batchSize = config.pipeline.indexer != null ? config.pipeline.indexer.batchSize : 1000;
                DebugIndexer service = new DebugIndexer(indexerRawDbUrl, indexerDebugDbUrl, batchSize);
                
                indexer.set(service);
                service.start();
                indexerRunning.set(true);
                log.info("Indexer started");
            } else {
                log.warn("Cannot start indexer: persistence service not running");
            }
        }
    }
    
    private void startDebugServer() {
        if (debugServer.get() == null || !serverRunning.get()) {
            if (indexer.get() != null) {
                String debugDbUrl = indexer.get().getDebugDbPath();
                int port = config.pipeline.server != null && config.pipeline.server.port != null ? config.pipeline.server.port : 7070;
                DebugServer server = new DebugServer();
                
                debugServer.set(server);
                server.start(debugDbUrl, port);
                serverRunning.set(true);
                log.info("Debug server started on port {} reading {}", port, debugDbUrl);
            } else {
                log.warn("Cannot start debug server: indexer not running");
            }
        }
    }
    
    private String findLatestDebugDatabase() {
        try {
            // First check if there's a specific debug database configured
            if (config.pipeline.server != null && config.pipeline.server.debugDbFile != null) {
                String configuredPath = config.pipeline.server.debugDbFile;
                if (Files.exists(Paths.get(configuredPath))) {
                    return configuredPath;
                }
                log.warn("Configured debug database not found: {}", configuredPath);
            }
            
            // Otherwise find the latest debug database in runs directory
            Path runsDir = Paths.get("runs");
            if (!Files.exists(runsDir)) {
                return null;
            }
            
            Optional<Path> latestDebugDb = Files.list(runsDir)
                    .filter(p -> p.getFileName().toString().endsWith("_debug.sqlite"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
            
            return latestDebugDb.map(Path::toString).orElse(null);
        } catch (Exception e) {
            log.warn("Error finding debug database: {}", e.getMessage());
            return null;
        }
    }
}
