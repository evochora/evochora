package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.ServiceManager;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.services.IService.State;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.evochora.test.utils.FileUtils.readAllTicksFromBatches;

/**
 * Integration tests for end-to-end persistence flow with real resources.
 * Tests the complete pipeline: SimulationEngine → Queue → PersistenceService → Storage.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class SimulationToPersistenceIntegrationTest {

    private static final int SAMPLING_INTERVAL = 10;

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
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "PersistenceService initialized WITHOUT batch-topic - event-driven indexing disabled!")
    void testEndToEndPersistence() {
        Config config = createIntegrationConfig();
        serviceManager = new ServiceManager(config);
        serviceManager.startAll();

        await().atMost(20, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> !readAllTicksFromBatches(tempStorageDir).isEmpty());

        assertStoredExactlySentOncePaused();
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "PersistenceService initialized WITHOUT batch-topic - event-driven indexing disabled!", occurrences = 2)
    void testMultiplePersistenceInstances() {
        Config config = createMultiInstanceConfig();
        serviceManager = new ServiceManager(config);
        serviceManager.startAll();

        await().atMost(20, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> readAllTicksFromBatches(tempStorageDir).size() >= 10);

        // Competing consumers share the ticks between them: none may be lost or stored twice
        assertStoredExactlySentOncePaused();
    }

    /**
     * A restart of the persistence service while the simulation keeps producing loses no tick and
     * stores none twice.
     * <p>
     * The restart happens while data flows, which is where a stop could lose what the service has
     * taken from the queue but not yet written.
     */
    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "PersistenceService initialized WITHOUT batch-topic - event-driven indexing disabled!",
               occurrences = 2)
    void testGracefulShutdown() {
        Config config = createIntegrationConfig();
        serviceManager = new ServiceManager(config);
        serviceManager.startAll();

        await().atMost(20, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> !readAllTicksFromBatches(tempStorageDir).isEmpty());

        // Restart the persistence service while the simulation keeps producing
        serviceManager.stopService("persistence-1");
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> serviceManager.getServiceStatus("persistence-1").state() == State.STOPPED);
        long sentBeforeRestart = sentTicks();
        serviceManager.startService("persistence-1");

        // Data has to pass the restart: the new instance stores ticks sent after it began
        await().atMost(20, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> readAllTicksFromBatches(tempStorageDir).stream()
                .anyMatch(tick -> tick.getTickNumber() > sentBeforeRestart * SAMPLING_INTERVAL));

        assertStoredExactlySentOncePaused();
    }

    /**
     * Pauses the simulation and waits until the stored ticks are exactly the ticks it sent, each
     * once, all of one run.
     * <p>
     * The comparison needs a fixed state: storage read while it is being written may list a file
     * and miss an older one. A check for gaps alone would also pass a loss at the end. A tick on
     * its way when the pause came may still be sent, so the count is read anew in every attempt;
     * once everything sent is stored, the two agree.
     */
    private void assertStoredExactlySentOncePaused() {
        serviceManager.pauseService("simulation-engine");
        await().atMost(20, java.util.concurrent.TimeUnit.SECONDS)
            .pollInterval(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            .untilAsserted(() -> {
                long sent = sentTicks();
                List<TickData> ticks = readAllTicksFromBatches(tempStorageDir);
                List<Long> stored = ticks.stream()
                    .map(TickData::getTickNumber)
                    .sorted()
                    .collect(Collectors.toList());
                List<Long> expected = java.util.stream.LongStream.range(0, sent)
                    .map(i -> i * SAMPLING_INTERVAL)
                    .boxed()
                    .collect(Collectors.toList());
                assertEquals(expected, stored, "the stored ticks are not exactly the " + sent + " ticks sent");
                assertEquals(1, ticks.stream().map(TickData::getSimulationRunId).distinct().count(),
                    "the stored ticks belong to more than one run");
            });
    }

    /** The number of sampled ticks the simulation has sent to the queue. */
    private long sentTicks() {
        return serviceManager.getServiceStatus("simulation-engine").metrics().get("messages_sent").longValue();
    }

    private Config createIntegrationConfig() {
        return ConfigFactory.parseMap(Map.of(
            "pipeline", Map.of(
                // The tests start the services themselves; an automatic start would make
                // their own start a second one.
                "autoStart", false,
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
                            "runtime", Map.of(
                                "organism", Map.of(
                                    "max-energy", 32767,
                                    "max-entropy", 8191,
                                    "error-penalty-cost", 500
                                ),
                                "thermodynamics", Map.of(
                                    "default", Map.of(
                                        "className", "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy",
                                        "options", Map.of(
                                            "base-energy", 1,
                                            "base-entropy", 1
                                        )
                                    ),
                                    "overrides", Map.of(
                                        "instructions", Map.of(),
                                        "families", Map.of()
                                    )
                                )
                            ),
                            "samplingInterval", SAMPLING_INTERVAL,
                            // Delta compression: 1 sample = 1 chunk for testing
                            "accumulatedDeltaInterval", 1,
                            "snapshotInterval", 1,
                            "chunkInterval", 1,
                            "environment", Map.of(
                                "shape", List.of(64, 32),
                                "topology", "TORUS"
                            ),
                            "organisms", List.of(Map.of(
                                "program", programFile.toString(),
                                "initialEnergy", 10000,
                                "placement", Map.of("positions", List.of(5, 5))
                            )),
                            "plugins", Collections.emptyList()
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
}
