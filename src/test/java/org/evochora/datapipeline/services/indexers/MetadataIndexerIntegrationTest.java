/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.MetadataInfo;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.resources.database.H2Database;
import org.evochora.datapipeline.resources.storage.FileSystemStorageResource;
import org.evochora.datapipeline.resources.topics.H2TopicResource;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class MetadataIndexerIntegrationTest {

    private H2Database testDatabase;
    private FileSystemStorageResource testStorage;
    private H2TopicResource testTopic;
    private Path tempStorageDir;
    private String dbUrl;
    private String dbUsername;
    private String dbPassword;

    @BeforeEach
    void setUp() throws IOException {
        tempStorageDir = Files.createTempDirectory("evochora-test-storage-");
        Config storageConfig = ConfigFactory.parseString("rootDirectory = \"" + tempStorageDir.toAbsolutePath().toString().replace("\\", "/") + "\"");
        testStorage = new FileSystemStorageResource("test-storage", storageConfig);

        dbUrl = "jdbc:h2:mem:test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        dbUsername = "test-user";
        dbPassword = "test-password";
        Config dbConfig = ConfigFactory.parseString(
            "jdbcUrl = \"" + dbUrl + "\"\n" +
            "username = \"" + dbUsername + "\"\n" +
            "password = \"" + dbPassword + "\""
        );
        testDatabase = new H2Database("test-db", dbConfig);
        
        // Create H2 topic for metadata notifications
        String topicJdbcUrl = "jdbc:h2:mem:test-metadata-topic-" + UUID.randomUUID();
        Config topicConfig = ConfigFactory.parseString(
            "jdbcUrl = \"" + topicJdbcUrl + "\"\n" +
            "username = \"sa\"\n" +
            "password = \"\"\n" +
            "claimTimeout = 300"
        );
        testTopic = new H2TopicResource("metadata-topic", topicConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (testTopic != null) {
            testTopic.close();
        }
        if (testDatabase != null) {
            // Verify no connection leaks before shutting down
            Map<String, Number> metrics = testDatabase.getMetrics();
            Number activeConnections = metrics.get("h2_pool_active_connections");
            assertEquals(0, activeConnections != null ? activeConnections.intValue() : 0,
                    "Connection leak detected! Active connections should be 0 after test completion");
            
            testDatabase.close();
        }
        if (tempStorageDir != null) {
            Files.walk(tempStorageDir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
        }
    }

    @Test
    void testMetadataIndexing_PostMortemMode() throws Exception {
        String runId = "20250101120000-" + UUID.randomUUID();
        SimulationMetadata metadata = createTestMetadata(runId);
        String storageKey = runId + "/metadata.pb";
        
        // Write metadata to storage (simulates MetadataPersistenceService)
        testStorage.writeMessage(storageKey, metadata);
        
        // Send notification to topic (simulates MetadataPersistenceService)
        ResourceContext topicWriterContext = new ResourceContext("test-persistence", "topic", "topic-write", "metadata-topic", Collections.emptyMap());
        @SuppressWarnings("unchecked")
        ITopicWriter<MetadataInfo> topicWriter = (ITopicWriter<MetadataInfo>) testTopic.getWrappedResource(topicWriterContext);
        topicWriter.setSimulationRun(runId);
        
        MetadataInfo notification = MetadataInfo.newBuilder()
            .setSimulationRunId(runId)
            .setStorageKey(storageKey)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        topicWriter.send(notification);

        // Manually wrap resources for indexer
        ResourceContext dbContext = new ResourceContext("test-indexer", "database", "db-meta-write", "test-db", Collections.emptyMap());
        IResource wrappedDatabase = testDatabase.getWrappedResource(dbContext);
        
        ResourceContext topicReaderContext = new ResourceContext("test-indexer", "topic", "topic-read", "metadata-topic", Map.of("consumerGroup", "metadata"));
        IResource wrappedTopic = testTopic.getWrappedResource(topicReaderContext);

        Config indexerConfig = ConfigFactory.parseString("runId = \"" + runId + "\"");
        Map<String, List<IResource>> resources = Map.of(
            "storage", List.of(testStorage), 
            "database", List.of(wrappedDatabase),
            "topic", List.of(wrappedTopic)
        );
        MetadataIndexer indexer = new MetadataIndexer("test-indexer", indexerConfig, resources);

        indexer.start();

        await().atMost(10, TimeUnit.SECONDS).until(() -> indexer.getCurrentState() == IService.State.STOPPED);
        assertEquals(IService.State.STOPPED, indexer.getCurrentState(), "Indexer should be in STOPPED state.");

        assertSchemaExists(toSchemaName(runId));
        assertMetadataInDatabase(toSchemaName(runId), metadata);
    }

    @Test
    void testMetadataIndexing_ParallelMode() throws Exception {
        // Manually wrap resources for indexer
        ResourceContext dbContext = new ResourceContext("test-indexer", "database", "db-meta-write", "test-db", Collections.emptyMap());
        IResource wrappedDatabase = testDatabase.getWrappedResource(dbContext);
        
        ResourceContext topicReaderContext = new ResourceContext("test-indexer", "topic", "topic-read", "metadata-topic", Map.of("consumerGroup", "metadata"));
        IResource wrappedTopic = testTopic.getWrappedResource(topicReaderContext);

        Config indexerConfig = ConfigFactory.empty();
        Map<String, List<IResource>> resources = Map.of(
            "storage", List.of(testStorage), 
            "database", List.of(wrappedDatabase),
            "topic", List.of(wrappedTopic)
        );
        MetadataIndexer indexer = new MetadataIndexer("test-indexer", indexerConfig, resources);
        indexer.start();

        // Wait for indexer to be running before creating the run
        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> indexer.getCurrentState() == IService.State.RUNNING);

        // Generate a runId with a timestamp slightly in the future to guarantee it's discovered
        Instant runInstant = java.time.Instant.now().plusSeconds(1);
        String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSS")
                .withZone(java.time.ZoneId.systemDefault())
                .format(runInstant);
        String runId = timestamp + "-" + UUID.randomUUID();
        SimulationMetadata metadata = createTestMetadata(runId);
        String storageKey = runId + "/metadata.pb";

        // Write metadata to storage (simulates MetadataPersistenceService)
        Files.createDirectories(tempStorageDir.resolve(runId));
        testStorage.writeMessage(storageKey, metadata);
        
        // Send notification to topic (simulates MetadataPersistenceService)
        ResourceContext topicWriterContext = new ResourceContext("test-persistence", "topic", "topic-write", "metadata-topic", Collections.emptyMap());
        @SuppressWarnings("unchecked")
        ITopicWriter<MetadataInfo> topicWriter = (ITopicWriter<MetadataInfo>) testTopic.getWrappedResource(topicWriterContext);
        topicWriter.setSimulationRun(runId);
        
        MetadataInfo notification = MetadataInfo.newBuilder()
            .setSimulationRunId(runId)
            .setStorageKey(storageKey)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        topicWriter.send(notification);

        await().atMost(10, TimeUnit.SECONDS).until(() -> indexer.getCurrentState() == IService.State.STOPPED);
        assertEquals(IService.State.STOPPED, indexer.getCurrentState(), "Indexer should be in STOPPED state.");

        assertSchemaExists(toSchemaName(runId));
        assertMetadataInDatabase(toSchemaName(runId), metadata);
    }

    private SimulationMetadata createTestMetadata(String runId) {
        return SimulationMetadata.newBuilder()
                .setSimulationRunId(runId)
                .setStartTimeMs(System.currentTimeMillis())
                .setInitialSeed(12345L)
                .setSamplingInterval(1)
                .build();
    }

    private String toSchemaName(String runId) {
        return "sim_" + runId.replace("-", "_");
    }

    private void assertSchemaExists(String schemaName) throws Exception {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = '" + schemaName.toUpperCase() + "'")) {
            assertTrue(rs.next(), "Schema '" + schemaName + "' should exist.");
        }
    }

    private void assertMetadataInDatabase(String schemaName, SimulationMetadata expectedMetadata) throws Exception {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUsername, dbPassword)) {
            conn.setSchema(schemaName.toUpperCase());
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT \"key\", \"value\" FROM metadata WHERE \"key\" = 'simulation_info'")) {

                assertTrue(rs.next(), "Metadata with key 'simulation_info' should exist.");
                String jsonValue = rs.getString("value");
                assertNotNull(jsonValue);
                assertTrue(jsonValue.contains(expectedMetadata.getSimulationRunId()));
                assertFalse(rs.next(), "Should only be one row for 'simulation_info'.");
            }
        }
    }
}
