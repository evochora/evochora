package org.evochora.datapipeline.resources.storage;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

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

import static org.junit.jupiter.api.Assertions.*;

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
        // Write 3 batch files for test-sim
        storage.writeBatch(List.of(createTick(1), createTick(2)), 1, 2);
        storage.writeBatch(List.of(createTick(10), createTick(20)), 10, 20);
        storage.writeBatch(List.of(createTick(100), createTick(200)), 100, 200);

        // List all batches for test-sim
        BatchFileListResult result = storage.listBatchFiles("test-sim/", null, 10);

        assertEquals(3, result.getFilenames().size(), "Should find 3 batch files");
        assertTrue(result.getFilenames().stream().allMatch(f -> f.asString().startsWith("test-sim/")));
        assertTrue(result.getFilenames().stream().allMatch(f -> f.asString().contains("batch_")));
        assertFalse(result.isTruncated());
    }

    @Test
    void testConcurrentRead() throws Exception {
        // Write a batch of 100 ticks
        List<TickData> batch = new ArrayList<>();
        for(int i=0; i<100; i++) {
            batch.add(createTick(i));
        }
        StoragePath batchPath = storage.writeBatch(batch, 0, 99);

        // Read the batch concurrently from 10 threads
        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicBoolean failed = new AtomicBoolean(false);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    List<TickData> readBatch = storage.readBatch(batchPath);
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
        
        String configPath = String.join(File.separator, "${java.io.tmpdir}", "evochora-test-sysprop");
        Map<String, String> configMap = Map.of("rootDirectory", configPath);
        Config config = ConfigFactory.parseMap(configMap);

        FileSystemStorageResource storage = new FileSystemStorageResource("test-storage", config);
        assertNotNull(storage);
    }

    @Test
    void testVariableExpansion_EnvironmentVariable() {
        // Set a custom environment-like variable via system properties for testing
        String testDirPath = System.getProperty("java.io.tmpdir") + File.separator + "evochora-test-env";
        System.setProperty("TEST_EVOCHORA_DIR", testDirPath);
        
        Path testDir = Path.of(testDirPath);
        createdDirectories.add(testDir);

        try {
            Map<String, String> configMap = Map.of("rootDirectory", "${TEST_EVOCHORA_DIR}");
            Config config = ConfigFactory.parseMap(configMap);

            FileSystemStorageResource storage = new FileSystemStorageResource("test-storage", config);
            assertNotNull(storage);
        } finally {
            System.clearProperty("TEST_EVOCHORA_DIR");
        }
    }

    @Test
    void testVariableExpansion_MultipleVariables() {
        String javaTmpDir = System.getProperty("java.io.tmpdir");
        System.setProperty("test.project", "evochora-multi-var-test");
        
        Path testDir = Path.of(javaTmpDir, "evochora-multi-var-test");
        createdDirectories.add(testDir);

        try {
            String configPath = String.join(File.separator, "${java.io.tmpdir}", "${test.project}", "data");
            Map<String, String> configMap = Map.of("rootDirectory", configPath);
            Config config = ConfigFactory.parseMap(configMap);

            FileSystemStorageResource storage = new FileSystemStorageResource("test-storage", config);
            assertNotNull(storage);
        } finally {
            System.clearProperty("test.project");
        }
    }

    @Test
    void testVariableExpansion_UndefinedVariable() {
        Map<String, String> configMap = Map.of("rootDirectory", "${THIS_VARIABLE_DOES_NOT_EXIST}/data");
        Config config = ConfigFactory.parseMap(configMap);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            new FileSystemStorageResource("test-storage", config);
        });
        assertTrue(exception.getMessage().contains("Undefined variable"));
        assertTrue(exception.getMessage().contains("THIS_VARIABLE_DOES_NOT_EXIST"));
    }

    @Test
    void testVariableExpansion_UnclosedVariable() {
        Map<String, String> configMap = Map.of("rootDirectory", "${user.home/data");
        Config config = ConfigFactory.parseMap(configMap);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            new FileSystemStorageResource("test-storage", config);
        });
        assertTrue(exception.getMessage().contains("Unclosed variable"));
    }

    @Test
    void testVariableExpansion_MustBeAbsoluteAfterExpansion() {
        System.setProperty("test.relative", "relative" + File.separator + "path");

        try {
            String configPath = "${test.relative}" + File.separator + "data";
            Map<String, String> configMap = Map.of("rootDirectory", configPath);
            Config config = ConfigFactory.parseMap(configMap);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
                new FileSystemStorageResource("test-storage", config);
            });
            assertTrue(exception.getMessage().contains("must be an absolute path"));
        } finally {
            System.clearProperty("test.relative");
        }
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
        
        String configPath = String.join(File.separator, "${java.io.tmpdir}", "evochora-test");
        Map<String, String> configMap = Map.of("rootDirectory", configPath);
        Config config = ConfigFactory.parseMap(configMap);

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
}