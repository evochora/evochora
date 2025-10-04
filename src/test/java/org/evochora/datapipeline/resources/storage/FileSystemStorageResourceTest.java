package org.evochora.datapipeline.resources.storage;

import com.google.protobuf.InvalidProtocolBufferException;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.storage.MessageReader;
import org.evochora.datapipeline.api.resources.storage.MessageWriter;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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

    @BeforeEach
    void setUp() {
        Map<String, String> configMap = Map.of("rootDirectory", tempDir.toAbsolutePath().toString());
        config = ConfigFactory.parseMap(configMap);
        storage = new FileSystemStorageResource("test-storage", config);
    }

    private TickData createTick(long tickNumber) {
        return TickData.newBuilder()
                .setTickNumber(tickNumber)
                .setSimulationRunId("test-sim")
                .setCaptureTimeMs(System.currentTimeMillis())
                .build();
    }

    @Test
    void testOpenWriter_AtomicCommit() throws IOException {
        String key = "atomic_commit_test.pb";
        File finalFile = new File(tempDir.toFile(), key);
        File tempFile = new File(tempDir.toFile(), key + ".tmp");

        assertFalse(finalFile.exists(), "Final file should not exist before open");
        assertFalse(tempFile.exists(), "Temp file should not exist before open");

        try (MessageWriter writer = storage.openWriter(key)) {
            assertTrue(tempFile.exists(), "Temp file should exist after open");
            assertFalse(finalFile.exists(), "Final file should not exist before close");
            writer.writeMessage(createTick(1));
        }

        assertTrue(finalFile.exists(), "Final file should exist after close");
        assertFalse(tempFile.exists(), "Temp file should not exist after close");
    }

    @Test
    void testOpenWriter_NoCommitOnException() {
        String key = "no_commit_on_exception.pb";
        File finalFile = new File(tempDir.toFile(), key);
        File tempFile = new File(tempDir.toFile(), key + ".tmp");

        try {
            MessageWriter writer = storage.openWriter(key);
            writer.writeMessage(createTick(1));
            // Simulate a crash by not calling close() and throwing an exception
            throw new RuntimeException("Simulating crash");
        } catch (Exception e) {
            // expected
        }

        // After the "crash", the final file should not exist, but the temp file should.
        assertFalse(finalFile.exists(), "Final file should not exist after crash");
        assertTrue(tempFile.exists(), "Temp file should still exist after crash");
    }


    @Test
    void testReadMessage_Success() throws IOException {
        String key = "single_message.pb";
        TickData originalTick = createTick(42);

        try (MessageWriter writer = storage.openWriter(key)) {
            // Note: readMessage expects a single message, not delimited.
            // We write it directly for this test.
        }
        // Manually write a non-delimited message
        java.nio.file.Files.write(tempDir.resolve(key), originalTick.toByteArray());


        TickData readTick = storage.readMessage(key, TickData.parser());
        assertEquals(originalTick, readTick);
    }

    @Test
    void testReadMessage_NotFound() {
        assertThrows(IOException.class, () -> storage.readMessage("not_found.pb", TickData.parser()));
    }

    @Test
    void testOpenReader_Success() throws IOException {
        String key = "multi_message.pb";
        List<TickData> originalTicks = List.of(createTick(1), createTick(2), createTick(3));

        try (MessageWriter writer = storage.openWriter(key)) {
            for (TickData tick : originalTicks) {
                writer.writeMessage(tick);
            }
        }

        List<TickData> readTicks = new ArrayList<>();
        try (MessageReader<TickData> reader = storage.openReader(key, TickData.parser())) {
            while (reader.hasNext()) {
                readTicks.add(reader.next());
            }
        }

        assertEquals(originalTicks, readTicks);
    }

    @Test
    void testListKeys_Success() throws IOException {
        storage.openWriter("prefix/a.pb").close();
        storage.openWriter("prefix/b.pb").close();
        storage.openWriter("other/c.pb").close();

        List<String> keys = storage.listKeys("prefix/");
        Collections.sort(keys);

        List<String> expected = List.of("prefix/a.pb", "prefix/b.pb");
        assertEquals(expected, keys.stream().sorted().toList());
    }

    @Test
    void testConcurrentRead() throws Exception {
        String key = "concurrent_read.pb";
        List<TickData> originalTicks = new ArrayList<>();
        for(int i=0; i<100; i++) {
            originalTicks.add(createTick(i));
        }

        try (MessageWriter writer = storage.openWriter(key)) {
            for (TickData tick : originalTicks) {
                writer.writeMessage(tick);
            }
        }

        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicBoolean failed = new AtomicBoolean(false);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    List<TickData> readTicks = new ArrayList<>();
                    try (MessageReader<TickData> reader = storage.openReader(key, TickData.parser())) {
                        reader.forEachRemaining(readTicks::add);
                    }
                    assertEquals(originalTicks, readTicks);
                } catch (IOException e) {
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
        storage.openWriter(key).close();
        assertTrue(storage.exists(key));
        File file = new File(tempDir.toFile(), key);
        assertTrue(file.exists());
        assertTrue(file.getParentFile().exists());
        assertEquals("c", file.getParentFile().getName());
    }
}