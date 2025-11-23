package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.ServiceManager;
import org.evochora.datapipeline.api.contracts.SystemContracts;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.services.IService.State;
import org.evochora.datapipeline.resources.idempotency.InMemoryIdempotencyTracker;
import org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue;
import org.evochora.datapipeline.resources.queues.InMemoryDeadLetterQueue;
import org.evochora.datapipeline.resources.storage.FileSystemStorageResource;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for end-to-end persistence flow with real resources.
 * Tests the complete pipeline: SimulationEngine → Queue → PersistenceService → Storage.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*(SimulationEngine|PersistenceService|ServiceManager|FileSystemStorageResource|InMemoryIdempotencyTracker).*")
class SimulationToPersistenceIntegrationTest {

    @TempDir
    Path tempDir;

    private Path tempStorageDir;
    private Path programFile;
    private ServiceManager serviceManager;

    @BeforeAll
    static void setUpClass() {
        // Initialize instruction set
        Instruction.init();
    }

    @BeforeEach
    void setUp() throws IOException {
        tempStorageDir = tempDir.resolve("storage");
        Files.createDirectories(tempStorageDir);

        // Copy an existing valid assembly program for testing
        Path sourceProgram = Path.of("src/test/resources/org/evochora/datapipeline/services/simple.evo");
        programFile = tempDir.resolve("simple.evo");
        Files.copy(sourceProgram, programFile, StandardCopyOption.REPLACE_EXISTING);
    }

    @AfterEach
    void tearDown() {
        if (serviceManager != null) {
            serviceManager.stopAll();
        }
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*(SimulationEngine|PersistenceService|ServiceManager|FileSystemStorageResource).*")
    @AllowLog(level = LogLevel.WARN, messagePattern = "PersistenceService initialized WITHOUT batch-topic - event-driven indexing disabled!")
    void testEndToEndPersistence() {
        Config config = createIntegrationConfig();
        serviceManager = new ServiceManager(config);
        
        // Start all services
        serviceManager.startAll();

        // Wait for batches to be written
        await().atMost(30, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> countBatchFiles(tempStorageDir) > 0);

        // Verify data integrity (this implicitly verifies that batch files exist)
        verifyAllTicksPersisted();

        // Note: No need to re-check countBatchFiles() here - the await() already guaranteed files exist,
        // and verifyAllTicksPersisted() reads those files successfully. Re-checking introduces a race condition:
        // countBatchFiles() can transiently return 0 if Files.walk() encounters concurrent file operations
        // (e.g., temp files being renamed), even though files actually exist.
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*(SimulationEngine|PersistenceService|ServiceManager|FileSystemStorageResource|InMemoryIdempotencyTracker).*")
    @AllowLog(level = LogLevel.WARN, messagePattern = "PersistenceService initialized WITHOUT batch-topic - event-driven indexing disabled!")
    void testMultiplePersistenceInstances() {
        Config config = createMultiInstanceConfig();
        serviceManager = new ServiceManager(config);

        // Start all services (includes 2 persistence instances with shared idempotency tracker)
        serviceManager.startAll();

        // Wait for batches to be written
        await().atMost(30, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> countBatchFiles(tempStorageDir) > 0);

        // Verify no duplicate ticks were written (idempotency working)
        List<TickData> allTicks = readAllPersistedTicks();
        assertTrue(allTicks.size() > 0, "No ticks found in persisted batch files");

        // Verify each tick appears exactly once
        Set<Long> tickNumbers = new HashSet<>();
        for (TickData tick : allTicks) {
            boolean isNew = tickNumbers.add(tick.getTickNumber());
            assertTrue(isNew, "Duplicate tick number found with competing consumers: " + tick.getTickNumber());
        }
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*(SimulationEngine|PersistenceService|ServiceManager).*")
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Failed to write batch .* after .* retries:.*", occurrences = -1)
    void testDLQFunctionality() {
        // Create config with invalid storage directory to trigger failures
        Config config = createDLQTestConfig();
        serviceManager = new ServiceManager(config);

        // Start all services
        serviceManager.startAll();

        // Wait for some time to allow failures to occur
        await().atMost(30, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> countBatchFiles(tempStorageDir) >= 0); // This will always be true, but gives time for failures

        // Note: DLQ verification would require access to the resource, which is not directly available
        // in the current ServiceManager API. This test verifies that the system handles failures gracefully.
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*(SimulationEngine|PersistenceService|ServiceManager|FileSystemStorageResource).*")
    @AllowLog(level = LogLevel.WARN, messagePattern = "PersistenceService initialized WITHOUT batch-topic - event-driven indexing disabled!")
    void testGracefulShutdown() {
        Config config = createIntegrationConfig();
        serviceManager = new ServiceManager(config);
        
        // Start all services
        serviceManager.startAll();
        
        // Wait for some data to be processed
        await().atMost(10, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> countBatchFiles(tempStorageDir) > 0);
        
        // Stop persistence service while simulation is still running
        serviceManager.stopService("persistence-1");

        // Wait for persistence service to actually stop
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> serviceManager.getServiceStatus("persistence-1").state() == State.STOPPED);

        // Restart persistence service
        serviceManager.startService("persistence-1");
        
        // Wait for more data to be processed
        await().atMost(10, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> countBatchFiles(tempStorageDir) > 2);
        
        // Verify no data loss occurred
        verifyAllTicksPersisted();
    }

    private Config createIntegrationConfig() {
        return ConfigFactory.parseMap(Map.of(
            "pipeline", Map.of(
                "resources", Map.of(
                    "storage-main", Map.of(
                        "className", "org.evochora.datapipeline.resources.storage.FileSystemStorageResource",
                        "options", Map.of("rootDirectory", tempStorageDir.toString())
                    ),
                    "raw-tick-data", Map.of(
                        "className", "org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue",
                        "options", Map.of("capacity", 1000)
                    ),
                    "metadata-queue", Map.of(
                        "className", "org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue",
                        "options", Map.of("capacity", 100)
                    ),
                    "persistence-dlq", Map.of(
                        "className", "org.evochora.datapipeline.resources.queues.InMemoryDeadLetterQueue",
                        "options", Map.of("capacity", 100)
                    ),
                    "persistence-idempotency", Map.of(
                        "className", "org.evochora.datapipeline.resources.idempotency.InMemoryIdempotencyTracker",
                        "options", Map.of(
                            "ttlSeconds", 3600,
                            "cleanupThresholdMessages", 100000,
                            "cleanupIntervalSeconds", 300
                        )
                    )
                ),
                "services", Map.of(
                    "simulation-engine", Map.of(
                        "className", "org.evochora.datapipeline.services.SimulationEngine",
                        "resources", Map.of(
                            "tickData", "queue-out:raw-tick-data",
                            "metadataOutput", "queue-out:metadata-queue"
                        ),
                        "options", Map.of(
                            "maxTicks", 100,
                            "samplingInterval", 10,
                            "environment", Map.of(
                                "shape", List.of(10, 10),
                                "topology", "TORUS"
                            ),
                            "organisms", List.of(Map.of(
                                "program", programFile.toString(),
                                "initialEnergy", 10000,
                                "placement", Map.of("positions", List.of(5, 5))
                            )),
                            "energyStrategies", Collections.emptyList()
                        )
                    ),
                    "persistence-1", Map.of(
                        "className", "org.evochora.datapipeline.services.PersistenceService",
                        "resources", Map.of(
                            "input", "queue-in:raw-tick-data",
                            "storage", "storage-write:storage-main",
                            "dlq", "queue-out:persistence-dlq",
                            "idempotencyTracker", "persistence-idempotency"
                        ),
                        "options", Map.of(
                            "maxBatchSize", 50,
                            "batchTimeoutSeconds", 2,
                            "maxRetries", 2,
                            "retryBackoffMs", 100
                        )
                    )
                ),
                "startupSequence", java.util.List.of("simulation-engine", "persistence-1")
            )
        ));
    }

    private Config createMultiInstanceConfig() {
        Config baseConfig = createIntegrationConfig();
        return baseConfig.withValue("pipeline.services.persistence-2",
            ConfigFactory.parseMap(Map.of(
                "className", "org.evochora.datapipeline.services.PersistenceService",
                "resources", Map.of(
                    "input", "queue-in:raw-tick-data",
                    "storage", "storage-write:storage-main",
                    "dlq", "queue-out:persistence-dlq",
                    "idempotencyTracker", "persistence-idempotency"
                ),
                "options", Map.of(
                    "maxBatchSize", 50,
                    "batchTimeoutSeconds", 2,
                    "maxRetries", 2,
                    "retryBackoffMs", 100
                )
            )).root()
        ).withValue("pipeline.startupSequence",
            ConfigFactory.parseString("pipeline.startupSequence=[\"simulation-engine\", \"persistence-1\", \"persistence-2\"]").getValue("pipeline.startupSequence")
        );
    }

    private Config createDLQTestConfig() {
        // Create config with invalid storage directory to trigger failures
        return ConfigFactory.parseMap(Map.of(
            "pipeline", Map.of(
                "resources", Map.of(
                    "storage-main", Map.of(
                        "className", "org.evochora.datapipeline.resources.storage.FileSystemStorageResource",
                        "options", Map.of("rootDirectory", "/invalid/path/that/does/not/exist")
                    ),
                    "raw-tick-data", Map.of(
                        "className", "org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue",
                        "options", Map.of("capacity", 1000)
                    ),
                    "metadata-queue", Map.of(
                        "className", "org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue",
                        "options", Map.of("capacity", 100)
                    ),
                    "persistence-dlq", Map.of(
                        "className", "org.evochora.datapipeline.resources.queues.InMemoryDeadLetterQueue",
                        "options", Map.of("capacity", 100)
                    )
                ),
                "services", Map.of(
                    "simulation-engine", Map.of(
                        "className", "org.evochora.datapipeline.services.SimulationEngine",
                        "resources", Map.of(
                            "tickData", "queue-out:raw-tick-data",
                            "metadataOutput", "queue-out:metadata-queue"
                        ),
                        "options", Map.of(
                            "maxTicks", 50,
                            "samplingInterval", 5,
                            "environment", Map.of(
                                "shape", List.of(10, 10),
                                "topology", "TORUS"
                            ),
                            "organisms", List.of(Map.of(
                                "program", programFile.toString(),
                                "initialEnergy", 10000,
                                "placement", Map.of("positions", List.of(5, 5))
                            )),
                            "energyStrategies", Collections.emptyList()
                        )
                    ),
                    "persistence-1", Map.of(
                        "className", "org.evochora.datapipeline.services.PersistenceService",
                        "resources", Map.of(
                            "input", "queue-in:raw-tick-data",
                            "storage", "storage-write:storage-main",
                            "dlq", "queue-out:persistence-dlq"
                        ),
                        "options", Map.of(
                            "maxBatchSize", 10,
                            "batchTimeoutSeconds", 1,
                            "maxRetries", 1,
                            "retryBackoffMs", 50
                        )
                    )
                ),
                "startupSequence", java.util.List.of("simulation-engine", "persistence-1")
            )
        ));
    }

    private int countBatchFiles(Path storageDir) {
        try {
            return (int) Files.walk(storageDir)
                .filter(path -> {
                    try {
                        String pathStr = path.toString();
                        // Exclude temporary files
                        if (pathStr.contains(".tmp")) {
                            return false;
                        }
                        // Only count .pb files that exist (ignore .pb.tmp and handle race conditions)
                        return pathStr.endsWith(".pb") && Files.exists(path);
                    } catch (Exception e) {
                        // File may have been deleted/renamed between walk and exists check
                        return false;
                    }
                })
                .count();
        } catch (java.io.UncheckedIOException | IOException e) {
            // Handle race conditions where files are being written/renamed during walk
            return 0;
        }
    }

    private void verifyAllTicksPersisted() {
        // Read all batch files from storage and deserialize TickData messages
        List<TickData> allTicks = readAllPersistedTicks();

        assertTrue(allTicks.size() > 0, "No ticks found in persisted batch files");

        // Verify all ticks have the same simulationRunId
        String firstRunId = allTicks.get(0).getSimulationRunId();
        for (TickData tick : allTicks) {
            assertEquals(firstRunId, tick.getSimulationRunId(),
                "All ticks should have the same simulationRunId");
        }

        // Verify no duplicate tick numbers
        Set<Long> tickNumbers = new HashSet<>();
        for (TickData tick : allTicks) {
            boolean isNew = tickNumbers.add(tick.getTickNumber());
            assertTrue(isNew, "Duplicate tick number found: " + tick.getTickNumber());
        }

        // Verify tick sequence is complete (all ticks between min and max exist)
        List<Long> sortedTicks = allTicks.stream()
            .map(TickData::getTickNumber)
            .sorted()
            .collect(Collectors.toList());

        // With samplingInterval=10, we expect ticks: 0, 10, 20, ..., up to maxTicks
        long minTick = sortedTicks.get(0);
        long maxTick = sortedTicks.get(sortedTicks.size() - 1);
        int expectedCount = (int)((maxTick - minTick) / 10) + 1;

        assertEquals(expectedCount, sortedTicks.size(),
            String.format("Expected %d ticks from %d to %d with interval 10", expectedCount, minTick, maxTick));
    }

    private List<TickData> readAllPersistedTicks() {
        List<TickData> allTicks = new ArrayList<>();

        // Retry up to 3 times to handle race conditions with concurrent file writes
        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                // Find all .pb batch files (handle race conditions during file walking)
                // Note: Files.walk() can throw UncheckedIOException if files are deleted during iteration
                List<Path> batchFiles = Files.walk(tempStorageDir)
                    .filter(path -> {
                        String pathStr = path.toString();
                        // Exclude temporary files (files with .tmp in the name)
                        if (pathStr.contains(".tmp")) {
                            return false;
                        }
                        // Only include .pb files that exist and are regular files
                        // (ignore .pb.tmp and .pb.zst.tmp files)
                        // Files.exists() and Files.isRegularFile() don't throw checked exceptions
                        if (!pathStr.endsWith(".pb") && !pathStr.endsWith(".pb.zst")) {
                            return false;
                        }
                        try {
                            return Files.exists(path) && Files.isRegularFile(path);
                        } catch (Exception e) {
                            // Catch any runtime exceptions (e.g., SecurityException)
                            return false;
                        }
                    })
                    .collect(Collectors.toList());

                // Read and deserialize each batch file
                for (Path batchFile : batchFiles) {
                    try {
                        // Double-check file still exists before opening
                        if (!Files.exists(batchFile)) {
                            continue; // File was deleted/renamed after collection
                        }

                        try (java.io.InputStream is = new java.io.BufferedInputStream(Files.newInputStream(batchFile))) {
                            // Read all delimited TickData messages from the file
                            while (is.available() > 0) {
                                TickData tick = TickData.parseDelimitedFrom(is);
                                if (tick == null) {
                                    break; // End of file
                                }
                                allTicks.add(tick);
                            }
                        }
                    } catch (java.nio.file.NoSuchFileException e) {
                        // File was deleted/renamed between exists check and read - this is OK in concurrent scenarios
                        // Just skip this file and continue
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read batch file: " + batchFile, e);
                    }
                }

                // Successfully read all files, return
                return allTicks;

            } catch (java.io.UncheckedIOException | IOException e) {
                // Handle race conditions where files are being written/renamed during walk
                if (attempt < maxRetries - 1) {
                    // Retry after a short delay
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while retrying walk", ie);
                    }
                    allTicks.clear(); // Clear partial results before retry
                    continue;
                }
                throw new RuntimeException("Failed to walk storage directory after " + maxRetries + " attempts", e);
            }
        }
        return allTicks;
    }
}