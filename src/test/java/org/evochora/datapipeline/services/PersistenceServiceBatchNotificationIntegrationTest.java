package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.ServiceManager;
import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
import org.evochora.datapipeline.resources.topics.H2TopicResource;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for PersistenceService batch notification feature.
 * <p>
 * Tests end-to-end flow: TickData → PersistenceService → Storage + Topic.
 * Uses real H2TopicResource (in-memory) for batch notifications.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*(SimulationEngine|PersistenceService|ServiceManager|FileSystemStorageResource|H2TopicResource).*")
class PersistenceServiceBatchNotificationIntegrationTest {
    
    @TempDir
    Path tempDir;
    
    private Path tempStorageDir;
    private Path programFile;
    private ServiceManager serviceManager;
    
    @BeforeAll
    static void setUpClass() {
        Instruction.init();
    }
    
    @BeforeEach
    void setUp() throws IOException {
        tempStorageDir = tempDir.resolve("storage");
        Files.createDirectories(tempStorageDir);
        
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
    void shouldSendBatchNotificationToTopic() throws Exception {
        // Given
        Config config = createConfigWithTopic();
        serviceManager = new ServiceManager(config);
        
        // Start all services
        serviceManager.startAll();
        
        // Wait for at least one batch to be written
        await().atMost(30, TimeUnit.SECONDS)
            .until(() -> {
                var status = serviceManager.getServiceStatus("persistence-1");
                return status != null && status.metrics().get("batches_written").longValue() >= 1;
            });
        
        // Get batch-topic resource and create reader
        H2TopicResource<?> topicResource = (H2TopicResource<?>) serviceManager.getAllResourceStatus().get("batch-topic");
        assertNotNull(topicResource, "batch-topic resource should exist");
        
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, ?> reader = (ITopicReader<BatchInfo, ?>) topicResource.getWrappedResource(
            new ResourceContext("test-reader", "reader-port", "topic-read", "batch-topic", 
                Map.of("consumerGroup", "test-consumer")));
        
        // Then - Read notification from topic
        TopicMessage<BatchInfo, ?> message = reader.poll(5, TimeUnit.SECONDS);
        
        assertThat(message).isNotNull();
        assertThat(message.payload()).isNotNull();
        
        BatchInfo notification = message.payload();
        assertThat(notification.getSimulationRunId()).isNotEmpty();
        assertThat(notification.getStoragePath()).contains("batch_");
        assertThat(notification.getTickStart()).isGreaterThanOrEqualTo(0);
        assertThat(notification.getTickEnd()).isGreaterThanOrEqualTo(notification.getTickStart());
        assertThat(notification.getWrittenAtMs()).isPositive();
        
        // Verify metrics
        var persistenceStatus = serviceManager.getServiceStatus("persistence-1");
        assertThat(persistenceStatus.metrics().get("batches_written")).isNotNull();
        assertThat(persistenceStatus.metrics().get("notifications_sent")).isNotNull();
        assertThat(persistenceStatus.metrics().get("notifications_sent").longValue()).isGreaterThanOrEqualTo(1);
        
        // Cleanup
        reader.close();
    }
    
    @Test
    void shouldHandleMultiplePersistenceInstancesWithSharedTopic() throws Exception {
        // Given - Config with 2 competing persistence instances
        Config config = createMultiInstanceConfigWithTopic();
        serviceManager = new ServiceManager(config);
        
        // Start all services
        serviceManager.startAll();
        
        // Wait for batches to be written by both instances
        await().atMost(30, TimeUnit.SECONDS)
            .until(() -> {
                var status1 = serviceManager.getServiceStatus("persistence-1");
                var status2 = serviceManager.getServiceStatus("persistence-2");
                return status1 != null && status2 != null &&
                       (status1.metrics().get("batches_written").longValue() + 
                        status2.metrics().get("batches_written").longValue()) >= 2;
            });
        
        // Get batch-topic resource and create reader
        H2TopicResource<?> topicResource = (H2TopicResource<?>) serviceManager.getAllResourceStatus().get("batch-topic");
        assertNotNull(topicResource, "batch-topic resource should exist");
        
        @SuppressWarnings("unchecked")
        ITopicReader<BatchInfo, ?> reader = (ITopicReader<BatchInfo, ?>) topicResource.getWrappedResource(
            new ResourceContext("test-reader", "reader-port", "topic-read", "batch-topic", 
                Map.of("consumerGroup", "test-consumer")));
        
        // Then - Read at least 2 notifications from topic (one from each persistence instance)
        TopicMessage<BatchInfo, ?> msg1 = reader.poll(5, TimeUnit.SECONDS);
        assertThat(msg1).isNotNull();
        assertThat(msg1.payload()).isNotNull();
        
        TopicMessage<BatchInfo, ?> msg2 = reader.poll(5, TimeUnit.SECONDS);
        assertThat(msg2).isNotNull();
        assertThat(msg2.payload()).isNotNull();
        
        // Verify both messages are valid
        BatchInfo notification1 = msg1.payload();
        BatchInfo notification2 = msg2.payload();
        
        assertThat(notification1.getSimulationRunId()).isEqualTo(notification2.getSimulationRunId());
        assertThat(notification1.getStoragePath()).isNotEmpty();
        assertThat(notification2.getStoragePath()).isNotEmpty();
        
        // Verify metrics from both instances
        var status1 = serviceManager.getServiceStatus("persistence-1");
        var status2 = serviceManager.getServiceStatus("persistence-2");
        
        long totalNotifications = status1.metrics().get("notifications_sent").longValue() + 
                                 status2.metrics().get("notifications_sent").longValue();
        assertThat(totalNotifications).isGreaterThanOrEqualTo(2);
        
        // Cleanup
        reader.close();
    }
    
    private Config createConfigWithTopic() {
        String topicJdbcUrl = "jdbc:h2:mem:test-batch-notification-" + UUID.randomUUID();
        
        String hoconConfig = String.format("""
            pipeline {
              autoStart = false
              startupSequence = ["simulation-engine", "persistence-1"]
              
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
                
                batch-topic {
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
                    maxTicks = 50
                    environment {
                      shape = [10, 10]
                      topology = "TORUS"
                    }
                    organisms = [
                      {
                        program = "%s"
                        initialEnergy = 10000
                        placement {
                          positions = [5, 5]
                        }
                      }
                    ]
                    plugins = []
                  }
                }
                
                persistence-1 {
                  className = "org.evochora.datapipeline.services.PersistenceService"
                  resources {
                    input = "queue-in:raw-tick-data"
                    storage = "storage-write:tick-storage"
                    topic = "topic-write:batch-topic"
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
    
    private Config createMultiInstanceConfigWithTopic() {
        Config baseConfig = createConfigWithTopic();
        
        // Add persistence-2 service
        return baseConfig.withValue("pipeline.services.persistence-2",
            ConfigFactory.parseMap(Map.of(
                "className", "org.evochora.datapipeline.services.PersistenceService",
                "resources", Map.of(
                    "input", "queue-in:raw-tick-data",
                    "storage", "storage-write:tick-storage",
                    "topic", "topic-write:batch-topic"
                ),
                "options", Map.of(
                    "maxBatchSize", 50,
                    "batchTimeoutSeconds", 2
                )
            )).root()
        ).withValue("pipeline.startupSequence",
            ConfigFactory.parseString("pipeline.startupSequence=[\"simulation-engine\", \"persistence-1\", \"persistence-2\"]").getValue("pipeline.startupSequence")
        );
    }
}

