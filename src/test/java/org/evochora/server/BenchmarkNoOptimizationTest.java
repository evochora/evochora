package org.evochora.server;

import org.evochora.server.config.SimulationConfiguration;
import org.evochora.server.engine.SimulationEngine;
import org.evochora.server.engine.OrganismPlacement;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.persistence.PersistenceService;
import org.evochora.server.indexer.DebugIndexer;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.internal.services.IRandomProvider;
import org.evochora.runtime.worldgen.EnergyStrategyFactory;
import org.evochora.runtime.worldgen.IEnergyDistributionCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive benchmark test for the complete Evochora pipeline WITHOUT my optimizations.
 * This is identical to BenchmarkTest but with my optimizations disabled to demonstrate
 * the performance impact of the optimizations I added to config.jsonc.
 * 
 * Tests simulation engine, persistence service, and debug indexer performance
 * with configurable parameters for detailed performance analysis.
 */
@Tag("benchmark")
public class BenchmarkNoOptimizationTest {

    // ===== CONFIGURABLE BENCHMARK PARAMETERS =====
    private int simulationTicks = 20000;
    private int persistenceBatchSize = 1000;
    private int indexerBatchSize = 1000;
    private EnvironmentProperties environmentProperties = new EnvironmentProperties(new int[]{100, 100}, true);
    private boolean indexerComprssionEnabled = false;
    
    // ===== TIMEOUT CONFIGURATION =====
    private int simulationReadyTimeoutMs = 5000; // Max wait for simulation to be ready
    private int simulationCompleteTimeoutMs = 60000; // Max wait for simulation to complete all ticks
    private int persistenceCompleteTimeoutMs = 10000; // Max wait for persistence to write all ticks to raw DB
    private int indexerStartTimeoutMs = 5000; // Max wait for indexer to start processing (after persistence is complete)
    private int indexerCompleteTimeoutMs = 60000; // Max wait for indexer to process all ticks from raw DB
    
    // ===== CLEANUP CONFIGURATION =====
    private boolean cleanUpDb = false; // Whether to delete database files after benchmark
    
    // ===== ORGANISM CONFIGURATION =====
    // Sie können hier die Anzahl der Organismen einfach ändern
    private int organismCount = 1; // Anzahl der Organismen
    private List<OrganismConfig> organismConfigs;
    
    // Organismen werden in der setUp() Methode erstellt
    private void createOrganismConfigs() {
        organismConfigs = new java.util.ArrayList<>();
        
        // Assembly-Programm für alle Organismen (gleiche Art)
        String assemblyProgram = String.join("\n",
            "START:",
            "  SETI %DR0 DATA:1",
            "  SETI %DR0 DATA:1",
            "  ADDR %DR0 %DR1",
            "  NOP",
            "  JMPI START"
        );
        
        // Erstelle Organismen in einer For-Schleife
        for (int i = 0; i < organismCount; i++) {
            organismConfigs.add(new OrganismConfig(
                assemblyProgram,
                new int[]{0, i}, // Startposition: x=0, y=i (jeder in eigener Zeile)
                100000 // Startenergie
            ));
        }
    }
    
    // Helper class for organism configuration
    private static class OrganismConfig {
        final String assemblyProgram;
        final int[] startPosition;
        final int startEnergy;
        
        OrganismConfig(String assemblyProgram, int[] startPosition, int startEnergy) {
            this.assemblyProgram = assemblyProgram;
            this.startPosition = startPosition;
            this.startEnergy = startEnergy;
        }
    }
    
    private List<Map<String, Object>> energyStrategies = List.of(
        Map.of("type", "solar", "params", Map.of("probability", 0.3, "amount", 50, "safetyRadius", 2, "executionsPerTick", 1)),
        Map.of("type", "geyser", "params", Map.of("count", 3, "interval", 10, "amount", 200, "safetyRadius", 2))
    );
    private String outputDirectory = "runs/";
    
    // ===== BENCHMARK STATE =====
    private SimulationConfiguration testConfig;
    private final List<Path> filesToCleanup = new java.util.ArrayList<>();
    
    // ===== TIMING TRACKING =====
    private long simulationStartTime;
    private long simulationEndTime;
    private long persistenceStartTime;
    private long persistenceEndTime;
    private long indexerStartTime;
    private long indexerEndTime;
    private long totalStartTime;
    private long totalEndTime;
    
    // ===== METRICS TRACKING =====
    private final AtomicInteger organismsAlive = new AtomicInteger(0);
    private final AtomicInteger organismsDead = new AtomicInteger(0);
    private final AtomicLong ticksPersisted = new AtomicLong(0);
    private final AtomicLong ticksIndexed = new AtomicLong(0);
    
    // ===== SERVICE REFERENCES FOR METRICS =====
    private PersistenceService persistenceService;
    private DebugIndexer debugIndexer;
    
    @BeforeAll
    static void init() {
        Instruction.init();
    }
    
    @BeforeEach
    void setUp() {
        Instruction.init();
        createOrganismConfigs(); // Erstelle Organismen-Konfigurationen
        testConfig = createTestConfiguration();
        totalStartTime = System.currentTimeMillis();
    }

    /**
     * Creates a test configuration based on the configured parameters.
     */
    private SimulationConfiguration createTestConfiguration() {
        SimulationConfiguration config = new SimulationConfiguration();
        
        // Create simulation config
        config.simulation = new SimulationConfiguration.SimulationConfig();
        
        // Environment: Use configured environment properties
        config.simulation.environment = new SimulationConfiguration.EnvironmentConfig();
        config.simulation.environment.shape = environmentProperties.getWorldShape().clone();
        config.simulation.environment.toroidal = environmentProperties.isToroidal();
        
        // No organisms in config - we'll create them manually with inline assembly code
        
        // Energy strategies: Convert from our format to the config format
        config.simulation.energyStrategies = energyStrategies.stream()
            .map(strategy -> {
                SimulationConfiguration.EnergyStrategyConfig energyConfig = new SimulationConfiguration.EnergyStrategyConfig();
                energyConfig.type = (String) strategy.get("type");
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) strategy.get("params");
                energyConfig.params = params;
                return energyConfig;
            })
            .toList();
        
        // Pipeline configuration
        config.pipeline = new SimulationConfiguration.PipelineConfig();
        
        // Simulation service config
        config.pipeline.simulation = new SimulationConfiguration.SimulationServiceConfig();
        config.pipeline.simulation.autoStart = false; // We'll start manually
        // Note: We'll set the tick limit when starting the simulation
        
        // Persistence service config
        config.pipeline.persistence = new SimulationConfiguration.PersistenceServiceConfig();
        config.pipeline.persistence.autoStart = false; // We'll start manually
        config.pipeline.persistence.batchSize = persistenceBatchSize;
        // Note: jdbcUrl will be set when we create the service manager
        
        // Indexer service config
        config.pipeline.indexer = new SimulationConfiguration.IndexerServiceConfig();
        config.pipeline.indexer.autoStart = false; // We'll start manually
        config.pipeline.indexer.batchSize = indexerBatchSize;
        
        // Server service config (not needed for benchmark)
        config.pipeline.server = new SimulationConfiguration.ServerServiceConfig();
        config.pipeline.server.autoStart = false;
        
        return config;
    }
    
    /**
     * Creates organism placements for the SimulationEngine.
     */
    private List<OrganismPlacement> createOrganismPlacements() {
        return organismConfigs.stream()
            .map(config -> {
                try {
                    // Compile the assembly program
                    Compiler compiler = new Compiler();
                    List<String> lines = List.of(config.assemblyProgram.split("\n"));
                    ProgramArtifact artifact = compiler.compile(lines, "benchmark_organism_no_opt", environmentProperties);
                    
                    // Create placement
                    return OrganismPlacement.of(artifact, config.startEnergy, config.startPosition);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to compile organism program: " + e.getMessage(), e);
                }
            })
            .toList();
    }
    
    /**
     * Creates energy strategies for the SimulationEngine.
     */
    private List<IEnergyDistributionCreator> createEnergyStrategies() {
        return energyStrategies.stream()
            .map(strategy -> {
                String type = (String) strategy.get("type");
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) strategy.get("params");
                IRandomProvider randomProvider = new org.evochora.runtime.internal.services.SeededRandomProvider(0L);
                return EnergyStrategyFactory.create(type, params, randomProvider);
            })
            .toList();
    }

    /**
     * Main benchmark test that runs the complete pipeline and measures performance.
     */
    @Test
    void complete_pipeline_benchmark() {
        // Add a unique test identifier to prevent Gradle from caching this test
        String testId = java.util.UUID.randomUUID().toString();
        System.out.println("Starting benchmark test WITHOUT my optimizations with ID: " + testId);
        // Ensure output directory exists
        try {
            Files.createDirectories(Paths.get(outputDirectory));
        } catch (java.io.IOException e) {
            System.err.println("Warning: Could not create output directory: " + e.getMessage());
        }
        
        // Create temporary database files with unique identifiers
        String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String uniqueId = testId.substring(0, 8); // Use first 8 characters of UUID
        Path rawDbFile = Paths.get(outputDirectory, "benchmark_no_opt_" + timestamp + "_" + uniqueId + "_raw.db");
        Path debugDbFile = Paths.get(outputDirectory, "benchmark_no_opt_" + timestamp + "_" + uniqueId + "_debug.db");
        filesToCleanup.add(rawDbFile);
        filesToCleanup.add(debugDbFile);
        
        String rawJdbcUrl = "jdbc:sqlite:" + rawDbFile.toString();
        String debugJdbcUrl = "jdbc:sqlite:" + debugDbFile.toString();
        
        // Phase 1: Run simulation with persistence
        runSimulationWithPersistence(rawJdbcUrl);
        
        // Phase 2: Run debug indexer (only after persistence is complete)
        runDebugIndexer(rawJdbcUrl, debugJdbcUrl);
        
        totalEndTime = System.currentTimeMillis();
        
        // Output comprehensive benchmark results
        outputBenchmarkResults(rawDbFile, debugDbFile);
    }

    /**
     * Runs the simulation with persistence service to generate raw data.
     */
    private void runSimulationWithPersistence(String rawJdbcUrl) {
        try {
            // Setup queue
            InMemoryTickQueue queue = new InMemoryTickQueue(10000);
            

            
                        // Create simulation engine with new API
            SimulationEngine engine = new SimulationEngine(
                queue,
                environmentProperties,
                createOrganismPlacements(),
                createEnergyStrategies(),
                false // skipProgramArtefact
            );
            
            // Set tick limit
            engine.setMaxTicks((long) simulationTicks);
            
            // Start simulation engine
            engine.start();
            
            // Wait for simulation to be ready
            waitForSimulationReady(engine);
            
            // Create persistence service directly WITH MY OPTIMIZATIONS DISABLED
            SimulationConfiguration.PersistenceServiceConfig persistenceConfig = new SimulationConfiguration.PersistenceServiceConfig();
            persistenceConfig.jdbcUrl = rawJdbcUrl;
            persistenceConfig.batchSize = persistenceBatchSize;
            
            // DISABLE my optimizations from config.jsonc:
            persistenceConfig.database.cacheSize = 1000; // Instead of 10000
            persistenceConfig.database.mmapSize = 1048576L; // Instead of 268MB (268435456L)
            persistenceConfig.database.pageSize = 1024; // Instead of 4096
            persistenceConfig.memoryOptimization.enabled = false; // Instead of true
            persistenceService = new PersistenceService(queue, environmentProperties, persistenceConfig);
            
            // Mark start times
            simulationStartTime = System.currentTimeMillis();
            persistenceStartTime = System.currentTimeMillis();
            
            // Start persistence service
            persistenceService.start();
            
            // Wait for simulation to complete
            while (engine.isRunning()) {
                Thread.sleep(100);
            }
            
            // Mark simulation end time
            simulationEndTime = System.currentTimeMillis();
            
            // Wait for persistence to complete
            waitForPersistenceToComplete(queue, rawJdbcUrl, simulationTicks);
            
            // Stop persistence service
            persistenceService.shutdown();
            
            // Mark persistence end time
            persistenceEndTime = System.currentTimeMillis();
            
            // Stop simulation engine
            engine.shutdown();
            
            // Collect final organism statistics
            if (engine.getSimulation() != null) {
                int finalOrganismCount = engine.getSimulation().getOrganisms().size();
                organismsAlive.set(finalOrganismCount);
                organismsDead.set(Math.max(0, organismConfigs.size() - finalOrganismCount)); // Organisms that died during simulation
            }
            
        } catch (Exception e) {
            System.err.println("Failed to run simulation with persistence: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Runs the debug indexer to process the raw database.
     */
    private void runDebugIndexer(String rawJdbcUrl, String debugJdbcUrl) {
        try {
            // Setup debug indexer WITH MY OPTIMIZATIONS DISABLED
            SimulationConfiguration.IndexerServiceConfig indexerConfig = new SimulationConfiguration.IndexerServiceConfig();
            indexerConfig.batchSize = indexerBatchSize;
            
            // DISABLE my optimizations from config.jsonc:
            indexerConfig.compression.enabled = indexerComprssionEnabled; // Instead of true
            indexerConfig.database.cacheSize = 1000; // Instead of 10000
            indexerConfig.database.mmapSize = 1048576L; // Instead of 268MB (268435456L)
            indexerConfig.database.pageSize = 1024; // Instead of 4096
            indexerConfig.parallelProcessing.enabled = false; // Instead of true
            indexerConfig.parallelProcessing.threadCount = 1; // Instead of auto-detection
            indexerConfig.memoryOptimization.enabled = false; // Instead of true
            debugIndexer = new DebugIndexer(rawJdbcUrl, debugJdbcUrl, indexerConfig);
            
            // Mark start time
            indexerStartTime = System.currentTimeMillis();
            
            // Start indexer
            debugIndexer.start();
            
            // Wait for indexer to start processing
            waitForIndexerToStartProcessing(debugIndexer);
            
            // Wait for indexer to complete
            // Note: getLastProcessedTick() returns nextTickToProcess - 1, so we need to account for this
            long actualProcessedTicks = waitForIndexerToComplete(debugIndexer, simulationTicks);
            ticksIndexed.set(actualProcessedTicks);
            
            // Stop indexer
            debugIndexer.shutdown();
            
            // Mark end time
            indexerEndTime = System.currentTimeMillis();
            
        } catch (Exception e) {
            System.err.println("Failed to run debug indexer: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Waits for the simulation to be ready by checking the simulation engine.
     */
    private void waitForSimulationReady(SimulationEngine engine) {
        long timeout = System.currentTimeMillis() + simulationReadyTimeoutMs;
        while (System.currentTimeMillis() < timeout) {
            try {
                if (engine.getSimulation() != null) {
                    return;
                }
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Waits for persistence to complete by checking both queue size and database tick count.
     */
    private void waitForPersistenceToComplete(InMemoryTickQueue queue, String jdbcUrl, int expectedTicks) {
        long timeout = System.currentTimeMillis() + persistenceCompleteTimeoutMs;
        while (System.currentTimeMillis() < timeout) {
            try {
                if (queue.size() == 0) {
                    long ticksInDb = countTicksInRawDatabase(jdbcUrl);
                    if (ticksInDb >= expectedTicks) {
                        ticksPersisted.set(ticksInDb);
                        return;
                    }
                }
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.err.println("WARNING: Persistence timeout after " + persistenceCompleteTimeoutMs + "ms");
    }

    /**
     * Waits for the debug indexer to start processing.
     */
    private void waitForIndexerToStartProcessing(DebugIndexer indexer) {
        long timeout = System.currentTimeMillis() + indexerStartTimeoutMs;
        while (indexer.getLastProcessedTick() == 0 && indexer.isRunning() && System.currentTimeMillis() < timeout) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Waits for the debug indexer to complete processing.
     */
    private long waitForIndexerToComplete(DebugIndexer indexer, int targetTicks) {
        long timeout = System.currentTimeMillis() + indexerCompleteTimeoutMs;
        long processedTicks = 0;
        
        // getLastProcessedTick() returns nextTickToProcess - 1, so for targetTicks=10000,
        // we expect getLastProcessedTick() to return 9999 (meaning 10000 ticks processed: 0-9999)
        long expectedLastProcessedTick = targetTicks - 1;
        
        while (processedTicks < expectedLastProcessedTick && indexer.isRunning() && System.currentTimeMillis() < timeout) {
            try {
                Thread.sleep(50);
                processedTicks = indexer.getLastProcessedTick();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        if (processedTicks < expectedLastProcessedTick && System.currentTimeMillis() >= timeout) {
            System.err.println("WARNING: Indexer timeout after " + indexerCompleteTimeoutMs + "ms - processed " + processedTicks + " ticks (target was " + targetTicks + ", expected last processed tick: " + expectedLastProcessedTick + ")");
        }
        
        // Return the actual number of ticks processed (processedTicks + 1)
        return processedTicks + 1;
    }

    /**
     * Counts the number of ticks in the raw database.
     */
    private long countTicksInRawDatabase(String jdbcUrl) {
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(jdbcUrl);
             java.sql.Statement stmt = conn.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM raw_ticks WHERE tick_data_json LIKE '%\"organisms\"%'")) {
            
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (Exception e) {
            System.err.println("Failed to count ticks in raw database: " + e.getMessage());
        } finally {
            // Force garbage collection to help with connection cleanup
            System.gc();
        }
        return 0;
    }

    /**
     * Outputs comprehensive benchmark results.
     */
    private void outputBenchmarkResults(Path rawDbFile, Path debugDbFile) {
        // Calculate durations
        double simulationDuration = (simulationEndTime - simulationStartTime) / 1000.0;
        double persistenceDuration = (persistenceEndTime - persistenceStartTime) / 1000.0;
        double indexerDuration = (indexerEndTime - indexerStartTime) / 1000.0;
        double totalDuration = (totalEndTime - totalStartTime) / 1000.0;
        
        // Calculate performance metrics
        double simulationTicksPerSecond = simulationTicks / simulationDuration;
        double persistenceTicksPerSecond = ticksPersisted.get() / persistenceDuration;
        double indexerTicksPerSecond = ticksIndexed.get() / indexerDuration;
        
        // Calculate database sizes and throughput
        long rawDbSize = 0;
        long debugDbSize = 0;
        try {
            rawDbSize = Files.size(rawDbFile);
            debugDbSize = Files.size(debugDbFile);
        } catch (java.io.IOException e) {
            System.err.println("Warning: Could not determine database sizes: " + e.getMessage());
        }
        
        double rawThroughput = (rawDbSize / (1024.0 * 1024.0)) / persistenceDuration; // MB/s
        double debugThroughput = (debugDbSize / (1024.0 * 1024.0)) / indexerDuration; // MB/s
        
        // Calculate effective batch sizes using actual batch counts
        int persistenceBatchCount = persistenceService != null ? persistenceService.getBatchCount() : 0;
        int indexerBatchCount = debugIndexer != null ? debugIndexer.getBatchCount() : 0;
        
        double persistenceEffectiveBatchSize = calculateEffectiveBatchSize((int) ticksPersisted.get(), persistenceDuration, persistenceBatchSize, persistenceBatchCount);
        double indexerEffectiveBatchSize = calculateEffectiveBatchSize((int) ticksIndexed.get(), indexerDuration, indexerBatchSize, indexerBatchCount);
        
        System.out.println("\n=== EVOCHORA PIPELINE BENCHMARK RESULTS (WITHOUT MY OPTIMIZATIONS) ===");
        System.out.println("Configuration:");
        System.out.printf("  Simulation ticks: %d%n", simulationTicks);
        System.out.printf("  World shape: %s%n", java.util.Arrays.toString(environmentProperties.getWorldShape()));
        System.out.printf("  Toroidal: %s%n", environmentProperties.isToroidal());
        System.out.printf("  Organisms configured: %d%n", organismConfigs.size());
        System.out.printf("  Persistence batch size: %d%n", persistenceBatchSize);
        System.out.printf("  Indexer batch size: %d%n", indexerBatchSize);
        System.out.printf("  Energy strategies: %d%n", energyStrategies.size());
        System.out.printf("  Compression: enabled %s%n", indexerComprssionEnabled ? "True" : "False");
        System.out.println("⚠️  OPTIMIZATIONS DISABLED:");
        System.out.println("   • Database Cache: 1000 (instead of 10000)");
        System.out.println("   • MMAP Size: 1MB (instead of 268MB)");
        System.out.println("   • Page Size: 1024 (instead of 4096)");
        System.out.println("   • Memory Optimization: disabled");
        System.out.println("   • Multi-Core Processing: disabled (1 thread only)");
        System.out.println("Timeouts:");
        System.out.printf("  Simulation ready: %d ms%n", simulationReadyTimeoutMs);
        System.out.printf("  Simulation complete: %d ms%n", simulationCompleteTimeoutMs);
        System.out.printf("  Persistence complete: %d ms%n", persistenceCompleteTimeoutMs);
        System.out.printf("  Indexer start: %d ms%n", indexerStartTimeoutMs);
        System.out.printf("  Indexer complete: %d ms%n", indexerCompleteTimeoutMs);
        System.out.println("Cleanup:");
        System.out.printf("  Database cleanup: %s%n", cleanUpDb ? "enabled" : "disabled");
        
        System.out.println("\nSimulation Results:");
        System.out.printf("  Duration: %.2f seconds%n", simulationDuration);
        System.out.printf("  Processed ticks: %d%n", simulationTicks);
        System.out.printf("  Organisms alive: %d%n", organismsAlive.get());
        System.out.printf("  Organisms dead: %d%n", organismsDead.get());
        System.out.printf("  Ticks per second: %.2f%n", simulationTicksPerSecond);
        
        System.out.println("\nPersistence Service Results:");
        System.out.printf("  Duration: %.2f seconds%n", persistenceDuration);
        System.out.printf("  Processed ticks: %d%n", ticksPersisted.get());
        System.out.printf("  Configured batch size: %d%n", persistenceBatchSize);
        System.out.printf("  Actual batches processed: %d%n", persistenceBatchCount);
        System.out.printf("  Effective batch size: %.1f%n", persistenceEffectiveBatchSize);
        System.out.printf("  Raw database size: %.2f MB%n", rawDbSize / (1024.0 * 1024.0));
        System.out.printf("  Database throughput: %.2f MB/s%n", rawThroughput);
        System.out.printf("  Ticks per second: %.2f%n", persistenceTicksPerSecond);
        
        System.out.println("\nDebug Indexer Results:");
        System.out.printf("  Duration: %.2f seconds%n", indexerDuration);
        System.out.printf("  Processed ticks: %d%n", ticksIndexed.get());
        System.out.printf("  Configured batch size: %d%n", indexerBatchSize);
        System.out.printf("  Actual batches processed: %d%n", indexerBatchCount);
        System.out.printf("  Effective batch size: %.1f%n", indexerEffectiveBatchSize);
        System.out.printf("  Debug database size: %.2f MB%n", debugDbSize / (1024.0 * 1024.0));
        System.out.printf("  Database throughput: %.2f MB/s%n", debugThroughput);
        System.out.printf("  Ticks per second: %.2f%n", indexerTicksPerSecond);
        
        System.out.printf("\nTotal Pipeline Duration: %.2f seconds%n", totalDuration);
        System.out.println("=== END BENCHMARK RESULTS (WITHOUT MY OPTIMIZATIONS) ===\n");
    }

    /**
     * Calculates the effective batch size based on processing time and throughput.
     */
    private double calculateEffectiveBatchSize(int totalTicks, double durationSeconds, int configuredBatchSize, int actualBatchCount) {
        if (durationSeconds <= 0 || actualBatchCount <= 0) return 0.0;
        
        // Calculate the average batch size based on actual processing
        double averageBatchSize = (double) totalTicks / actualBatchCount;
        
        return averageBatchSize;
    }



    /**
     * Cleanup method to remove temporary files created during benchmarks.
     */
    @AfterEach
    void cleanup() {
        // Ensure all services are properly stopped first
        if (persistenceService != null) {
            try {
                persistenceService.shutdown();
                // Give it time to close database connections
                Thread.sleep(100);
            } catch (Exception e) {
                System.err.println("Error stopping persistence service: " + e.getMessage());
            }
        }
        
        if (debugIndexer != null) {
            try {
                debugIndexer.shutdown();
                // Give it time to close database connections
                Thread.sleep(100);
            } catch (Exception e) {
                System.err.println("Error stopping debug indexer: " + e.getMessage());
            }
        }
        
        if (!cleanUpDb) {
            System.out.println("Database cleanup disabled - keeping files: " + filesToCleanup);
            return;
        }
        
        for (Path file : filesToCleanup) {
            try {
                if (Files.exists(file)) {
                    // Try to delete with retry logic for Windows file locking issues
                    boolean deleted = false;
                    for (int attempt = 0; attempt < 5 && !deleted; attempt++) {
                        try {
                            Files.delete(file);
                            System.out.println("Cleaned up: " + file);
                            deleted = true;
                        } catch (Exception e) {
                            if (attempt < 4) {
                                try {
                                    Thread.sleep(500); // Wait 500ms before retry
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            } else {
                                System.err.println("Failed to cleanup " + file + " after 5 attempts: " + e.getMessage());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to cleanup " + file + ": " + e.getMessage());
            }
        }
        filesToCleanup.clear();
    }
}
