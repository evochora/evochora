package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.contracts.MetadataInfo;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.resources.database.H2Database;
import org.evochora.datapipeline.resources.storage.FileSystemStorageResource;
import org.evochora.datapipeline.resources.topics.H2TopicResource;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.ExpectLogs;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.evochora.junit.extensions.logging.LogLevel.ERROR;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for DummyIndexer Phase 2.5.1 (Metadata Reading).
 * <p>
 * Tests the complete metadata reading flow with real database and storage.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class DummyIndexerIntegrationTest {
    
    private H2Database testDatabase;
    private FileSystemStorageResource testStorage;
    private H2TopicResource testMetadataTopic;
    private H2TopicResource testBatchTopic;
    private Path tempStorageDir;
    private String dbUrl;
    private String dbUsername;
    private String dbPassword;
    
    @BeforeEach
    void setup() throws IOException {
        // Setup temporary storage
        tempStorageDir = Files.createTempDirectory("evochora-test-dummy-v1-");
        Config storageConfig = ConfigFactory.parseString(
            "rootDirectory = \"" + tempStorageDir.toAbsolutePath().toString().replace("\\", "/") + "\""
        );
        testStorage = new FileSystemStorageResource("test-storage", storageConfig);
        
        // Setup in-memory H2 database with unique name for parallel testing
        dbUrl = "jdbc:h2:mem:test-dummy-v1-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        dbUsername = "test-user";
        dbPassword = "test-password";
        Config dbConfig = ConfigFactory.parseString(
            "jdbcUrl = \"" + dbUrl + "\"\n" +
            "username = \"" + dbUsername + "\"\n" +
            "password = \"" + dbPassword + "\""
        );
        testDatabase = new H2Database("test-db", dbConfig);
        
        // Setup H2 topics for metadata and batch notifications
        String metadataTopicJdbcUrl = "jdbc:h2:mem:test-metadata-topic-" + UUID.randomUUID();
        Config metadataTopicConfig = ConfigFactory.parseString(
            "jdbcUrl = \"" + metadataTopicJdbcUrl + "\"\n" +
            "username = \"sa\"\n" +
            "password = \"\"\n" +
            "claimTimeout = 300"
        );
        testMetadataTopic = new H2TopicResource("metadata-topic", metadataTopicConfig);
        
        String batchTopicJdbcUrl = "jdbc:h2:mem:test-batch-topic-" + UUID.randomUUID();
        Config batchTopicConfig = ConfigFactory.parseString(
            "jdbcUrl = \"" + batchTopicJdbcUrl + "\"\n" +
            "username = \"sa\"\n" +
            "password = \"\"\n" +
            "claimTimeout = 300"
        );
        testBatchTopic = new H2TopicResource("batch-topic", batchTopicConfig);
    }
    
    @AfterEach
    void cleanup() throws Exception {
        if (testMetadataTopic != null) {
            testMetadataTopic.close();
        }
        if (testBatchTopic != null) {
            testBatchTopic.close();
        }
        if (testDatabase != null) {
            // Wait for connections to be released (avoid Thread.sleep)
            await().atMost(2, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> {
                    Map<String, Number> metrics = testDatabase.getMetrics();
                    Number active = metrics.get("h2_pool_active_connections");
                    return active == null || active.intValue() == 0;
                });
            
            // Verify no connection leaks
            Map<String, Number> metrics = testDatabase.getMetrics();
            Number activeConnections = metrics.get("h2_pool_active_connections");
            assertEquals(0, activeConnections != null ? activeConnections.intValue() : 0,
                "Connection leak detected! Active connections should be 0 after test completion");
            
            testDatabase.stop();
        }
        
        // Cleanup temp directory
        if (tempStorageDir != null && Files.exists(tempStorageDir)) {
            Files.walk(tempStorageDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
        }
    }
    
    @Test
    void testMetadataReading_Success() throws Exception {
        // Given: Create test run with metadata
        String runId = "20251011-120000-" + UUID.randomUUID();
        SimulationMetadata metadata = createTestMetadata(runId, 10);
        
        // First index metadata using MetadataIndexer
        indexMetadata(runId, metadata);
        
        // Configure DummyIndexer in post-mortem mode
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            pollIntervalMs = 100
            maxPollDurationMs = 5000
            """.formatted(runId));
        
        DummyIndexer indexer = createDummyIndexer("test-indexer", config);
        
        // When: Start indexer
        indexer.start();
        
        // Then: Should complete successfully after reading metadata
        await().atMost(15, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .until(() -> {
                IService.State state = indexer.getCurrentState();
                return state == IService.State.STOPPED || state == IService.State.ERROR;
            });
        
        // Verify final state
        IService.State finalState = indexer.getCurrentState();
        assertEquals(IService.State.STOPPED, finalState,
            "Indexer should be STOPPED after reading metadata, but was: " + finalState);
        
        // Verify metrics
        Map<String, Number> metrics = indexer.getMetrics();
        assertEquals(1, metrics.get("runs_processed").intValue(),
            "DummyIndexer should have processed exactly one run");
    }
    
    @Test
    void testMetadataReading_PollingBehavior() throws Exception {
        // Given: Create run ID but don't index metadata yet
        String runId = "test-run-" + UUID.randomUUID();
        
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            pollIntervalMs = 100
            maxPollDurationMs = 5000
            """.formatted(runId));
        
        DummyIndexer indexer = createDummyIndexer("test-indexer", config);
        
        // When: Start indexer (metadata doesn't exist yet)
        indexer.start();
        
        // Wait until indexer is running (polling for metadata)
        await().atMost(2, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);
        
        // Now create and index metadata
        SimulationMetadata metadata = createTestMetadata(runId, 10);
        indexMetadata(runId, metadata);
        
        // Then: Should complete now that metadata is available
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.STOPPED);
        
        assertEquals(1, indexer.getMetrics().get("runs_processed").intValue());
    }
    
    @Test
    @ExpectLogs({
        @ExpectLog(level = ERROR,
                   loggerPattern = "org.evochora.datapipeline.services.indexers.DummyIndexer",
                   messagePattern = "Indexing timeout for run: test-run-.*"),
        @ExpectLog(level = ERROR,
                   loggerPattern = "org.evochora.datapipeline.services.indexers.DummyIndexer",
                   messagePattern = "DummyIndexer stopped with ERROR due to RuntimeException")
    })
    void testMetadataReading_Timeout() throws Exception {
        // Given: Create run ID but never index metadata
        String runId = "test-run-" + UUID.randomUUID();
        
        // Configure short timeout
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            pollIntervalMs = 100
            maxPollDurationMs = 1000
            """.formatted(runId));
        
        DummyIndexer indexer = createDummyIndexer("test-indexer", config);
        
        // When: Start indexer (metadata never created)
        indexer.start();
        
        // Then: Should timeout and enter ERROR state
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.ERROR);
        
        assertEquals(IService.State.ERROR, indexer.getCurrentState(),
            "Service should enter ERROR state after timeout");
        
        // Verify no runs processed
        assertEquals(0, indexer.getMetrics().get("runs_processed").intValue());
    }
    
    @Test
    void testMetadataReading_ParallelMode() throws Exception {
        // Given: Setup DummyIndexer in parallel mode (no runId specified)
        Config config = ConfigFactory.parseString("""
            pollIntervalMs = 100
            maxPollDurationMs = 5000
            """);
        
        DummyIndexer indexer = createDummyIndexer("test-indexer", config);
        indexer.start();
        
        // Wait for indexer to be running
        await().atMost(2, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);
        
        // Generate runId with timestamp in the future (avoid race condition)
        Instant runInstant = Instant.now().plusSeconds(1);
        String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSS")
            .withZone(java.time.ZoneId.systemDefault())
            .format(runInstant);
        String runId = timestamp + "-" + UUID.randomUUID();
        
        // Create metadata
        SimulationMetadata metadata = createTestMetadata(runId, 10);
        
        // Write metadata file to storage (simulates new run discovery)
        Files.createDirectories(tempStorageDir.resolve(runId));
        testStorage.writeMessage(runId + "/metadata.pb", metadata);
        
        // Index metadata
        indexMetadata(runId, metadata);
        
        // Then: Should discover, read metadata, and complete
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.STOPPED);
        
        assertEquals(1, indexer.getMetrics().get("runs_processed").intValue());
    }
    
    // ========== Helper Methods ==========
    
    private SimulationMetadata createTestMetadata(String runId, int samplingInterval) {
        return SimulationMetadata.newBuilder()
            .setSimulationRunId(runId)
            .setSamplingInterval(samplingInterval)
            .setInitialSeed(12345L)
            .setStartTimeMs(System.currentTimeMillis())
            .build();
    }
    
    private void indexMetadata(String runId, SimulationMetadata metadata) throws Exception {
        // Write metadata file to storage
        String storageKey = runId + "/metadata.pb";
        testStorage.writeMessage(storageKey, metadata);
        
        // Send notification to metadata topic (simulates MetadataPersistenceService)
        ResourceContext topicWriterContext = new ResourceContext("test-persistence", "topic", "topic-write", "metadata-topic", Collections.emptyMap());
        @SuppressWarnings("unchecked")
        ITopicWriter<MetadataInfo> topicWriter = (ITopicWriter<MetadataInfo>) testMetadataTopic.getWrappedResource(topicWriterContext);
        topicWriter.setSimulationRun(runId);
        
        MetadataInfo notification = MetadataInfo.newBuilder()
            .setSimulationRunId(runId)
            .setStorageKey(storageKey)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        topicWriter.send(notification);
        
        // Use MetadataIndexer to index it into database
        ResourceContext dbContext = new ResourceContext(
            "metadata-indexer",
            "database",
            "db-meta-write",
            "test-db",
            Collections.emptyMap()
        );
        IResource wrappedDatabase = testDatabase.getWrappedResource(dbContext);
        
        ResourceContext topicReaderContext = new ResourceContext("metadata-indexer", "topic", "topic-read", "metadata-topic", Map.of("consumerGroup", "metadata"));
        IResource wrappedTopic = testMetadataTopic.getWrappedResource(topicReaderContext);
        
        Config indexerConfig = ConfigFactory.parseString("runId = \"" + runId + "\"");
        Map<String, List<IResource>> resources = Map.of(
            "storage", List.of(testStorage),
            "database", List.of(wrappedDatabase),
            "topic", List.of(wrappedTopic)
        );
        
        MetadataIndexer metadataIndexer = new MetadataIndexer("metadata-indexer", indexerConfig, resources);
        metadataIndexer.start();
        
        // Wait for metadata indexing to complete
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> metadataIndexer.getCurrentState() == IService.State.STOPPED);
        
        assertEquals(IService.State.STOPPED, metadataIndexer.getCurrentState(),
            "MetadataIndexer should complete successfully");
        
        // Verify metadata was actually indexed (not just stopped due to error)
        Map<String, Number> metrics = metadataIndexer.getMetrics();
        assertEquals(1, metrics.get("metadata_indexed").intValue(),
            "MetadataIndexer should have indexed exactly 1 metadata");
        assertEquals(0, metrics.get("metadata_failed").intValue(),
            "MetadataIndexer should have 0 failures");
    }
    
    private DummyIndexer createDummyIndexer(String name, Config config) {
        // Wrap database resource with db-meta-read usage type
        ResourceContext dbContext = new ResourceContext(
            name,
            "metadata",  // Port name must match getRequiredResource() call in DummyIndexer
            "db-meta-read",
            "test-db",
            Collections.emptyMap()
        );
        IResource wrappedDatabase = testDatabase.getWrappedResource(dbContext);
        
        // Wrap batch topic for reading BatchInfo notifications
        ResourceContext topicReaderContext = new ResourceContext(name, "topic", "topic-read", "batch-topic", Map.of("consumerGroup", "dummy"));
        IResource wrappedTopic = testBatchTopic.getWrappedResource(topicReaderContext);
        
        Map<String, List<IResource>> resources = Map.of(
            "storage", List.of(testStorage),
            "metadata", List.of(wrappedDatabase),  // Port name must match getRequiredResource() call
            "topic", List.of(wrappedTopic)
        );
        
        return new DummyIndexer(name, config, resources);
    }
}

