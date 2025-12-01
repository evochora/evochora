package org.evochora.datapipeline.services.analytics;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareMetadataWriter;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.resources.database.H2Database;
import org.evochora.datapipeline.resources.storage.FileSystemStorageResource;
import org.evochora.datapipeline.resources.topics.H2TopicResource;
import org.evochora.datapipeline.services.indexers.AnalyticsIndexer;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * End-to-End integration tests for AnalyticsIndexer.
 * <p>
 * Tests the complete flow: Storage → Topic → AnalyticsIndexer → Parquet files.
 * <p>
 * <strong>Test Scenarios:</strong>
 * <ul>
 *   <li>Single batch processing with Parquet file generation</li>
 *   <li>Multiple batches with correct file boundaries (batch-passthrough)</li>
 *   <li>Competing consumers with no overlaps or gaps</li>
 *   <li>Manifest file generation</li>
 *   <li>Hierarchical folder structure</li>
 * </ul>
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class AnalyticsIndexerEndToEndTest {

    private H2Database testDatabase;
    private FileSystemStorageResource testStorage;
    private H2TopicResource testBatchTopic;
    private AnalyticsIndexer<?> indexer;
    private Path tempStorageDir;
    private Path tempAnalyticsDir;

    @BeforeEach
    void setup() throws IOException {
        // Setup temporary storage
        tempStorageDir = Files.createTempDirectory("evochora-test-analytics-");
        tempAnalyticsDir = Files.createTempDirectory("evochora-test-analytics-temp-");
        
        Config storageConfig = ConfigFactory.parseString(
            "rootDirectory = \"" + tempStorageDir.toAbsolutePath().toString().replace("\\", "/") + "\""
        );
        testStorage = new FileSystemStorageResource("test-storage", storageConfig);

        // Setup in-memory H2 database with unique name
        String dbUrl = "jdbc:h2:mem:test-analytics-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        Config dbConfig = ConfigFactory.parseString(
            "jdbcUrl = \"" + dbUrl + "\"\n" +
            "username = \"sa\"\n" +
            "password = \"\""
        );
        testDatabase = new H2Database("test-db", dbConfig);

        // Setup H2 topic for batch notifications
        String topicJdbcUrl = "jdbc:h2:mem:test-analytics-topic-" + UUID.randomUUID();
        Config topicConfig = ConfigFactory.parseString(
            "jdbcUrl = \"" + topicJdbcUrl + "\"\n" +
            "username = \"sa\"\n" +
            "password = \"\"\n" +
            "claimTimeout = 300"
        );
        testBatchTopic = new H2TopicResource("batch-topic", topicConfig);
    }

    @AfterEach
    void cleanup() throws Exception {
        // Stop indexer if still running
        if (indexer != null && indexer.getCurrentState() != IService.State.STOPPED 
            && indexer.getCurrentState() != IService.State.ERROR) {
            indexer.stop();
            await().atMost(5, TimeUnit.SECONDS)
                .until(() -> indexer.getCurrentState() == IService.State.STOPPED 
                    || indexer.getCurrentState() == IService.State.ERROR);
        }

        if (testBatchTopic != null) {
            testBatchTopic.close();
        }

        if (testDatabase != null) {
            testDatabase.close();
        }

        // Cleanup temp directories
        cleanupDirectory(tempStorageDir);
        cleanupDirectory(tempAnalyticsDir);
    }

    @Test
    void testSingleBatch_GeneratesParquetFile() throws Exception {
        // Given: Create test run with metadata and one batch
        String runId = "20251201-120000-" + UUID.randomUUID();
        SimulationMetadata metadata = createTestMetadata(runId, 10);
        indexMetadata(runId, metadata);

        // Write one batch with 10 ticks (with organisms for population metrics)
        List<TickData> batch = createTestTicksWithOrganisms(runId, 0, 10);
        StoragePath key = testStorage.writeBatch(batch, 0, 9);

        // Create AnalyticsIndexer
        indexer = createAnalyticsIndexer("test-indexer", runId);
        indexer.start();

        // Wait for indexer to be ready
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);

        // When: Send batch notification to topic
        sendBatchInfoToTopic(runId, key.asString(), 0, 9);

        // Then: Wait for Parquet file to be created
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> findParquetFiles(runId, "population").size() >= 1);

        // Verify exactly one Parquet file created
        List<Path> parquetFiles = findParquetFiles(runId, "population");
        assertEquals(1, parquetFiles.size(), "Should have exactly 1 Parquet file");

        // Verify filename format: batch_00000000000000000000_00000000000000000009.parquet
        String filename = parquetFiles.get(0).getFileName().toString();
        assertTrue(filename.matches("batch_\\d{20}_\\d{20}\\.parquet"),
            "Filename should match pattern: " + filename);

        // Verify start < end in filename
        long[] tickRange = extractTickRange(filename);
        assertTrue(tickRange[0] <= tickRange[1],
            "startTick should be <= endTick: " + tickRange[0] + " vs " + tickRange[1]);
        assertEquals(0, tickRange[0], "startTick should be 0");
        assertEquals(9, tickRange[1], "endTick should be 9");
    }

    @Test
    void testMultipleBatches_BatchPassthrough() throws Exception {
        // Given: Create test run with metadata
        String runId = "20251201-130000-" + UUID.randomUUID();
        SimulationMetadata metadata = createTestMetadata(runId, 10);
        indexMetadata(runId, metadata);

        // Write 3 batches with non-overlapping tick ranges
        List<TickData> batch1 = createTestTicksWithOrganisms(runId, 0, 25);
        List<TickData> batch2 = createTestTicksWithOrganisms(runId, 25, 25);
        List<TickData> batch3 = createTestTicksWithOrganisms(runId, 50, 25);

        StoragePath key1 = testStorage.writeBatch(batch1, 0, 24);
        StoragePath key2 = testStorage.writeBatch(batch2, 25, 49);
        StoragePath key3 = testStorage.writeBatch(batch3, 50, 74);

        // Create AnalyticsIndexer
        indexer = createAnalyticsIndexer("test-indexer", runId);
        indexer.start();

        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);

        // When: Send batch notifications
        sendBatchInfoToTopic(runId, key1.asString(), 0, 24);
        sendBatchInfoToTopic(runId, key2.asString(), 25, 49);
        sendBatchInfoToTopic(runId, key3.asString(), 50, 74);

        // Then: Wait for all Parquet files
        await().atMost(15, TimeUnit.SECONDS)
            .until(() -> findParquetFiles(runId, "population").size() >= 3);

        // Verify exactly 3 Parquet files (batch-passthrough: one per batch)
        List<Path> parquetFiles = findParquetFiles(runId, "population");
        assertEquals(3, parquetFiles.size(), 
            "Should have exactly 3 Parquet files (batch-passthrough)");

        // Verify no overlaps and no gaps
        List<long[]> ranges = parquetFiles.stream()
            .map(p -> extractTickRange(p.getFileName().toString()))
            .sorted(Comparator.comparingLong(r -> r[0]))
            .toList();

        // Verify ranges are contiguous
        for (int i = 0; i < ranges.size() - 1; i++) {
            long currentEnd = ranges.get(i)[1];
            long nextStart = ranges.get(i + 1)[0];
            assertEquals(currentEnd + 1, nextStart,
                "Gap detected between batch " + i + " (end=" + currentEnd + 
                ") and batch " + (i + 1) + " (start=" + nextStart + ")");
        }

        // Verify total coverage
        assertEquals(0, ranges.get(0)[0], "First batch should start at tick 0");
        assertEquals(74, ranges.get(ranges.size() - 1)[1], "Last batch should end at tick 74");
    }

    @Test
    void testManifestFileGenerated() throws Exception {
        // Given: Create test run with metadata
        String runId = "20251201-140000-" + UUID.randomUUID();
        SimulationMetadata metadata = createTestMetadata(runId, 10);
        indexMetadata(runId, metadata);

        // Write one batch
        List<TickData> batch = createTestTicksWithOrganisms(runId, 0, 5);
        StoragePath key = testStorage.writeBatch(batch, 0, 4);

        // Create and start AnalyticsIndexer
        indexer = createAnalyticsIndexer("test-indexer", runId);
        indexer.start();

        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);

        // Send batch notification
        sendBatchInfoToTopic(runId, key.asString(), 0, 4);

        // Wait for processing
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> findParquetFiles(runId, "population").size() >= 1);

        // Then: Verify manifest file exists
        Path manifestPath = tempStorageDir.resolve(runId)
            .resolve("analytics")
            .resolve("population")
            .resolve("metadata.json");

        assertTrue(Files.exists(manifestPath), 
            "Manifest file should exist at: " + manifestPath);

        // Verify manifest content
        String manifestContent = Files.readString(manifestPath);
        assertTrue(manifestContent.contains("\"id\""), "Manifest should contain id field");
        assertTrue(manifestContent.contains("population"), "Manifest should reference population metric");
    }

    @Test
    void testHierarchicalFolderStructure() throws Exception {
        // Given: Create test run with ticks in specific range for hierarchical path
        String runId = "20251201-150000-" + UUID.randomUUID();
        SimulationMetadata metadata = createTestMetadata(runId, 10);
        indexMetadata(runId, metadata);

        // Write batch starting at tick 0 (should be in 000/000/ folder)
        List<TickData> batch = createTestTicksWithOrganisms(runId, 0, 10);
        StoragePath key = testStorage.writeBatch(batch, 0, 9);

        // Create AnalyticsIndexer with folder structure
        indexer = createAnalyticsIndexer("test-indexer", runId);
        indexer.start();

        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);

        sendBatchInfoToTopic(runId, key.asString(), 0, 9);

        // Wait for Parquet file
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> findParquetFiles(runId, "population").size() >= 1);

        // Then: Verify hierarchical path structure
        List<Path> parquetFiles = findParquetFiles(runId, "population");
        assertFalse(parquetFiles.isEmpty(), "Should have at least one Parquet file");

        // Path should contain lod0/000/000 for ticks 0-9
        String relativePath = tempStorageDir.relativize(parquetFiles.get(0)).toString();
        assertTrue(relativePath.contains("lod0"), "Path should contain lod0");
        assertTrue(relativePath.contains("000"), "Path should contain hierarchical folders");
    }

    @Test
    void testMultipleLodLevels() throws Exception {
        // Given: Create test run with metadata
        String runId = "20251201-155000-" + UUID.randomUUID();
        SimulationMetadata metadata = createTestMetadata(runId, 10);
        indexMetadata(runId, metadata);

        // Write one batch with 100 ticks (to have data in lod1 which samples every 10th tick)
        List<TickData> batch = createTestTicksWithOrganisms(runId, 0, 100);
        StoragePath key = testStorage.writeBatch(batch, 0, 99);

        // Create AnalyticsIndexer with 2 LOD levels
        indexer = createAnalyticsIndexerWithLodLevels("test-indexer", runId, 2);
        indexer.start();

        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);

        // Send batch notification
        sendBatchInfoToTopic(runId, key.asString(), 0, 99);

        // Wait for Parquet files - should have 2 (one per LOD level)
        await().atMost(15, TimeUnit.SECONDS)
            .until(() -> findParquetFiles(runId, "population").size() >= 2);

        // Then: Verify both LOD levels exist
        List<Path> parquetFiles = findParquetFiles(runId, "population");
        assertEquals(2, parquetFiles.size(), "Should have 2 Parquet files (lod0 + lod1)");

        // Verify lod0 and lod1 folders exist
        Path analyticsDir = tempStorageDir.resolve(runId).resolve("analytics").resolve("population");
        assertTrue(Files.exists(analyticsDir.resolve("lod0")), "lod0 folder should exist");
        assertTrue(Files.exists(analyticsDir.resolve("lod1")), "lod1 folder should exist");

        // Verify each LOD level has one file
        long lod0Files = parquetFiles.stream()
            .filter(p -> p.toString().contains("lod0"))
            .count();
        long lod1Files = parquetFiles.stream()
            .filter(p -> p.toString().contains("lod1"))
            .count();

        assertEquals(1, lod0Files, "Should have 1 file in lod0");
        assertEquals(1, lod1Files, "Should have 1 file in lod1");
    }

    @Test
    void testMetricsTracking() throws Exception {
        // Given: Create test run
        String runId = "20251201-160000-" + UUID.randomUUID();
        SimulationMetadata metadata = createTestMetadata(runId, 10);
        indexMetadata(runId, metadata);

        // Write 2 batches
        List<TickData> batch1 = createTestTicksWithOrganisms(runId, 0, 10);
        List<TickData> batch2 = createTestTicksWithOrganisms(runId, 10, 10);

        StoragePath key1 = testStorage.writeBatch(batch1, 0, 9);
        StoragePath key2 = testStorage.writeBatch(batch2, 10, 19);

        // Create and start AnalyticsIndexer
        indexer = createAnalyticsIndexer("test-indexer", runId);
        indexer.start();

        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> indexer.getCurrentState() == IService.State.RUNNING);

        // Send batch notifications
        sendBatchInfoToTopic(runId, key1.asString(), 0, 9);
        sendBatchInfoToTopic(runId, key2.asString(), 10, 19);

        // Wait for processing
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> {
                Map<String, Number> metrics = indexer.getMetrics();
                return metrics.get("batches_processed").intValue() >= 2;
            });

        // Then: Verify metrics
        Map<String, Number> metrics = indexer.getMetrics();
        assertEquals(2, metrics.get("batches_processed").intValue(),
            "Should have processed 2 batches");
        assertEquals(20, metrics.get("ticks_processed").intValue(),
            "Should have processed 20 ticks");
    }

    // ========== Helper Methods ==========

    private AnalyticsIndexer<?> createAnalyticsIndexer(String name, String runId) {
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 10000
            tempDirectory = "%s"
            folderStructure {
                levels = [100000000, 100000]
            }
            plugins = [
                {
                    className = "org.evochora.datapipeline.services.analytics.plugins.PopulationMetricsPlugin"
                    options {
                        metricId = "population"
                        samplingInterval = 1
                        lodFactor = 10
                        lodLevels = 1
                    }
                }
            ]
            """.formatted(runId, tempAnalyticsDir.toAbsolutePath().toString().replace("\\", "/")));

        // Wrap database resource with db-meta-read usage type
        ResourceContext dbContext = new ResourceContext(
            name,
            "metadata",
            "db-meta-read",
            "test-db",
            Collections.emptyMap()
        );
        IResource wrappedDatabase = testDatabase.getWrappedResource(dbContext);

        // Wrap topic resource with topic-read usage type
        ResourceContext topicContext = new ResourceContext(
            name,
            "topic",
            "topic-read",
            "batch-topic",
            Map.of("consumerGroup", "test-analytics-" + UUID.randomUUID())
        );
        IResource wrappedTopic = testBatchTopic.getWrappedResource(topicContext);

        // Wrap storage for analytics output
        ResourceContext analyticsContext = new ResourceContext(
            name,
            "analyticsOutput",
            "analytics-write",
            "test-storage",
            Collections.emptyMap()
        );
        IResource wrappedAnalyticsStorage = testStorage.getWrappedResource(analyticsContext);

        Map<String, List<IResource>> resources = Map.of(
            "storage", List.of(testStorage),
            "metadata", List.of(wrappedDatabase),
            "topic", List.of(wrappedTopic),
            "analyticsOutput", List.of(wrappedAnalyticsStorage)
        );

        return new AnalyticsIndexer<>(name, config, resources);
    }

    private AnalyticsIndexer<?> createAnalyticsIndexerWithLodLevels(String name, String runId, int lodLevels) {
        Config config = ConfigFactory.parseString("""
            runId = "%s"
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 10000
            tempDirectory = "%s"
            folderStructure {
                levels = [100000000, 100000]
            }
            plugins = [
                {
                    className = "org.evochora.datapipeline.services.analytics.plugins.PopulationMetricsPlugin"
                    options {
                        metricId = "population"
                        samplingInterval = 1
                        lodFactor = 10
                        lodLevels = %d
                    }
                }
            ]
            """.formatted(runId, tempAnalyticsDir.toAbsolutePath().toString().replace("\\", "/"), lodLevels));

        // Same resource wiring as createAnalyticsIndexer
        ResourceContext dbContext = new ResourceContext(
            name,
            "metadata",
            "db-meta-read",
            "test-db",
            Collections.emptyMap()
        );
        IResource wrappedDatabase = testDatabase.getWrappedResource(dbContext);

        ResourceContext topicContext = new ResourceContext(
            name,
            "topic",
            "topic-read",
            "batch-topic",
            Map.of("consumerGroup", "test-analytics-" + UUID.randomUUID())
        );
        IResource wrappedTopic = testBatchTopic.getWrappedResource(topicContext);

        ResourceContext analyticsContext = new ResourceContext(
            name,
            "analyticsOutput",
            "analytics-write",
            "test-storage",
            Collections.emptyMap()
        );
        IResource wrappedAnalyticsStorage = testStorage.getWrappedResource(analyticsContext);

        Map<String, List<IResource>> resources = Map.of(
            "storage", List.of(testStorage),
            "metadata", List.of(wrappedDatabase),
            "topic", List.of(wrappedTopic),
            "analyticsOutput", List.of(wrappedAnalyticsStorage)
        );

        return new AnalyticsIndexer<>(name, config, resources);
    }

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
        String storageKey = runId + "/raw/metadata.pb";
        testStorage.writeMessage(storageKey, metadata);

        // Write metadata to database
        ResourceContext dbContext = new ResourceContext(
            "test-indexer",
            "database",
            "db-meta-write",
            "test-db",
            Collections.emptyMap()
        );
        IResource wrappedDatabase = testDatabase.getWrappedResource(dbContext);

        if (wrappedDatabase instanceof org.evochora.datapipeline.api.resources.database.IMetadataWriter metadataWriter) {
            ((IResourceSchemaAwareMetadataWriter) metadataWriter).setSimulationRun(runId);
            metadataWriter.insertMetadata(metadata);
            metadataWriter.close();
        } else {
            throw new IllegalStateException("Expected IMetadataWriter but got: " + wrappedDatabase.getClass());
        }
    }

    private List<TickData> createTestTicksWithOrganisms(String runId, long startTick, int count) {
        List<TickData> ticks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // Create some organisms with varying energy for interesting metrics
            List<OrganismState> organisms = new ArrayList<>();
            int numOrganisms = 5 + (i % 10);  // Vary organism count per tick
            
            for (int j = 0; j < numOrganisms; j++) {
                organisms.add(OrganismState.newBuilder()
                    .setOrganismId(j)
                    .setEnergy(100 + (j * 10) + (i * 5))  // Varying energy
                    .build());
            }
            
            ticks.add(TickData.newBuilder()
                .setSimulationRunId(runId)
                .setTickNumber(startTick + i)
                .setCaptureTimeMs(System.currentTimeMillis())
                .setTotalOrganismsCreated(numOrganisms + i)  // Accumulating total
                .addAllOrganisms(organisms)
                .build());
        }
        return ticks;
    }

    private void sendBatchInfoToTopic(String runId, String storageKey, long tickStart, long tickEnd) throws Exception {
        ResourceContext writerContext = new ResourceContext(
            "test-sender",
            "topic",
            "topic-write",
            "batch-topic",
            Collections.emptyMap()
        );

        @SuppressWarnings("unchecked")
        ITopicWriter<BatchInfo> writer = (ITopicWriter<BatchInfo>) testBatchTopic.getWrappedResource(writerContext);
        writer.setSimulationRun(runId);

        BatchInfo batchInfo = BatchInfo.newBuilder()
            .setSimulationRunId(runId)
            .setStoragePath(storageKey)
            .setTickStart(tickStart)
            .setTickEnd(tickEnd)
            .setWrittenAtMs(System.currentTimeMillis())
            .build();

        writer.send(batchInfo);
    }

    private List<Path> findParquetFiles(String runId, String metricId) throws IOException {
        Path analyticsDir = tempStorageDir.resolve(runId).resolve("analytics").resolve(metricId);
        
        if (!Files.exists(analyticsDir)) {
            return Collections.emptyList();
        }

        try (Stream<Path> walk = Files.walk(analyticsDir)) {
            return walk
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".parquet"))
                .toList();
        }
    }

    private long[] extractTickRange(String filename) {
        // Pattern: batch_00000000000000000000_00000000000000000009.parquet
        Pattern pattern = Pattern.compile("batch_(\\d+)_(\\d+)\\.parquet");
        Matcher matcher = pattern.matcher(filename);
        
        if (matcher.matches()) {
            return new long[] {
                Long.parseLong(matcher.group(1)),
                Long.parseLong(matcher.group(2))
            };
        }
        
        throw new IllegalArgumentException("Invalid filename format: " + filename);
    }

    private void cleanupDirectory(Path dir) {
        if (dir != null && Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (IOException e) {
                // Ignore cleanup errors
            }
        }
    }
}

