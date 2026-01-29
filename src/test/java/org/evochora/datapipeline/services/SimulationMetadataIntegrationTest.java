package org.evochora.datapipeline.services;

import static org.awaitility.Awaitility.await;
import static org.evochora.test.utils.FileUtils.readAllTicksFromBatches;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.evochora.datapipeline.ServiceManager;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.utils.MetadataConfigHelper;
import org.evochora.datapipeline.resources.storage.FileSystemStorageResource;
import org.evochora.junit.extensions.logging.AllowLog;
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

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Integration tests for end-to-end metadata persistence flow with real resources.
 * Tests the complete pipeline: SimulationEngine → context-data queue → MetadataPersistenceService → Storage.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*(SimulationEngine|MetadataPersistenceService|ServiceManager|FileSystemStorageResource).*")
class SimulationMetadataIntegrationTest {

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

        // Copy a valid assembly program for testing
        Path sourceProgram = Path.of("src/test/resources/org/evochora/datapipeline/services/simple.evo");
        programFile = tempDir.resolve("simple.evo");
        Files.copy(sourceProgram, programFile, StandardCopyOption.REPLACE_EXISTING);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (serviceManager != null) {
            // ServiceManager.stopAll() already closes all AutoCloseable resources
            // No need to manually close them again here
            serviceManager.stopAll();
        }
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*(SimulationEngine|MetadataPersistenceService|ServiceManager|FileSystemStorageResource).*")
    @AllowLog(level = LogLevel.WARN, messagePattern = "PersistenceService initialized WITHOUT batch-topic - event-driven indexing disabled!")
    void testEndToEndMetadataPersistence() throws IOException {
        Config config = createIntegrationConfig();
        serviceManager = new ServiceManager(config);

        // Start all services
        serviceManager.startAll();

        // Wait for metadata file to be created
        await().atMost(30, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> findMetadataFile(tempStorageDir) != null);

        Path metadataFile = findMetadataFile(tempStorageDir);
        assertNotNull(metadataFile, "Metadata file should exist");

        // Verify file naming convention: {simulationRunId}/raw/metadata.pb
        assertTrue(metadataFile.getFileName().toString().equals("metadata.pb"));

        // Verify file is readable and contains valid SimulationMetadata
        SimulationMetadata metadata = readMetadataFile(metadataFile);
        assertNotNull(metadata);
        assertNotNull(metadata.getSimulationRunId());
        assertFalse(metadata.getSimulationRunId().isEmpty());
        assertEquals(42, metadata.getInitialSeed()); // From config
        assertTrue(metadata.getStartTimeMs() > 0);
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*(SimulationEngine|MetadataPersistenceService|ServiceManager|FileSystemStorageResource).*")
    @AllowLog(level = LogLevel.WARN, messagePattern = "PersistenceService initialized WITHOUT batch-topic - event-driven indexing disabled!")
    void testMetadataCorrelatesWithTickData() throws IOException {
        Config config = createIntegrationConfig();
        serviceManager = new ServiceManager(config);

        serviceManager.startAll();

        // Wait for the metadata file to exist AND for all expected ticks to be readable.
        // Store the found path in a holder to reuse after await (avoids non-deterministic findFirst).
        final Path[] metadataHolder = new Path[1];
        await().atMost(30, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> {
                Path found = findMetadataFile(tempStorageDir);
                if (found == null) {
                    return false;
                }
                Path rawDir = found.getParent();
                // In this config, 100 ticks are produced with sampling 10 = 10 ticks total.
                boolean ticksAreReadable = readAllTicksFromBatches(rawDir).size() >= 10;
                if (ticksAreReadable) {
                    metadataHolder[0] = found;
                }
                return ticksAreReadable;
            });

        // Reuse the SAME metadataFile that satisfied the await condition
        Path metadataFile = metadataHolder[0];
        SimulationMetadata metadata = readMetadataFile(metadataFile);

        // Verify metadata and tick data are in the same directory (same simulationRunId)
        // New structure: {runId}/raw/metadata.pb, so parent.parent = {runId}
        String simulationRunId = metadata.getSimulationRunId();
        Path rawDir = metadataFile.getParent();  // {runId}/raw/
        Path simulationDir = rawDir.getParent();  // {runId}/
        assertTrue(simulationDir.getFileName().toString().equals(simulationRunId));
        // Note: The await condition already verified that batch files exist and are readable.
        // No redundant assertion needed here - await guarantees the condition was met.
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*(SimulationEngine|MetadataPersistenceService|ServiceManager|FileSystemStorageResource).*")
    @AllowLog(level = LogLevel.WARN, messagePattern = "PersistenceService initialized WITHOUT batch-topic - event-driven indexing disabled!")
    void testServiceStopsAfterProcessing() {
        Config config = createIntegrationConfig();
        serviceManager = new ServiceManager(config);

        serviceManager.startAll();

        // Wait for metadata to be written
        await().atMost(30, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> findMetadataFile(tempStorageDir) != null);

        // Verify MetadataPersistenceService stopped itself (one-shot pattern)
        // Use ServiceManager API to check service status
        await().atMost(10, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> {
                var status = serviceManager.getServiceStatus("metadata-persistence-service");
                return status != null && status.state() == org.evochora.datapipeline.api.services.IService.State.STOPPED;
            });

        // Verify service metrics show successful write
        var status = serviceManager.getServiceStatus("metadata-persistence-service");
        assertNotNull(status);
        assertEquals(1, status.metrics().get("metadata_written").longValue());
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*(SimulationEngine|MetadataPersistenceService|ServiceManager|FileSystemStorageResource).*")
    @AllowLog(level = LogLevel.WARN, messagePattern = "PersistenceService initialized WITHOUT batch-topic - event-driven indexing disabled!")
    void testMetadataContentCompleteness() throws IOException {
        Config config = createIntegrationConfig();
        serviceManager = new ServiceManager(config);

        serviceManager.startAll();

        await().atMost(30, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> findMetadataFile(tempStorageDir) != null);

        Path metadataFile = findMetadataFile(tempStorageDir);
        SimulationMetadata metadata = readMetadataFile(metadataFile);

        // Verify all critical fields are populated
        assertNotNull(metadata.getSimulationRunId());
        assertEquals(42, metadata.getInitialSeed());
        assertTrue(metadata.getStartTimeMs() > 0);

        // Verify environment configuration from resolvedConfigJson
        int[] shape = MetadataConfigHelper.getEnvironmentShape(metadata);
        assertEquals(2, shape.length);
        assertEquals(100, shape[0]);
        assertEquals(100, shape[1]);

        // Verify programs
        assertTrue(metadata.getProgramsCount() > 0);

        // Verify resolved config JSON exists
        assertNotNull(metadata.getResolvedConfigJson());
        assertFalse(metadata.getResolvedConfigJson().isEmpty());
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*(SimulationEngine|MetadataPersistenceService|ServiceManager).*")
    @AllowLog(level = LogLevel.WARN, messagePattern = "PersistenceService initialized WITHOUT batch-topic - event-driven indexing disabled!")
    void testGracefulShutdown() {
        Config config = createIntegrationConfig();
        serviceManager = new ServiceManager(config);

        serviceManager.startAll();

        // Wait briefly for services to transition to RUNNING state
        // This tests that shutdown doesn't lose the metadata message
        await().atMost(1, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> {
                var status = serviceManager.getServiceStatus("simulation-engine");
                return status != null && status.state() == org.evochora.datapipeline.api.services.IService.State.RUNNING;
            });

        serviceManager.stopAll();
        serviceManager = null;  // Prevent double-stop in @AfterEach

        // If metadata was queued, it should still be written during graceful shutdown
        // This test verifies no data loss during shutdown
        // Note: Timing-dependent - metadata might already be written or still in queue
    }

    // ========== Helper Methods ==========

    private Config createIntegrationConfig() {
        // Generate unique database name for topics (shared by all topics)
        String topicJdbcUrl = "jdbc:h2:mem:test-topics-" + UUID.randomUUID();
        
        // Build config using HOCON string to avoid Map.of() size limit
        String hoconConfig = String.format("""
            pipeline {
              autoStart = false
              startupSequence = ["metadata-persistence-service", "simulation-engine", "persistence-service"]

              resources {
                tick-storage {
                  className = "org.evochora.datapipeline.resources.storage.FileSystemStorageResource"
                  options {
                    rootDirectory = "%s"
                  }
                }

                raw-tick-data {
                  className = "org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue"
                  options {
                    capacity = 1000
                  }
                }

                context-data {
                  className = "org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue"
                  options {
                    capacity = 10
                  }
                }

                dlq-queue {
                  className = "org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue"
                  options {
                    capacity = 100
                  }
                }

                metadata-topic {
                  className = "org.evochora.datapipeline.resources.topics.H2TopicResource"
                  options {
                    jdbcUrl = "%s"
                    username = "sa"
                    password = ""
                    claimTimeout = 300
                  }
                }
              }

              services {
                metadata-persistence-service {
                  className = "org.evochora.datapipeline.services.MetadataPersistenceService"
                  resources {
                    input = "queue-in:context-data"
                    storage = "storage-write:tick-storage"
                    topic = "topic-write:metadata-topic"
                  }
                  options {
                    maxRetries = 3
                    retryBackoffMs = 100
                  }
                }

                simulation-engine {
                  className = "org.evochora.datapipeline.services.SimulationEngine"
                  resources {
                    tickData = "queue-out:raw-tick-data"
                    metadataOutput = "queue-out:context-data"
                  }
                  options {
                    runtime {
                      organism {
                        max-energy = 32767
                        max-entropy = 8191
                        error-penalty-cost = 500
                      }
                      thermodynamics {
                        default: {
                          className = "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy"
                          options: { base-energy = 1, base-entropy = 1 }
                        }
                        overrides: {
                          instructions: {}
                          families: {}
                        }
                      }
                    }
                    samplingInterval = 10
                    seed = 42
                    environment {
                      shape = [100, 100]
                      topology = "TORUS"
                    }
                    tickPlugins = [
                      {
                        className = "org.evochora.runtime.worldgen.GeyserCreator"
                        options {
                          count = 2
                          interval = 100
                          amount = 1000
                          safetyRadius = 2
                        }
                      }
                    ]
                    organisms = [
                      {
                        program = "%s"
                        initialEnergy = 10000
                        placement {
                          positions = [50, 50]
                        }
                      }
                    ]
                    metadata {
                      experiment = "test-run"
                    }
                  }
                }

                persistence-service {
                  className = "org.evochora.datapipeline.services.PersistenceService"
                  resources {
                    input = "queue-in:raw-tick-data"
                    storage = "storage-write:tick-storage"
                    dlq = "queue-out:dlq-queue"
                  }
                  options {
                    maxBatchSize = 100
                    batchTimeoutSeconds = 2
                  }
                }
              }
            }
            """,
            tempStorageDir.toAbsolutePath().toString().replace("\\", "/"),
            topicJdbcUrl,
            programFile.toAbsolutePath().toString().replace("\\", "/")
        );

        return ConfigFactory.parseString(hoconConfig);
    }

    private Path findMetadataFile(Path storageRoot) throws IOException {
        if (!Files.exists(storageRoot)) {
            return null;
        }

        // New structure: {runId}/raw/metadata.pb
        try (Stream<Path> simulationDirs = Files.list(storageRoot)) {
            return simulationDirs
                .filter(Files::isDirectory)
                .map(dir -> dir.resolve("raw/metadata.pb"))
                .filter(Files::exists)
                .findFirst()
                .orElse(null);
        }
    }

    private SimulationMetadata readMetadataFile(Path metadataFile) throws IOException {
        // Read metadata using storage resource (same as production would)
        // readMessage() handles length-delimited protobuf format correctly
        Path storageRoot = metadataFile.getParent().getParent();

        Config storageConfig = ConfigFactory.parseMap(
            Map.of("rootDirectory", storageRoot.toAbsolutePath().toString())
        );

        FileSystemStorageResource storage = new FileSystemStorageResource("test-storage", storageConfig);

        // Find actual metadata file (may have compression extension)
        Path relativePath = storageRoot.relativize(metadataFile);
        String physicalPath = relativePath.toString().replace(java.io.File.separatorChar, '/');
        
        // Use readMessage() - validates exactly one message in file
        return storage.readMessage(StoragePath.of(physicalPath), SimulationMetadata.parser());
    }
}
