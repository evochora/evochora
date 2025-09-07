package org.evochora.server;

import org.evochora.server.engine.SimulationEngine;
import org.evochora.server.persistence.PersistenceService;
import org.evochora.server.indexer.DebugIndexer;
import org.evochora.server.http.DebugServer;
import org.evochora.server.queue.ITickMessageQueue;
import org.evochora.server.config.SimulationConfiguration;
import org.evochora.runtime.model.EnvironmentProperties;
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
        
        // Apply logging configuration before any other operations
        applyLoggingConfiguration();
        
        // Check autoStart configuration and start services automatically
        log.debug("Checking autoStart configuration...");
        log.info("Auto-starting services...");
        
        if (config.pipeline != null && config.pipeline.simulation != null && 
            Boolean.TRUE.equals(config.pipeline.simulation.autoStart)) {
            startSimulation();
        }
        
        if (config.pipeline != null && config.pipeline.persistence != null && 
            Boolean.TRUE.equals(config.pipeline.persistence.autoStart)) {
            startPersistence();
        }
        
        if (config.pipeline != null && config.pipeline.indexer != null && 
            Boolean.TRUE.equals(config.pipeline.indexer.autoStart)) {
            startIndexer();
        }
        
        if (config.pipeline != null && config.pipeline.server != null && 
            Boolean.TRUE.equals(config.pipeline.server.autoStart)) {
            startDebugServer();
        }
        
        log.debug("AutoStart configuration processed");
    }
    
    /**
     * Start all services in the correct order.
     */
    public void startAll() {
        log.info("Starting all services...");
        
        boolean allServicesStarted = true;
        boolean anyServiceStarted = false;
        
        // Start simulation first
        if (!simulationRunning.get()) {
            startSimulation();
            log.info("Simulation service started");
            anyServiceStarted = true;
        } else {
            log.info("Simulation service already running");
        }
        
        // Wait a bit for simulation to initialize
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Start persistence service
        if (!persistenceRunning.get()) {
            startPersistence();
            log.info("Persistence service started");
            anyServiceStarted = true;
        } else {
            log.info("Persistence service already running");
        }
        
        // Wait for persistence to create database
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Start indexer
        if (!indexerRunning.get()) {
            startIndexer();
            log.info("Indexer service started");
            anyServiceStarted = true;
        } else {
            log.info("Indexer service already running");
        }
        
        // Start debug server
        if (!serverRunning.get()) {
            startDebugServer();
            if (serverRunning.get()) {
                log.info("Debug server started");
                anyServiceStarted = true;
            }
        } else {
            log.info("Debug server already running");
        }
        
        // Check if debug server actually started
        if (!serverRunning.get()) {
            allServicesStarted = false;
        }
        
        if (allServicesStarted) {
            if (anyServiceStarted) {
                log.info("All services started successfully");
            } else {
                log.info("All services already running");
            }
        } else {
            log.warn("Some services failed to start. Check logs for details.");
        }
    }
    
    /**
     * Start a specific service.
     */
    public void startService(String serviceName) {
        switch (serviceName.toLowerCase()) {
            case "simulation":
            case "sim":
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
            case "web":
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
        
        boolean anyServicePaused = false;
        
        if (simulationEngine.get() != null && simulationRunning.get()) {
            if (!simulationEngine.get().isPaused()) {
                simulationEngine.get().pause();
                log.info("Simulation service paused");
                anyServicePaused = true;
            } else {
                log.info("Simulation service already paused");
            }
        } else if (simulationEngine.get() != null) {
            log.info("Simulation service not running, cannot pause");
        } else {
            log.info("Simulation service not available");
        }
        
        if (persistenceService.get() != null && persistenceRunning.get()) {
            if (!persistenceService.get().isPaused()) {
                persistenceService.get().pause();
                log.info("Persistence service paused");
                anyServicePaused = true;
            } else {
                log.info("Persistence service already paused");
            }
        } else if (persistenceService.get() != null) {
            log.info("Persistence service not running, cannot pause");
        } else {
            log.info("Persistence service not available");
        }
        
        if (indexer.get() != null && indexerRunning.get()) {
            if (!indexer.get().isPaused()) {
                indexer.get().pause();
                log.info("Indexer service paused");
                anyServicePaused = true;
            } else {
                log.info("Indexer service already paused");
            }
        } else if (indexer.get() != null) {
            log.info("Indexer service not running, cannot pause");
        } else {
            log.info("Indexer service not available");
        }
        
        if (debugServer.get() != null && serverRunning.get()) {
            debugServer.get().stop();
            serverRunning.set(false);
            log.info("Debug server stopped");
            anyServicePaused = true;
        } else if (debugServer.get() != null) {
            log.info("Debug server not running, cannot stop");
        } else {
            log.info("Debug server not available");
        }
        
        if (anyServicePaused) {
            log.info("All services paused");
        } else {
            log.info("No services needed pausing");
        }
    }
    
    /**
     * Pause a specific service.
     */
    public void pauseService(String serviceName) {
        switch (serviceName.toLowerCase()) {
            case "simulation":
            case "sim":
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
            case "web":
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
        
        boolean anyServiceResumed = false;
        
        if (simulationEngine.get() != null && simulationRunning.get()) {
            if (simulationEngine.get().isPaused()) {
                simulationEngine.get().resume();
                log.info("Simulation service resumed");
                anyServiceResumed = true;
            } else {
                log.info("Simulation service already running");
            }
        } else if (simulationEngine.get() != null) {
            log.info("Simulation service not running, cannot resume");
        } else {
            log.info("Simulation service not available");
        }
        
        if (persistenceService.get() != null && persistenceRunning.get()) {
            if (persistenceService.get().isPaused()) {
                persistenceService.get().resume();
                log.info("Persistence service resumed");
                anyServiceResumed = true;
            } else {
                log.info("Persistence service already running");
            }
        } else if (persistenceService.get() != null) {
            log.info("Persistence service not running, cannot resume");
        } else {
            log.info("Persistence service not available");
        }
        
        if (indexer.get() != null && indexerRunning.get()) {
            if (indexer.get().isPaused()) {
                indexer.get().resume();
                log.info("Indexer service resumed");
                anyServiceResumed = true;
            } else {
                log.info("Indexer service already running");
            }
        } else if (indexer.get() != null) {
            log.info("Indexer service not running, cannot resume");
        } else {
            log.info("Indexer service not available");
        }
        
        if (debugServer.get() != null && !serverRunning.get()) {
            startDebugServer();
            if (serverRunning.get()) {
                log.info("Debug server started");
                anyServiceResumed = true;
            }
        } else if (debugServer.get() != null) {
            log.info("Debug server already running");
        } else {
            log.info("Debug server not available");
        }
        
        if (anyServiceResumed) {
            log.info("All services resumed");
        } else {
            log.info("No services needed resuming");
        }
    }
    
    /**
     * Resume a specific service.
     */
    public void resumeService(String serviceName) {
        switch (serviceName.toLowerCase()) {
            case "simulation":
            case "sim":
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
            case "web":
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
        
        // Header
        status.append(String.format("%-12s %-12s %-8s %-8s %s%n", 
                "Service", "Status", "Ticks", "TPS", "Details"));
        status.append(String.format("%-12s %-12s %-8s %-8s %s%n", 
                "--------", "------", "-----", "---", "-------"));
        
        // Simulation status
        if (simulationEngine.get() != null) {
            String simulationStatus = simulationEngine.get().getStatus();
            status.append(String.format("%-12s %s%n", "sim", simulationStatus));
        } else {
            status.append(String.format("%-12s %-12s %-8s %s%n", "sim", "NOT_STARTED", "", ""));
        }
        
        // Persistence status
        if (persistenceService.get() != null) {
            String persistenceStatus = persistenceService.get().getStatus();
            status.append(String.format("%-12s %s%n", "persist", persistenceStatus));
        } else {
            status.append(String.format("%-12s %-12s %-8s %s%n", "persist", "NOT_STARTED", "", ""));
        }
        
        // Indexer status
        if (indexer.get() != null) {
            String indexerStatus = indexer.get().getStatus();
            status.append(String.format("%-12s %s%n", "indexer", indexerStatus));
        } else {
            status.append(String.format("%-12s %-12s %-8s %s%n", "indexer", "NOT_STARTED", "", ""));
        }
        
        // Debug server status
        if (debugServer.get() != null) {
            String serverStatus = debugServer.get().getStatus();
            status.append(String.format("%-12s %s%n", "web", serverStatus));
        } else {
            status.append(String.format("%-12s %-12s %-8s %s%n", "web", "NOT_STARTED", "", ""));
        }
        
        return status.toString();
    }
    
    // Private helper methods
    
    private void startSimulation() {
        if (simulationEngine.get() == null || !simulationRunning.get()) {
            // Lade skipProgramArtefact Konfiguration
            boolean skipProgramArtefact = false; // Default: ProgramArtifact features enabled
            if (config.pipeline.simulation != null && config.pipeline.simulation.skipProgramArtefact != null) {
                skipProgramArtefact = config.pipeline.simulation.skipProgramArtefact;
            }
            
            // Create EnvironmentProperties from config
            org.evochora.runtime.model.EnvironmentProperties envProps = new org.evochora.runtime.model.EnvironmentProperties(
                config.simulation.environment.shape, 
                config.simulation.environment.toroidal
            );
            
            // Create organism placements from configuration
            java.util.List<org.evochora.server.engine.OrganismPlacement> organismPlacements = createOrganismPlacements(config, envProps);
            
            // Create energy strategies
            java.util.List<org.evochora.runtime.worldgen.IEnergyDistributionCreator> energyStrategies = new java.util.ArrayList<>();
            if (config.simulation.energyStrategies != null) {
                for (SimulationConfiguration.EnergyStrategyConfig strategyConfig : config.simulation.energyStrategies) {
                    try {
                        org.evochora.runtime.internal.services.IRandomProvider prov = 
                            new org.evochora.runtime.internal.services.SeededRandomProvider(0L);
                        energyStrategies.add(org.evochora.runtime.worldgen.EnergyStrategyFactory.create(
                            strategyConfig.type, strategyConfig.params, prov));
                    } catch (Exception e) {
                        log.warn("Failed to create energy strategy {}: {}", strategyConfig.type, e.getMessage());
                    }
                }
            }
            
            // Create SimulationEngine with new API
            SimulationEngine engine = new SimulationEngine(
                queue, 
                envProps,
                organismPlacements,
                energyStrategies,
                skipProgramArtefact
            );
            
            engine.setSeed(config.simulation.seed);
            
            // Set auto-pause configuration if available
            if (config.pipeline.simulation != null && config.pipeline.simulation.autoPauseTicks != null) {
                engine.setAutoPauseTicks(config.pipeline.simulation.autoPauseTicks);
            }
            
            simulationEngine.set(engine);
            engine.start();
            simulationRunning.set(true);
            log.info("Simulation engine started with ProgramArtifact features: {}", skipProgramArtefact ? "disabled" : "enabled");
        }
    }
    
    private void startPersistence() {
        if (persistenceService.get() == null || !persistenceRunning.get()) {
            int batchSize = config.pipeline.persistence != null ? config.pipeline.persistence.batchSize : 1000;
            String jdbcUrl = config.pipeline.persistence != null ? config.pipeline.persistence.jdbcUrl : null;
            EnvironmentProperties envProps = new EnvironmentProperties(config.simulation.environment.shape, config.simulation.environment.toroidal);
            PersistenceService service = new PersistenceService(queue, jdbcUrl, envProps, batchSize);
            
            persistenceService.set(service);
            service.start();
            persistenceRunning.set(true);
            String dbUrl = persistenceService.get().getJdbcUrl();
            log.info("Persistence service started: {}", dbUrl != null ? dbUrl : "in-memory database");
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
                // DebugIndexer lädt EnvironmentProperties aus der Datenbank
                DebugIndexer service = new DebugIndexer(indexerRawDbUrl, indexerDebugDbUrl, batchSize);
                
                indexer.set(service);
                service.start();
                indexerRunning.set(true);
                log.info("Indexer started: reading {}, writing {}", 
                    indexerRawDbUrl, indexerDebugDbUrl);
            } else {
                log.warn("Cannot start indexer: persistence service not running");
            }
        }
    }
    
    /**
     * Creates organism placements from the configuration.
     * This method reads the organism configuration, compiles the assembly programs,
     * and creates OrganismPlacement objects for the simulation.
     */
    private java.util.List<org.evochora.server.engine.OrganismPlacement> createOrganismPlacements(SimulationConfiguration config, EnvironmentProperties envProps) {
        java.util.List<org.evochora.server.engine.OrganismPlacement> placements = new java.util.ArrayList<>();
        
        if (config.simulation == null || config.simulation.organisms == null) {
            log.info("No organisms configured in simulation");
            return placements;
        }
        
        log.debug("Creating {} organism placements from configuration", config.simulation.organisms.size());
        
        for (SimulationConfiguration.OrganismConfig organismConfig : config.simulation.organisms) {
            try {
                // Read and compile the assembly program
                // Handle relative paths by looking in the project root directory
                java.nio.file.Path programPath;
                if (java.nio.file.Paths.get(organismConfig.program).isAbsolute()) {
                    programPath = java.nio.file.Paths.get(organismConfig.program);
                } else {
                    // Try relative to current working directory first
                    programPath = java.nio.file.Paths.get(organismConfig.program);
                    if (!java.nio.file.Files.exists(programPath)) {
                        // If not found, try relative to project root (where config.jsonc is located)
                        programPath = java.nio.file.Paths.get("src/main/resources", organismConfig.program);
                    }
                }
                
                if (!java.nio.file.Files.exists(programPath)) {
                    throw new java.io.FileNotFoundException("Assembly program not found: " + organismConfig.program + 
                        " (tried: " + java.nio.file.Paths.get(organismConfig.program) + 
                        " and " + java.nio.file.Paths.get("src/main/resources", organismConfig.program) + ")");
                }
                
                java.util.List<String> sourceLines = java.nio.file.Files.readAllLines(programPath);
                
                // Initialize the instruction set before compiling
                org.evochora.runtime.isa.Instruction.init();
                
                org.evochora.compiler.Compiler compiler = new org.evochora.compiler.Compiler();
                org.evochora.compiler.api.ProgramArtifact artifact = compiler.compile(sourceLines, programPath.toString(), envProps);
                
                // Create placements for each position
                if (organismConfig.placement != null && organismConfig.placement.positions != null) {
                    for (int[] position : organismConfig.placement.positions) {
                        org.evochora.server.engine.OrganismPlacement placement = org.evochora.server.engine.OrganismPlacement.of(
                            artifact,
                            organismConfig.initialEnergy,
                            position
                        );
                        placements.add(placement);
                        log.info("Created organism placement at position {} with energy {}", 
                                java.util.Arrays.toString(position), organismConfig.initialEnergy);
                    }
                } else {
                    log.warn("No placement positions configured for organism with program: {}", organismConfig.program);
                }
                
            } catch (Exception e) {
                log.error("Failed to create organism placement for program {}: {}", organismConfig.program, e.getMessage());
            }
        }
        
        log.debug("Successfully created {} organism placements", placements.size());
        return placements;
    }

    private void startDebugServer() {
        if (debugServer.get() == null || !serverRunning.get()) {
            String debugDbPath;
            int port = config.pipeline.server != null && config.pipeline.server.port != null ? config.pipeline.server.port : 7070;
            
            // Try to get debug database from indexer first (normal pipeline mode)
            if (indexer.get() != null) {
                debugDbPath = indexer.get().getDebugDbPath();
                log.info("Debug server started in pipeline mode on port {} reading {}", port, debugDbPath);
            } else {
                // Standalone mode: find debug database manually
                debugDbPath = findLatestDebugDatabase();
                if (debugDbPath == null) {
                    log.error("No debug database found. Please ensure a debug database exists in the runs directory.");
                    return;
                }
                log.info("Debug server started in standalone mode on port {} reading {}", port, debugDbPath);
            }
            
            DebugServer server = new DebugServer();
            debugServer.set(server);
            try {
                server.start(debugDbPath, port);
                serverRunning.set(true);
            } catch (RuntimeException e) {
                log.error("Debug server port {} in use: {}", port, e.getMessage());
                // Don't set serverRunning to true, but continue with other services
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
    
    /**
     * Applies logging configuration from the config to set appropriate log levels.
     */
    private void applyLoggingConfiguration() {
        try {
            // Try to access Logback LoggerContext via reflection (since it's runtimeOnly)
            Object loggerContext = LoggerFactory.getILoggerFactory();
            Class<?> levelClass = Class.forName("ch.qos.logback.classic.Level");
            Class<?> loggerClass = Class.forName("ch.qos.logback.classic.Logger");
            
            // Check for System Properties first (command line override)
            String defaultLevel = System.getProperty("log.level");
            if (defaultLevel != null) {
                Object level = levelClass.getMethod("toLevel", String.class)
                    .invoke(null, defaultLevel.toUpperCase());
                Object rootLogger = loggerContext.getClass().getMethod("getLogger", String.class)
                    .invoke(loggerContext, org.slf4j.Logger.ROOT_LOGGER_NAME);
                loggerClass.getMethod("setLevel", levelClass).invoke(rootLogger, level);
                log.debug("Applied default log level from System Property: {}", defaultLevel);
            }
            
            // Apply System Properties for specific loggers
            System.getProperties().entrySet().stream()
                .filter(entry -> entry.getKey().toString().startsWith("log."))
                .filter(entry -> !entry.getKey().toString().equals("log.level"))
                .forEach(entry -> {
                    try {
                        String loggerName = entry.getKey().toString().substring(4); // Remove "log." prefix
                        String logLevel = entry.getValue().toString();
                        Object level = levelClass.getMethod("toLevel", String.class)
                            .invoke(null, logLevel.toUpperCase());
                        Object logger = loggerContext.getClass().getMethod("getLogger", String.class)
                            .invoke(loggerContext, loggerName);
                        loggerClass.getMethod("setLevel", levelClass).invoke(logger, level);
                        log.debug("Applied log level from System Property: {} = {}", loggerName, logLevel);
                    } catch (Exception e) {
                        log.warn("Failed to apply log level from System Property: {}", e.getMessage());
                    }
                });
            
            // Fallback to config.jsonc if no System Properties were set
            if (defaultLevel == null && System.getProperties().entrySet().stream()
                .noneMatch(entry -> entry.getKey().toString().startsWith("log."))) {
                
                if (config.pipeline != null && config.pipeline.logging != null) {
                    // Set default log level from config
                    if (config.pipeline.logging.defaultLogLevel != null) {
                        Object level = levelClass.getMethod("toLevel", String.class)
                            .invoke(null, config.pipeline.logging.defaultLogLevel.toUpperCase());
                        Object rootLogger = loggerContext.getClass().getMethod("getLogger", String.class)
                            .invoke(loggerContext, org.slf4j.Logger.ROOT_LOGGER_NAME);
                        loggerClass.getMethod("setLevel", levelClass).invoke(rootLogger, level);
                    }
                    
                    // Set per-logger log levels from config
                    if (config.pipeline.logging.logLevels != null) {
                        for (var entry : config.pipeline.logging.logLevels.entrySet()) {
                            String loggerName = entry.getKey();
                            String logLevel = entry.getValue();
                            Object level = levelClass.getMethod("toLevel", String.class)
                                .invoke(null, logLevel.toUpperCase());
                            Object logger = loggerContext.getClass().getMethod("getLogger", String.class)
                                .invoke(loggerContext, loggerName);
                            loggerClass.getMethod("setLevel", levelClass).invoke(logger, level);
                        }
                    }
                    log.debug("Applied logging configuration from config.jsonc");
                }
            }
            
            log.debug("Successfully configured Logback logging levels");
        } catch (Exception e) {
            // Fallback: If Logback is not available, use System properties for SLF4J Simple Logger
            log.warn("Could not configure Logback, falling back to SLF4J Simple Logger: {}", e.getMessage());
            
            String defaultLevel = System.getProperty("log.level");
            if (defaultLevel != null) {
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", defaultLevel.toLowerCase());
            } else if (config.pipeline != null && config.pipeline.logging != null && config.pipeline.logging.defaultLogLevel != null) {
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", config.pipeline.logging.defaultLogLevel.toLowerCase());
            }
            
            // Apply System Properties for specific loggers
            System.getProperties().entrySet().stream()
                .filter(entry -> entry.getKey().toString().startsWith("log."))
                .filter(entry -> !entry.getKey().toString().equals("log.level"))
                .forEach(entry -> {
                    String loggerName = entry.getKey().toString().substring(4);
                    String logLevel = entry.getValue().toString();
                    System.setProperty("org.slf4j.simpleLogger.log." + loggerName, logLevel.toLowerCase());
                });
            
            // Fallback to config.jsonc if no System Properties were set
            if (defaultLevel == null && System.getProperties().entrySet().stream()
                .noneMatch(entry -> entry.getKey().toString().startsWith("log."))) {
                
                if (config.pipeline != null && config.pipeline.logging != null && config.pipeline.logging.logLevels != null) {
                    for (var entry : config.pipeline.logging.logLevels.entrySet()) {
                        String loggerName = entry.getKey();
                        String logLevel = entry.getValue();
                        System.setProperty("org.slf4j.simpleLogger.log." + loggerName, logLevel.toLowerCase());
                    }
                }
            }
        }
    }
    
    /**
     * Validates if a log level is valid.
     */
    public boolean isValidLogLevel(String level) {
        if (level == null || level.trim().isEmpty()) {
            return false;
        }
        String upperLevel = level.toUpperCase();
        return upperLevel.equals("TRACE") || upperLevel.equals("DEBUG") || 
               upperLevel.equals("INFO") || upperLevel.equals("WARN") || 
               upperLevel.equals("ERROR");
    }
    
    /**
     * Gets the current log level for a specific logger.
     */
    public String getCurrentLogLevel(String loggerAlias) {
        try {
            String loggerName = mapLoggerAlias(loggerAlias);
            Object loggerContext = LoggerFactory.getILoggerFactory();
            Class<?> loggerClass = Class.forName("ch.qos.logback.classic.Logger");
            
            Object logger = loggerContext.getClass().getMethod("getLogger", String.class)
                .invoke(loggerContext, loggerName);
            Object level = loggerClass.getMethod("getLevel").invoke(logger);
            
            if (level != null) {
                return level.toString();
            } else {
                // If logger has no specific level, get root logger level
                Object rootLogger = loggerContext.getClass().getMethod("getLogger", String.class)
                    .invoke(loggerContext, org.slf4j.Logger.ROOT_LOGGER_NAME);
                Object rootLevel = loggerClass.getMethod("getLevel").invoke(rootLogger);
                return rootLevel != null ? rootLevel.toString() : "UNKNOWN";
            }
        } catch (Exception e) {
            log.warn("Failed to get current log level for {}: {}", loggerAlias, e.getMessage());
            return "UNKNOWN";
        }
    }
    
    /**
     * Sets the default log level at runtime.
     */
    public void setDefaultLogLevel(String level) {
        try {
            Object loggerContext = LoggerFactory.getILoggerFactory();
            Class<?> levelClass = Class.forName("ch.qos.logback.classic.Level");
            Class<?> loggerClass = Class.forName("ch.qos.logback.classic.Logger");
            
            Object levelObj = levelClass.getMethod("toLevel", String.class)
                .invoke(null, level.toUpperCase());
            Object rootLogger = loggerContext.getClass().getMethod("getLogger", String.class)
                .invoke(loggerContext, org.slf4j.Logger.ROOT_LOGGER_NAME);
            loggerClass.getMethod("setLevel", levelClass).invoke(rootLogger, levelObj);
            
            log.info("Default log level set to: {}", level);
        } catch (Exception e) {
            log.error("Failed to set default log level: {}", e.getMessage());
        }
    }
    
    /**
     * Sets the log level for a specific logger at runtime.
     */
    public void setLogLevel(String loggerAlias, String level) {
        try {
            // Map aliases to full logger names
            String loggerName = mapLoggerAlias(loggerAlias);
            
            Object loggerContext = LoggerFactory.getILoggerFactory();
            Class<?> levelClass = Class.forName("ch.qos.logback.classic.Level");
            Class<?> loggerClass = Class.forName("ch.qos.logback.classic.Logger");
            
            Object levelObj = levelClass.getMethod("toLevel", String.class)
                .invoke(null, level.toUpperCase());
            Object logger = loggerContext.getClass().getMethod("getLogger", String.class)
                .invoke(loggerContext, loggerName);
            loggerClass.getMethod("setLevel", levelClass).invoke(logger, levelObj);
            
            log.info("Log level for {} set to: {}", loggerName, level);
        } catch (Exception e) {
            log.error("Failed to set log level for {}: {}", loggerAlias, e.getMessage());
        }
    }
    
    /**
     * Resets all log levels to config.jsonc values.
     */
    public void resetLogLevels() {
        // Clear System Properties
        System.getProperties().entrySet().removeIf(entry -> 
            entry.getKey().toString().startsWith("log."));
        
        // Reapply configuration from config.jsonc
        applyLoggingConfiguration();
        log.info("Log levels reset to config.jsonc values");
    }
    
    /**
     * Maps logger aliases to full logger names.
     */
    private String mapLoggerAlias(String alias) {
        return switch (alias.toLowerCase()) {
            case "sim" -> "org.evochora.server.engine.SimulationEngine";
            case "persist" -> "org.evochora.server.persistence.PersistenceService";
            case "indexer" -> "org.evochora.server.indexer.DebugIndexer";
            case "web" -> "org.evochora.server.http.DebugServer";
            case "cli" -> "org.evochora.server.ServiceManager";
            default -> alias; // Assume it's already a full logger name
        };
    }
}
