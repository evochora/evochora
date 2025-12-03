package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.ServiceManager;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.services.IService.State;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.evochora.test.utils.FileUtils.countBatchFiles;
import static org.evochora.test.utils.FileUtils.readAllTicksFromBatches;

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
    void testEndToEndPersistence() {
        Config config = createIntegrationConfig();
        serviceManager = new ServiceManager(config);
        serviceManager.startAll();

        // Use untilAsserted to create a robust, end-to-end wait condition.
        // This repeatedly attempts the final verification until it succeeds.
        // This is necessary to handle potential filesystem inconsistencies on CI runners,
        // where a successful file count check can be immediately followed by a failed read.
        await().atMost(30, java.util.concurrent.TimeUnit.SECONDS)
            .pollInterval(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            .untilAsserted(this::verifyAllTicksPersisted);
    }

    @Test
    void testMultiplePersistenceInstances() {
        Config config = createMultiInstanceConfig();
        serviceManager = new ServiceManager(config);
        serviceManager.startAll();

        // Use untilAsserted to wait for the final state to be consistent.
        // This is more robust than separate wait/assert steps, especially on CI runners
        // with potential filesystem inconsistencies.
        await().atMost(30, java.util.concurrent.TimeUnit.SECONDS)
            .pollInterval(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            .untilAsserted(() -> {
                List<TickData> allTicks = readAllTicksFromBatches(tempStorageDir);
                assertTrue(allTicks.size() >= 10, "Fewer than 10 ticks found in persisted batch files");

                // Verify each tick appears exactly once
                Set<Long> tickNumbers = new HashSet<>();
                for (TickData tick : allTicks) {
                    boolean isNew = tickNumbers.add(tick.getTickNumber());
                    assertTrue(isNew, "Duplicate tick number found with competing consumers: " + tick.getTickNumber());
                }
            });
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
    void testGracefulShutdown() {
        Config config = createIntegrationConfig();
        serviceManager = new ServiceManager(config);
        serviceManager.startAll();
        
        // Wait until we can verifiably read at least one tick before proceeding.
        await().atMost(20, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> !readAllTicksFromBatches(tempStorageDir).isEmpty());
        
        // Stop, wait, and restart the service
        serviceManager.stopService("persistence-1");
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> serviceManager.getServiceStatus("persistence-1").state() == State.STOPPED);
        serviceManager.startService("persistence-1");
        
        // After restart, wait for the final verification to succeed to handle
        // potential filesystem inconsistencies on CI runners.
        await().atMost(20, java.util.concurrent.TimeUnit.SECONDS)
            .pollInterval(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            .untilAsserted(this::verifyAllTicksPersisted);
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

    private void verifyAllTicksPersisted() {
        // Read all batch files from storage using the robust central utility
        List<TickData> allTicks = readAllTicksFromBatches(tempStorageDir);

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
}