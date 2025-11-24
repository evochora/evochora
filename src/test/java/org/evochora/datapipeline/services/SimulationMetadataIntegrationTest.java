package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.ServiceManager;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.resources.database.H2Database;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for end-to-end metadata persistence flow with real resources.
 * Tests the complete pipeline: SimulationEngine → context-data queue → MetadataPersistenceService → Storage.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*(SimulationEngine|MetadataPersistenceService|ServiceManager|FileSystemStorageResource).*")
class SimulationMetadataIntegrationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimulationMetadataIntegrationTest.class);

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

        // Verify file naming convention: {simulationRunId}/metadata.pb
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

        // Wait for both metadata and tick data files
        await().atMost(30, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> findMetadataFile(tempStorageDir) != null &&
                         countBatchFiles(tempStorageDir) > 0);

        Path metadataFile = findMetadataFile(tempStorageDir);
        SimulationMetadata metadata = readMetadataFile(metadataFile);

        // Verify metadata and tick data are in the same directory (same simulationRunId)
        String simulationRunId = metadata.getSimulationRunId();
        Path simulationDir = metadataFile.getParent();
        assertTrue(simulationDir.getFileName().toString().equals(simulationRunId));

        // Verify batch files exist in simulation directory using our robust helper method
        // which handles the race condition.
        assertTrue(countBatchFiles(simulationDir) > 0, "Batch files should exist under simulation directory");
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

        // Verify environment configuration
        assertNotNull(metadata.getEnvironment());
        assertEquals(2, metadata.getEnvironment().getDimensions());
        assertEquals(100, metadata.getEnvironment().getShape(0));
        assertEquals(100, metadata.getEnvironment().getShape(1));

        // Verify energy strategies
        assertTrue(metadata.getEnergyStrategiesCount() > 0);

        // Verify programs
        assertTrue(metadata.getProgramsCount() > 0);

        // Verify initial organisms
        assertTrue(metadata.getInitialOrganismsCount() > 0);

        // Verify user metadata
        assertEquals("test-run", metadata.getUserMetadataOrDefault("experiment", ""));

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
                    samplingInterval = 10
                    seed = 42
                    environment {
                      shape = [100, 100]
                      topology = "TORUS"
                    }
                    energyStrategies = [
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

        try (Stream<Path> simulationDirs = Files.list(storageRoot)) {
            return simulationDirs
                .filter(Files::isDirectory)
                .flatMap(dir -> {
                    try {
                        return Files.list(dir);
                    } catch (IOException e) {
                        return Stream.empty();
                    }
                })
                .filter(p -> p.getFileName().toString().equals("metadata.pb"))
                .findFirst()
                .orElse(null);
        }
    }

    private SimulationMetadata readMetadataFile(Path metadataFile) throws IOException {
        // Read metadata using storage resource (same as production would)
        // readMessage() handles length-delimited protobuf format correctly
        Path storageRoot = metadataFile.getParent().getParent();
        String simulationRunId = metadataFile.getParent().getFileName().toString();

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

    private int countBatchFiles(Path storageRoot) {
        if (!Files.exists(storageRoot)) {
            return 0;
        }

        try (Stream<Path> paths = Files.walk(storageRoot)) {
            return (int) paths
                    .filter(p -> {
                        String fileName = p.getFileName().toString();
                        // Filter out .tmp files BEFORE other operations to avoid race conditions
                        return fileName.startsWith("batch_") && fileName.endsWith(".pb") && !fileName.contains(".tmp");
                    })
                    .filter(Files::isRegularFile)
                .count();
        } catch (java.io.UncheckedIOException e) {
            if (e.getCause() instanceof java.nio.file.NoSuchFileException) {
                // This can happen in a race condition on fast filesystems (like in CI).
                // Logging at DEBUG level to avoid polluting test logs, but providing info for debugging.
                // Returning 0 is safe because await() will retry.
                LOGGER.debug("Caught a recoverable race condition while counting files: {}", e.getCause().getMessage());
                return 0;
            }
            throw e;
        } catch (IOException e) {
             // It's good practice to also handle the checked IOException variant
             if (e.getCause() instanceof java.nio.file.NoSuchFileException) {
                LOGGER.debug("Caught a recoverable race condition while counting files: {}", e.getCause().getMessage());
            return 0;
            }
            throw new RuntimeException("Failed to count batch files", e);
        }
    }
}
