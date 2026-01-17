package org.evochora.datapipeline.resources.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.DeltaType;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class FileSystemStorageResourceTest {

    @TempDir
    Path tempDir;

    private FileSystemStorageResource storage;
    private Config config;
    private final List<Path> createdDirectories = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Map<String, String> configMap = Map.of("rootDirectory", tempDir.toAbsolutePath().toString());
        config = ConfigFactory.parseMap(configMap);
        storage = new FileSystemStorageResource("test-storage", config);
    }
    
    @AfterEach
    void tearDown() throws IOException {
        // Clean up directories created by variable expansion tests
        for (Path dir : createdDirectories) {
            if (Files.exists(dir)) {
                Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            }
        }
        createdDirectories.clear();
    }

    private TickData createTick(long tickNumber) {
        return TickData.newBuilder()
                .setTickNumber(tickNumber)
                .setSimulationRunId("test-sim")
                .setCaptureTimeMs(System.currentTimeMillis())
                .build();
    }

    @Test
    void testWriteMessage_ReadMessage_RoundTrip() throws IOException {
        String key = "single_message.pb";
        TickData originalTick = createTick(42);

        // Write using writeMessage (interface method) - returns physical path
        StoragePath path = storage.writeMessage(key, originalTick);

        // Read using physical path returned from write
        TickData readTick = storage.readMessage(path, TickData.parser());
        assertEquals(originalTick, readTick);
    }

    @Test
    void testReadMessage_NotFound() {
        StoragePath nonExistentPath = StoragePath.of("not_found.pb");
        assertThrows(IOException.class, () -> storage.readMessage(nonExistentPath, TickData.parser()));
    }

    @Test
    void testListBatchFiles_Success() throws IOException {
        // Write 3 batch files for test-sim using chunks
        storage.writeChunkBatch(List.of(createChunk(1, 2, 2)), 1, 2);
        storage.writeChunkBatch(List.of(createChunk(10, 20, 11)), 10, 20);
        storage.writeChunkBatch(List.of(createChunk(100, 200, 101)), 100, 200);

        // List all batches for test-sim
        BatchFileListResult result = storage.listBatchFiles("test-sim/", null, 10);

        assertEquals(3, result.getFilenames().size(), "Should find 3 batch files");
        assertTrue(result.getFilenames().stream().allMatch(f -> f.asString().startsWith("test-sim/")));
        assertTrue(result.getFilenames().stream().allMatch(f -> f.asString().contains("batch_")));
        assertFalse(result.isTruncated());
    }

    @Test
    void testConcurrentRead() throws Exception {
        // Write a batch of chunks
        List<TickDataChunk> batch = new ArrayList<>();
        for(int i=0; i<10; i++) {
            batch.add(createChunk(i * 10, i * 10 + 9, 10));
        }
        StoragePath batchPath = storage.writeChunkBatch(batch, 0, 99);

        // Read the batch concurrently from 10 threads
        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicBoolean failed = new AtomicBoolean(false);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    List<TickDataChunk> readBatch = storage.readChunkBatch(batchPath);
                    assertEquals(batch.size(), readBatch.size());
                    assertEquals(batch, readBatch);
                } catch (Exception e) {
                    failed.set(true);
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertFalse(failed.get(), "Concurrent read test failed");
    }

    @Test
    void testHierarchicalKeys() throws IOException {
        String key = "a/b/c/d.pb";
        TickData tick = createTick(1);

        // writeMessage should create nested directories automatically
        StoragePath path = storage.writeMessage(key, tick);

        // Verify the file was created and is readable
        TickData readTick = storage.readMessage(path, TickData.parser());
        assertEquals(tick, readTick, "Read tick should match written tick");

        // Verify all parent directories were created
        File parentDir = new File(tempDir.toFile(), "a/b/c");
        assertTrue(parentDir.exists(), "Parent directory a/b/c should exist");
        assertTrue(parentDir.isDirectory(), "a/b/c should be a directory");
    }

    // Variable expansion tests

    @Test
    void testVariableExpansion_SystemProperty() {
        String javaTmpDir = System.getProperty("java.io.tmpdir");
        assertNotNull(javaTmpDir, "java.io.tmpdir system property should be defined");

        Path testDir = Path.of(javaTmpDir, "evochora-test-sysprop");
        createdDirectories.add(testDir);
        
        // Use ConfigFactory.parseMap for variable definitions to avoid systemProperties() caching issues
        Config varsConfig = ConfigFactory.parseMap(Map.of("java.io.tmpdir", javaTmpDir));
        Config config = ConfigFactory.parseString("rootDirectory = ${java.io.tmpdir}/evochora-test-sysprop")
            .withFallback(varsConfig)
            .resolve();

        FileSystemStorageResource storage = new FileSystemStorageResource("test-storage", config);
        assertNotNull(storage);
    }

    @Test
    void testVariableExpansion_EnvironmentVariable() {
        // Set a custom environment-like variable via system properties for testing
        String testDirPath = System.getProperty("java.io.tmpdir") + File.separator + "evochora-test-env";
        
        Path testDir = Path.of(testDirPath);
        createdDirectories.add(testDir);

        // Use ConfigFactory.parseMap for the variable definition to avoid caching issues
        Config varsConfig = ConfigFactory.parseMap(Map.of("TEST_EVOCHORA_DIR", testDirPath));
        Config config = ConfigFactory.parseString("rootDirectory = ${TEST_EVOCHORA_DIR}")
            .withFallback(varsConfig)
            .resolve();

        FileSystemStorageResource storage = new FileSystemStorageResource("test-storage", config);
        assertNotNull(storage);
    }

    @Test
    void testVariableExpansion_MultipleVariables() {
        String javaTmpDir = System.getProperty("java.io.tmpdir");
        
        Path testDir = Path.of(javaTmpDir, "evochora-multi-var-test");
        createdDirectories.add(testDir);

        // Use ConfigFactory.parseMap for variable definitions to avoid systemProperties() caching issues
        Config varsConfig = ConfigFactory.parseMap(Map.of(
            "java.io.tmpdir", javaTmpDir,
            "test.project", "evochora-multi-var-test"
        ));
        Config config = ConfigFactory.parseString("rootDirectory = ${java.io.tmpdir}/${test.project}/data")
            .withFallback(varsConfig)
            .resolve();

        FileSystemStorageResource storage = new FileSystemStorageResource("test-storage", config);
        assertNotNull(storage);
    }

    @Test
    void testVariableExpansion_UndefinedVariable() {
        // HOCON throws ConfigException.UnresolvedSubstitution when resolve() is called on undefined variables
        // Note: Variables must be OUTSIDE quotes for HOCON to recognize them as substitutions
        com.typesafe.config.ConfigException.UnresolvedSubstitution exception = 
            assertThrows(com.typesafe.config.ConfigException.UnresolvedSubstitution.class, () -> {
                ConfigFactory.parseString("rootDirectory = ${THIS_VARIABLE_DOES_NOT_EXIST}/data").resolve();
            });
        assertTrue(exception.getMessage().contains("THIS_VARIABLE_DOES_NOT_EXIST"));
    }

    @Test
    void testVariableExpansion_UnclosedVariable() {
        // HOCON throws a parse exception for unclosed substitutions
        com.typesafe.config.ConfigException.Parse exception = assertThrows(
            com.typesafe.config.ConfigException.Parse.class, () -> {
                ConfigFactory.parseString("rootDirectory = ${user.home/data").resolve();
            });
        // The error message should indicate a parsing problem
        assertNotNull(exception.getMessage());
    }

    @Test
    void testVariableExpansion_MustBeAbsoluteAfterExpansion() {
        // Use ConfigFactory.parseMap for the variable definition to avoid caching issues
        Config varsConfig = ConfigFactory.parseMap(Map.of("test.relative", "relative/path"));
        Config config = ConfigFactory.parseString("rootDirectory = ${test.relative}/data")
            .withFallback(varsConfig)
            .resolve();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            new FileSystemStorageResource("test-storage", config);
        });
        assertTrue(exception.getMessage().contains("must be an absolute path"));
    }

    @Test
    void testVariableExpansion_NoVariables() {
        // Test that paths without variables still work
        Map<String, String> configMap = Map.of("rootDirectory", tempDir.toAbsolutePath().toString());
        Config config = ConfigFactory.parseMap(configMap);

        FileSystemStorageResource storage = new FileSystemStorageResource("test-storage", config);
        assertNotNull(storage);
    }

    @Test
    void testVariableExpansion_JavaTempDir() {
        String javaTmpDir = System.getProperty("java.io.tmpdir");
        assertNotNull(javaTmpDir, "java.io.tmpdir should be defined");

        Path testDir = Path.of(javaTmpDir, "evochora-test");
        createdDirectories.add(testDir);
        
        // Use ConfigFactory.parseMap for variable definitions to avoid systemProperties() caching issues
        Config varsConfig = ConfigFactory.parseMap(Map.of("java.io.tmpdir", javaTmpDir));
        Config config = ConfigFactory.parseString("rootDirectory = ${java.io.tmpdir}/evochora-test")
            .withFallback(varsConfig)
            .resolve();

        FileSystemStorageResource storage = new FileSystemStorageResource("test-storage", config);
        assertNotNull(storage);
    }

    @Test
    void testFindMetadataPath_Success() throws IOException {
        String runId = "test-sim-123";
        
        // Write metadata file
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
                .setSimulationRunId(runId)
                .setStartTimeMs(System.currentTimeMillis())
                .setInitialSeed(42)
                .build();
        
        String key = runId + "/raw/metadata.pb";
        StoragePath writtenPath = storage.writeMessage(key, metadata);
        
        // Find metadata path
        java.util.Optional<StoragePath> foundPath = storage.findMetadataPath(runId);
        
        assertTrue(foundPath.isPresent(), "Metadata path should be found");
        assertEquals(writtenPath.asString(), foundPath.get().asString(), 
                "Found path should match written path");
        
        // Verify we can read the metadata back
        SimulationMetadata readMetadata = storage.readMessage(foundPath.get(), SimulationMetadata.parser());
        assertEquals(runId, readMetadata.getSimulationRunId());
    }

    @Test
    void testFindMetadataPath_NotFound() throws IOException {
        String runId = "non-existent-sim";
        
        // Try to find metadata for non-existent run
        java.util.Optional<StoragePath> foundPath = storage.findMetadataPath(runId);
        
        assertFalse(foundPath.isPresent(), "Metadata path should not be found for non-existent run");
    }

    @Test
    void testFindMetadataPath_NullRunId() {
        assertThrows(IllegalArgumentException.class, () -> storage.findMetadataPath(null),
                "findMetadataPath should throw IllegalArgumentException for null runId");
    }

    // ========================================================================
    // Chunk Batch Tests (Delta Compression)
    // ========================================================================

    private TickDataChunk createChunk(long firstTick, long lastTick, int tickCount) {
        TickData snapshot = TickData.newBuilder()
                .setTickNumber(firstTick)
                .setSimulationRunId("test-sim")
                .setCaptureTimeMs(System.currentTimeMillis())
                .setCellColumns(CellDataColumns.newBuilder()
                        .addFlatIndices(0)
                        .addMoleculeData(100)
                        .addOwnerIds(1)
                        .build())
                .build();

        TickDataChunk.Builder chunkBuilder = TickDataChunk.newBuilder()
                .setSimulationRunId("test-sim")
                .setFirstTick(firstTick)
                .setLastTick(lastTick)
                .setTickCount(tickCount)
                .setSnapshot(snapshot);

        // Add deltas if tickCount > 1
        for (long tick = firstTick + 1; tick <= lastTick; tick++) {
            TickDelta delta = TickDelta.newBuilder()
                    .setTickNumber(tick)
                    .setCaptureTimeMs(System.currentTimeMillis())
                    .setDeltaType(DeltaType.INCREMENTAL)
                    .setChangedCells(CellDataColumns.newBuilder()
                            .addFlatIndices((int) tick)
                            .addMoleculeData((int) (100 + tick))
                            .addOwnerIds(1)
                            .build())
                    .build();
            chunkBuilder.addDeltas(delta);
        }

        return chunkBuilder.build();
    }

    @Test
    void testWriteChunkBatch_ReadChunkBatch_RoundTrip() throws IOException {
        // Create chunks
        TickDataChunk chunk1 = createChunk(0, 9, 10);
        TickDataChunk chunk2 = createChunk(10, 19, 10);
        List<TickDataChunk> batch = List.of(chunk1, chunk2);

        // Write
        StoragePath path = storage.writeChunkBatch(batch, 0, 19);
        assertNotNull(path);
        assertTrue(path.asString().contains("test-sim"));
        assertTrue(path.asString().contains("batch_"));

        // Read
        List<TickDataChunk> readBatch = storage.readChunkBatch(path);
        assertEquals(2, readBatch.size());
        assertEquals(chunk1, readBatch.get(0));
        assertEquals(chunk2, readBatch.get(1));
    }

    @Test
    void testWriteChunkBatch_EmptyBatch_Throws() {
        assertThrows(IllegalArgumentException.class, 
                () -> storage.writeChunkBatch(List.of(), 0, 0));
    }

    @Test
    void testWriteChunkBatch_InvalidTickOrder_Throws() {
        TickDataChunk chunk = createChunk(100, 109, 10);
        assertThrows(IllegalArgumentException.class, 
                () -> storage.writeChunkBatch(List.of(chunk), 109, 100));
    }

    @Test
    void testReadChunkBatch_NotFound() {
        StoragePath nonExistentPath = StoragePath.of("test-sim/raw/000/000/batch_not_found.pb");
        assertThrows(IOException.class, () -> storage.readChunkBatch(nonExistentPath));
    }
}