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
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
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
        storage.writeChunkBatchStreaming(List.of(createChunk(1, 2, 2)).iterator());
        storage.writeChunkBatchStreaming(List.of(createChunk(10, 20, 11)).iterator());
        storage.writeChunkBatchStreaming(List.of(createChunk(100, 200, 101)).iterator());

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
        StoragePath batchPath = storage.writeChunkBatchStreaming(batch.iterator()).path();

        // Read the batch concurrently from 10 threads
        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicBoolean failed = new AtomicBoolean(false);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    List<TickDataChunk> readBatch = new ArrayList<>();
                    storage.forEachChunk(batchPath, readBatch::add);
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
    void testWriteChunkBatch_ReadChunkBatch_RoundTrip() throws Exception {
        // Create chunks
        TickDataChunk chunk1 = createChunk(0, 9, 10);
        TickDataChunk chunk2 = createChunk(10, 19, 10);
        List<TickDataChunk> batch = List.of(chunk1, chunk2);

        // Write
        StoragePath path = storage.writeChunkBatchStreaming(batch.iterator()).path();
        assertNotNull(path);
        assertTrue(path.asString().contains("test-sim"));
        assertTrue(path.asString().contains("batch_"));

        // Read
        List<TickDataChunk> readBatch = new ArrayList<>();
        storage.forEachChunk(path, readBatch::add);
        assertEquals(2, readBatch.size());
        assertEquals(chunk1, readBatch.get(0));
        assertEquals(chunk2, readBatch.get(1));
    }

    @Test
    void testWriteChunkBatch_EmptyBatch_Throws() {
        assertThrows(IllegalArgumentException.class, 
                () -> storage.writeChunkBatchStreaming(List.<TickDataChunk>of().iterator()));
    }

    @Test
    void testForEachChunk_NotFound() {
        StoragePath nonExistentPath = StoragePath.of("test-sim/raw/000/000/batch_not_found.pb");
        assertThrows(IOException.class, () -> storage.forEachChunk(nonExistentPath, chunk -> {}));
    }

    // ========================================================================
    // listBatchFiles Deduplication Tests
    // ========================================================================

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*AbstractBatchStorageResource.*",
               messagePattern = ".*Duplicate batch files for firstTick.*")
    void testListBatchFiles_Deduplication_KeepsSmallerLastTick() throws IOException {
        // Simulate crash scenario: two files with same firstTick but different lastTick
        // This happens when a batch file is being written during crash

        // Create two batch files manually with same firstTick (0) but different lastTick
        // File 1: batch_0_9 (the complete file before crash)
        TickDataChunk chunk1 = createChunk(0, 9, 10);
        storage.writeChunkBatchStreaming(List.of(chunk1).iterator());

        // File 2: batch_0_19 (partial file from crash - simulated by writing directly)
        // We need to manually create this file since writeChunkBatchStreaming would use different folder
        File batchDir = new File(tempDir.toFile(), "test-sim/raw/000/000");
        batchDir.mkdirs();
        File duplicateFile = new File(batchDir, "batch_0000000000000000000_0000000000000000019.pb");
        // Write minimal content (just to make the file exist)
        TickDataChunk chunk2 = createChunk(0, 19, 20);
        try (java.io.OutputStream out = Files.newOutputStream(duplicateFile.toPath())) {
            chunk2.writeDelimitedTo(out);
        }

        // List batch files - should only return one (the one with smaller lastTick)
        BatchFileListResult result = storage.listBatchFiles("test-sim/", null, 10);

        assertEquals(1, result.getFilenames().size(),
                "Should deduplicate to 1 file when same firstTick");
        assertTrue(result.getFilenames().get(0).asString().contains("_0000000000000000009.pb"),
                "Should keep the file with smaller lastTick (9, not 19)");
    }

    @Test
    void testListBatchFiles_NoDuplicates_ReturnsAll() throws IOException {
        // Write multiple batch files with different firstTick values
        TickDataChunk chunk1 = createChunk(0, 9, 10);
        TickDataChunk chunk2 = createChunk(10, 19, 10);
        TickDataChunk chunk3 = createChunk(20, 29, 10);

        storage.writeChunkBatchStreaming(List.of(chunk1).iterator());
        storage.writeChunkBatchStreaming(List.of(chunk2).iterator());
        storage.writeChunkBatchStreaming(List.of(chunk3).iterator());

        // List batch files - should return all 3
        BatchFileListResult result = storage.listBatchFiles("test-sim/", null, 10);

        assertEquals(3, result.getFilenames().size(), "Should return all 3 unique batch files");
    }

    // ========================================================================
    // findLastBatchFile Tests
    // ========================================================================

    @Test
    void testFindLastBatchFile_Success() throws IOException {
        // Write multiple batch files
        TickDataChunk chunk1 = createChunk(0, 9, 10);
        TickDataChunk chunk2 = createChunk(10, 19, 10);
        TickDataChunk chunk3 = createChunk(100, 109, 10);

        storage.writeChunkBatchStreaming(List.of(chunk1).iterator());
        storage.writeChunkBatchStreaming(List.of(chunk2).iterator());
        StoragePath lastPath = storage.writeChunkBatchStreaming(List.of(chunk3).iterator()).path();

        // Find last batch file
        java.util.Optional<org.evochora.datapipeline.api.resources.storage.StoragePath> found =
            storage.findLastBatchFile("test-sim/raw/");

        assertTrue(found.isPresent(), "Should find last batch file");
        assertEquals(lastPath.asString(), found.get().asString(),
            "Should return the batch file with highest tick numbers");
    }

    @Test
    void testFindLastBatchFile_NullRunIdPrefix_ThrowsException() {
        assertThrows(IllegalArgumentException.class,
            () -> storage.findLastBatchFile(null),
            "Should throw IllegalArgumentException for null runIdPrefix");
    }

    @Test
    void testFindLastBatchFile_NonExistentDirectory_ReturnsEmpty() throws IOException {
        java.util.Optional<org.evochora.datapipeline.api.resources.storage.StoragePath> found =
            storage.findLastBatchFile("non-existent-run/raw/");

        assertFalse(found.isPresent(), "Should return empty for non-existent directory");
    }

    @Test
    void testFindLastBatchFile_EmptyDirectory_ReturnsEmpty() throws IOException {
        // Create empty directory structure
        File emptyDir = new File(tempDir.toFile(), "empty-run/raw/000/000");
        emptyDir.mkdirs();

        java.util.Optional<org.evochora.datapipeline.api.resources.storage.StoragePath> found =
            storage.findLastBatchFile("empty-run/raw/");

        assertFalse(found.isPresent(), "Should return empty for directory with no batch files");
    }

    @Test
    void testFindLastBatchFile_MultipleFolders_ReturnsLastBatch() throws IOException {
        // Create batch files in different folder levels
        // Folder structure: 000/000, 000/001, 001/000
        TickDataChunk chunk1 = createChunk(0, 9, 10);           // -> 000/000
        TickDataChunk chunk2 = createChunk(100_000, 100_009, 10); // -> 000/001
        TickDataChunk chunk3 = createChunk(100_000_000, 100_000_009, 10); // -> 001/000

        storage.writeChunkBatchStreaming(List.of(chunk1).iterator());
        storage.writeChunkBatchStreaming(List.of(chunk2).iterator());
        StoragePath lastPath = storage.writeChunkBatchStreaming(List.of(chunk3).iterator()).path();

        // Find last batch file - should be in folder 001/000
        java.util.Optional<org.evochora.datapipeline.api.resources.storage.StoragePath> found =
            storage.findLastBatchFile("test-sim/raw/");

        assertTrue(found.isPresent(), "Should find last batch file across folders");
        assertEquals(lastPath.asString(), found.get().asString(),
            "Should return batch from highest numbered folder");
    }

    @Test
    void testFindLastBatchFile_EmptySubdirectory_BacktracksToNextFolder() throws IOException {
        // Write batch to 000/000
        TickDataChunk chunk = createChunk(0, 9, 10);
        StoragePath expectedPath = storage.writeChunkBatchStreaming(List.of(chunk).iterator()).path();

        // Create empty folder 000/001 (higher numbered but empty)
        File emptyHigherFolder = new File(tempDir.toFile(), "test-sim/raw/000/001");
        emptyHigherFolder.mkdirs();

        // findLastBatchFile should backtrack from empty 001 to 000
        java.util.Optional<org.evochora.datapipeline.api.resources.storage.StoragePath> found =
            storage.findLastBatchFile("test-sim/raw/");

        assertTrue(found.isPresent(), "Should find batch file after backtracking from empty folder");
        assertEquals(expectedPath.asString(), found.get().asString(),
            "Should return batch from non-empty folder after backtracking");
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*AbstractBatchStorageResource.*",
               messagePattern = ".*Duplicate batch files for firstTick.*")
    void testFindLastBatchFile_Deduplication_PrefersSmallerLastTick() throws IOException {
        // Write a normal batch file
        TickDataChunk chunk1 = createChunk(100, 109, 10);
        storage.writeChunkBatchStreaming(List.of(chunk1).iterator());

        // Manually create a duplicate file with same firstTick but larger lastTick
        // (simulates crash scenario)
        File batchDir = new File(tempDir.toFile(), "test-sim/raw/000/000");
        File duplicateFile = new File(batchDir, "batch_0000000000000000100_0000000000000000119.pb");
        TickDataChunk chunk2 = createChunk(100, 119, 20);
        try (java.io.OutputStream out = Files.newOutputStream(duplicateFile.toPath())) {
            chunk2.writeDelimitedTo(out);
        }

        // Find last batch file - should prefer the one with smaller lastTick (109)
        java.util.Optional<org.evochora.datapipeline.api.resources.storage.StoragePath> found =
            storage.findLastBatchFile("test-sim/raw/");

        assertTrue(found.isPresent(), "Should find batch file");
        assertTrue(found.get().asString().contains("_0000000000000000109.pb"),
            "Should prefer batch file with smaller lastTick for deduplication");
    }

    @Test
    void testFindLastBatchFile_IgnoresTmpFiles() throws IOException {
        // Write a normal batch file
        TickDataChunk chunk = createChunk(0, 9, 10);
        StoragePath normalPath = storage.writeChunkBatchStreaming(List.of(chunk).iterator()).path();

        // Create a .tmp file that would sort higher
        File batchDir = new File(tempDir.toFile(), "test-sim/raw/000/000");
        File tmpFile = new File(batchDir, "batch_0000000000000001000_0000000000000001009.pb.tmp");
        tmpFile.createNewFile();

        // Find last batch file - should ignore .tmp file
        java.util.Optional<org.evochora.datapipeline.api.resources.storage.StoragePath> found =
            storage.findLastBatchFile("test-sim/raw/");

        assertTrue(found.isPresent(), "Should find batch file");
        assertEquals(normalPath.asString(), found.get().asString(),
            "Should ignore .tmp files and return valid batch file");
    }

    // ========================================================================
    // findBatchFileContaining Tests
    // ========================================================================

    @Test
    void testFindBatchFileContaining_TickInsideRange_ReturnsThatBatch() throws IOException {
        storage.writeChunkBatchStreaming(List.of(createChunk(0, 9, 10)).iterator());
        StoragePath middlePath = storage.writeChunkBatchStreaming(List.of(createChunk(10, 19, 10)).iterator()).path();
        storage.writeChunkBatchStreaming(List.of(createChunk(20, 29, 10)).iterator());

        java.util.Optional<StoragePath> found = storage.findBatchFileContaining("test-sim/raw/", 15);

        assertTrue(found.isPresent(), "Should find the batch covering tick 15");
        assertEquals(middlePath.asString(), found.get().asString());
    }

    @Test
    void testFindBatchFileContaining_TickOnRangeBounds_ReturnsThatBatch() throws IOException {
        storage.writeChunkBatchStreaming(List.of(createChunk(0, 9, 10)).iterator());
        StoragePath secondPath = storage.writeChunkBatchStreaming(List.of(createChunk(10, 19, 10)).iterator()).path();

        assertEquals(secondPath.asString(),
            storage.findBatchFileContaining("test-sim/raw/", 10).orElseThrow().asString(),
            "First tick of a batch is covered by that batch");
        assertEquals(secondPath.asString(),
            storage.findBatchFileContaining("test-sim/raw/", 19).orElseThrow().asString(),
            "Last tick of a batch is covered by that batch");
    }

    @Test
    void testFindBatchFileContaining_TickInGapBetweenBatches_ReturnsEmpty() throws IOException {
        storage.writeChunkBatchStreaming(List.of(createChunk(0, 9, 10)).iterator());
        storage.writeChunkBatchStreaming(List.of(createChunk(20, 29, 10)).iterator());

        java.util.Optional<StoragePath> found = storage.findBatchFileContaining("test-sim/raw/", 15);

        assertFalse(found.isPresent(), "No batch covers a tick between two recorded ranges");
    }

    @Test
    void testFindBatchFileContaining_TickBeforeFirstBatch_ReturnsEmpty() throws IOException {
        // A run forked from another begins where its window begins; nothing precedes it
        storage.writeChunkBatchStreaming(List.of(createChunk(150_000, 150_009, 10)).iterator());

        java.util.Optional<StoragePath> found = storage.findBatchFileContaining("test-sim/raw/", 0);

        assertFalse(found.isPresent(), "No batch covers a tick before the run's first batch");
    }

    @Test
    void testFindBatchFileContaining_TickBeyondLastBatch_ReturnsEmpty() throws IOException {
        storage.writeChunkBatchStreaming(List.of(createChunk(0, 9, 10)).iterator());

        java.util.Optional<StoragePath> found = storage.findBatchFileContaining("test-sim/raw/", 1000);

        assertFalse(found.isPresent(), "No batch covers a tick beyond the recorded data");
    }

    @Test
    void testFindBatchFileContaining_MultipleFolders_ReturnsBatchFromMatchingFolder() throws IOException {
        storage.writeChunkBatchStreaming(List.of(createChunk(0, 9, 10)).iterator());
        StoragePath higherPath = storage.writeChunkBatchStreaming(
            List.of(createChunk(100_000_000, 100_000_009, 10)).iterator()).path();

        java.util.Optional<StoragePath> found = storage.findBatchFileContaining("test-sim/raw/", 100_000_005);

        assertTrue(found.isPresent(), "Should find the batch across folder levels");
        assertEquals(higherPath.asString(), found.get().asString());
    }

    @Test
    void testFindBatchFileContaining_NonExistentRun_ReturnsEmpty() throws IOException {
        assertFalse(storage.findBatchFileContaining("non-existent-run/raw/", 5).isPresent(),
            "Should return empty for a run without storage");
    }

    @Test
    void testFindBatchFileContaining_InvalidArguments_ThrowsException() {
        assertThrows(IllegalArgumentException.class,
            () -> storage.findBatchFileContaining(null, 5),
            "Should throw IllegalArgumentException for null runIdPrefix");
        assertThrows(IllegalArgumentException.class,
            () -> storage.findBatchFileContaining("test-sim/raw/", -1),
            "Should throw IllegalArgumentException for a negative tick");
    }

    @Test
    void testFindBatchFileContaining_BatchStartsInPrecedingFolder_StepsBack() throws IOException {
        // The batch starts in folder 000/000 and reaches into the tick range of folder 000/001
        StoragePath spanningPath = storage.writeChunkBatchStreaming(
            List.of(createChunk(99_990, 100_010, 21)).iterator()).path();
        storage.writeChunkBatchStreaming(List.of(createChunk(100_020, 100_029, 10)).iterator());

        java.util.Optional<StoragePath> found = storage.findBatchFileContaining("test-sim/raw/", 100_005);

        assertTrue(found.isPresent(), "Should step back into the preceding folder");
        assertEquals(spanningPath.asString(), found.get().asString());
    }

    @Test
    void testFindBatchFileContaining_FolderHoldsOnlyLaterBatches_ReturnsEmpty() throws IOException {
        storage.writeChunkBatchStreaming(List.of(createChunk(99_990, 100_010, 21)).iterator());
        storage.writeChunkBatchStreaming(List.of(createChunk(100_020, 100_029, 10)).iterator());

        java.util.Optional<StoragePath> found = storage.findBatchFileContaining("test-sim/raw/", 100_015);

        assertFalse(found.isPresent(), "No batch covers a tick between the recorded ranges");
    }

    @Test
    void testFindBatchFileContaining_FolderStructureDoesNotMatchRun_Throws() throws IOException {
        storage.writeChunkBatchStreaming(List.of(createChunk(0, 9, 10)).iterator());

        FileSystemStorageResource deeperStorage = new FileSystemStorageResource("deeper-storage",
            ConfigFactory.parseString("folderStructure { levels = [100000000, 100000, 1000] }")
                .withFallback(config));

        IllegalStateException containing = assertThrows(IllegalStateException.class,
            () -> deeperStorage.findBatchFileContaining("test-sim/raw/", 5));
        assertTrue(containing.getMessage().contains("test-sim/raw/"), "Message should name the run prefix");
        assertTrue(containing.getMessage().contains("tick 5"), "Message should name the requested tick");
        assertTrue(containing.getMessage().contains("1000"), "Message should name the configured levels");

        IllegalStateException last = assertThrows(IllegalStateException.class,
            () -> deeperStorage.findLastBatchFile("test-sim/raw/"));
        assertTrue(last.getMessage().contains("test-sim/raw/"), "Message should name the run prefix");
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*AbstractBatchStorageResource.*",
               messagePattern = ".*Duplicate batch files for firstTick.*")
    void testFindLastBatchFile_MultipleFolders_DeduplicatesInLeaf() throws IOException {
        storage.writeChunkBatchStreaming(List.of(createChunk(0, 9, 10)).iterator());
        StoragePath completePath = storage.writeChunkBatchStreaming(
            List.of(createChunk(100_000_000, 100_000_009, 10)).iterator()).path();

        // A crash during a write leaves a second file with the same first tick
        File batchDir = new File(tempDir.toFile(), "test-sim/raw/001/000");
        File duplicateFile = new File(batchDir, "batch_0000000000100000000_0000000000100000019.pb");
        try (java.io.OutputStream out = Files.newOutputStream(duplicateFile.toPath())) {
            createChunk(100_000_000, 100_000_019, 20).writeDelimitedTo(out);
        }

        java.util.Optional<StoragePath> found = storage.findLastBatchFile("test-sim/raw/");

        assertTrue(found.isPresent(), "Should find the last batch file across folder levels");
        assertEquals(completePath.asString(), found.get().asString(),
            "Should keep the complete file with the smaller lastTick");
    }

    @Test
    void testFindBatchFileContaining_EmptyLeavesBeforeTarget_StepsBackToTheFile() throws IOException {
        // Folder levels of 1000 and 100 put every hundred ticks into their own leaf
        FileSystemStorageResource smallFolders = new FileSystemStorageResource("small-folders",
            ConfigFactory.parseString("folderStructure { levels = [1000, 100] }").withFallback(config));

        // One batch in leaf 000/000, reaching into the tick range of leaf 000/003
        StoragePath spanningPath = smallFolders.writeChunkBatchStreaming(
            List.of(createChunk(90, 350, 261)).iterator()).path();

        // A crash between creating a folder and writing its file leaves a leaf without one
        new File(tempDir.toFile(), "test-sim/raw/000/001").mkdirs();
        new File(tempDir.toFile(), "test-sim/raw/000/002").mkdirs();
        new File(tempDir.toFile(), "test-sim/raw/000/003").mkdirs();

        java.util.Optional<StoragePath> found = smallFolders.findBatchFileContaining("test-sim/raw/", 320);

        assertTrue(found.isPresent(), "Should step back over the empty leaves");
        assertEquals(spanningPath.asString(), found.get().asString());
    }

    @Test
    void testFindLastBatchFile_EmptyLeavesAfterLastBatch_StepsBackToTheFile() throws IOException {
        StoragePath batchPath = storage.writeChunkBatchStreaming(List.of(createChunk(0, 9, 10)).iterator()).path();

        new File(tempDir.toFile(), "test-sim/raw/000/001").mkdirs();
        new File(tempDir.toFile(), "test-sim/raw/000/002").mkdirs();

        java.util.Optional<StoragePath> found = storage.findLastBatchFile("test-sim/raw/");

        assertTrue(found.isPresent(), "Should step back over the empty leaves");
        assertEquals(batchPath.asString(), found.get().asString());
    }

    // ========================================================================
    // listBatchFiles Sort Order Tests
    // ========================================================================

    @Test
    void testListBatchFiles_Descending_SingleResult_ReturnsLastBatch() throws IOException {
        for (int i = 0; i < 10; i++) {
            storage.writeChunkBatchStreaming(List.of(createChunk(i * 10, i * 10 + 9, 10)).iterator());
        }

        BatchFileListResult result = storage.listBatchFiles("test-sim/", null, 1,
            IBatchStorageRead.SortOrder.DESCENDING);

        assertEquals(1, result.getFilenames().size(), "Should return one file");
        assertTrue(result.getFilenames().get(0).asString().contains("batch_0000000000000000090_"),
            "Should return the batch with the highest ticks, was: " + result.getFilenames().get(0).asString());
    }

    @Test
    void testListBatchFiles_Descending_ReturnsLastFilesNewestFirst() throws IOException {
        for (int i = 0; i < 10; i++) {
            storage.writeChunkBatchStreaming(List.of(createChunk(i * 10, i * 10 + 9, 10)).iterator());
        }

        BatchFileListResult result = storage.listBatchFiles("test-sim/", null, 3,
            IBatchStorageRead.SortOrder.DESCENDING);

        assertEquals(3, result.getFilenames().size(), "Should return three files");
        assertTrue(result.getFilenames().get(0).asString().contains("batch_0000000000000000090_"),
            "First should be the highest, was: " + result.getFilenames().get(0).asString());
        assertTrue(result.getFilenames().get(1).asString().contains("batch_0000000000000000080_"),
            "Second should follow descending, was: " + result.getFilenames().get(1).asString());
        assertTrue(result.getFilenames().get(2).asString().contains("batch_0000000000000000070_"),
            "Third should follow descending, was: " + result.getFilenames().get(2).asString());
    }

    @Test
    void testListBatchFiles_Descending_PagesNewestFirst() throws IOException {
        for (int i = 0; i < 10; i++) {
            storage.writeChunkBatchStreaming(List.of(createChunk(i * 10, i * 10 + 9, 10)).iterator());
        }

        BatchFileListResult firstPage = storage.listBatchFiles("test-sim/", null, 3,
            IBatchStorageRead.SortOrder.DESCENDING);
        assertTrue(firstPage.isTruncated(), "Seven files follow the first three");
        BatchFileListResult secondPage = storage.listBatchFiles("test-sim/", firstPage.getNextContinuationToken(), 3,
            IBatchStorageRead.SortOrder.DESCENDING);

        assertEquals(3, secondPage.getFilenames().size(), "The second page holds three files");
        assertTrue(secondPage.getFilenames().get(0).asString().contains("batch_0000000000000000060_"),
            "The second page continues behind the first, was: " + secondPage.getFilenames().get(0).asString());
        assertTrue(secondPage.getFilenames().get(2).asString().contains("batch_0000000000000000040_"),
            "The second page keeps the descending order, was: " + secondPage.getFilenames().get(2).asString());
        assertTrue(secondPage.isTruncated(), "Four files still follow");
    }

    // ========================================================================
    // Folder Structure Limit Tests
    // ========================================================================

    @Test
    void testConstructor_LevelRatioTooLarge_Throws() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> new FileSystemStorageResource("too-wide-storage",
                ConfigFactory.parseString("folderStructure { levels = [1000000, 100] }").withFallback(config)),
            "A level holding 10000 folders should be rejected");
        assertTrue(thrown.getMessage().contains("1000000"), "Message should name the configured levels");
    }

    @Test
    void testWriteChunkBatch_TickBeyondFolderLevels_Throws() {
        FileSystemStorageResource smallFolders = new FileSystemStorageResource("small-folders",
            ConfigFactory.parseString("folderStructure { levels = [1000, 100] }").withFallback(config));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> smallFolders.writeChunkBatchStreaming(
                List.of(createChunk(1_000_000, 1_000_009, 10)).iterator()),
            "A tick of 1000 times the outermost divisor has no folder name");
        assertTrue(thrown.getMessage().contains("1000000"), "Message should name the tick");
        assertTrue(thrown.getMessage().contains("configure a further level"),
            "Message should name the way out");
    }

    @Test
    void testFindLastBatchFile_FullLevelOfThousandFolders_IsAccepted() throws IOException {
        // The first 10^8 ticks fill the second level completely: folders 000 to 999
        for (int folder = 0; folder < 999; folder++) {
            assertTrue(new File(tempDir.toFile(), String.format("test-sim/raw/000/%03d", folder)).mkdirs(),
                "Should create the folder");
        }
        storage.writeChunkBatchStreaming(List.of(createChunk(99_900_000L, 99_900_009L, 10)).iterator());

        java.util.Optional<StoragePath> last = storage.findLastBatchFile("test-sim/raw/");

        assertTrue(last.isPresent(), "A level of exactly 1000 folders is the full width of a 3-digit name");
        assertTrue(last.get().asString().contains("000/999/"), "The last folder should hold the last batch");
    }

    @Test
    void testFindLastBatchFile_LevelHoldsTooManyFolders_Throws() throws IOException {
        for (int folder = 0; folder <= 1000; folder++) {
            assertTrue(new File(tempDir.toFile(), String.format("wide-run/raw/%04d", folder)).mkdirs(),
                "Should create the folder");
        }

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> storage.findLastBatchFile("wide-run/raw/"),
            "A level of 1001 folders should be rejected");
        assertTrue(thrown.getMessage().contains("wide-run/raw/"), "Message should name the folder");
    }
}
