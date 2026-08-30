package org.evochora.datapipeline.resources.storage;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import org.evochora.datapipeline.api.resources.storage.PublishedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class FileSystemStorageAnalyticsTest {

    @TempDir
    Path tempDir;

    private FileSystemStorageResource storage;
    private String runId = "sim-run-123";

    @BeforeEach
    void setUp() {
        Config config = ConfigFactory.parseMap(Map.of(
            "rootDirectory", tempDir.toAbsolutePath().toString()
        ));
        storage = new FileSystemStorageResource("test-storage", config);
    }

    @AfterEach
    void tearDown() {
        // No explicit cleanup needed, TempDir handles it
    }

    @Test
    void testWriteAndReadAnalyticsFile() throws IOException {
        String metricId = "population";
        String lod = "raw";
        String filename = "test-metric.csv";
        String content = "tick,count\n1,100\n2,105";

        // 1. Write (structured)
        try (PublishedOutputStream out = storage.openAnalyticsOutputStream(runId, metricId, lod, filename)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.publish();
        }

        // 2. Read (path relative to analytics root)
        String relativePath = metricId + "/" + lod + "/" + filename;
        try (InputStream in = storage.openAnalyticsInputStream(runId, relativePath)) {
            String readContent = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(content, readContent);
        }

        // 3. Verify physical location (white-box check for correct folder structure)
        File expectedFile = tempDir.resolve(runId)
                .resolve("analytics")
                .resolve(metricId)
                .resolve(lod)
                .resolve(filename)
                .toFile();
        assertTrue(expectedFile.exists(), "File should exist at " + expectedFile);
    }

    @Test
    void testListAnalyticsFiles() throws IOException {
        // Setup: Create multiple files
        storage.writeAnalyticsBlob(runId, "metric1", "raw", "data.csv", "data".getBytes());
        storage.writeAnalyticsBlob(runId, "metric1", "lod1", "data.csv", "data".getBytes());
        storage.writeAnalyticsBlob(runId, "metric2", null, "meta.json", "{}".getBytes()); // Null LOD

        // 1. List all
        List<String> allFiles = storage.listAnalyticsFiles(runId, "");
        assertEquals(3, allFiles.size());
        assertTrue(allFiles.contains("metric1/raw/data.csv"));
        assertTrue(allFiles.contains("metric1/lod1/data.csv"));
        assertTrue(allFiles.contains("metric2/meta.json"));

        // 2. List with prefix
        List<String> metric1Files = storage.listAnalyticsFiles(runId, "metric1/");
        assertEquals(2, metric1Files.size());
        assertTrue(metric1Files.contains("metric1/raw/data.csv"));

        List<String> rawFiles = storage.listAnalyticsFiles(runId, "metric1/raw/");
        assertEquals(1, rawFiles.size());
        assertTrue(rawFiles.contains("metric1/raw/data.csv"));
    }

    @Test
    void testWriteAtomicBlob() throws IOException {
        String metricId = "blob";
        String filename = "data.bin";
        byte[] data = {1, 2, 3, 4};

        storage.writeAnalyticsBlob(runId, metricId, "raw", filename, data);

        try (InputStream in = storage.openAnalyticsInputStream(runId, metricId + "/raw/" + filename)) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }

    @Test
    void testMissingFileThrowsIOException() {
        assertThrows(IOException.class, () -> {
            storage.openAnalyticsInputStream(runId, "non-existent/file.txt");
        });
    }
    
    @Test
    void testPathTraversalAttempt() {
        assertThrows(IllegalArgumentException.class, () -> {
            storage.openAnalyticsOutputStream(runId, "..", "raw", "hack.txt");
        });
        
        assertThrows(IOException.class, () -> {
            storage.openAnalyticsInputStream(runId, "../secret.txt");
        });
    }

    /**
     * A write that is abandoned without closing must leave nothing under the final name: half a
     * Parquet file passes a size check and then breaks every directory-wide read.
     */
    @Test
    void abandonedWriteLeavesNoFileUnderTheFinalName() throws IOException {
        String runId = "run-abandoned";
        PublishedOutputStream out = storage.openAnalyticsOutputStream(runId, "population", "lod0", "000", "batch.parquet");
        out.write("half a file".getBytes(StandardCharsets.UTF_8));
        out.flush();
        // deliberately not closed - as if the process died here

        List<String> listed = storage.listAnalyticsFiles(runId, "");
        assertTrue(listed.stream().noneMatch(f -> f.endsWith("batch.parquet")),
                "destination must not appear before the write completes: " + listed);
        assertTrue(listed.stream().noneMatch(f -> f.endsWith(".tmp")),
                "temp files must stay out of listings: " + listed);
    }

    /**
     * A write that failed part way through and was then closed properly must leave nothing under
     * the final name either. Closing is what a try-with-resources block does on the way out of an
     * exception, so it cannot be the signal that the content is complete.
     */
    @Test
    void aWriteThatFailedAndWasClosedLeavesNoFileUnderTheFinalName() throws IOException {
        String runId = "run-failed";

        try (PublishedOutputStream out = storage.openAnalyticsOutputStream(
                runId, "population", "lod0", "000", "batch.parquet")) {
            out.write("half a file".getBytes(StandardCharsets.UTF_8));
            // as if the transfer threw here: the block is left without publishing
        }

        List<String> listed = storage.listAnalyticsFiles(runId, "");
        assertTrue(listed.stream().noneMatch(f -> f.endsWith("batch.parquet")),
                "an unpublished write must not appear under the final name: " + listed);
        assertTrue(listed.isEmpty(), "and must leave nothing behind at all: " + listed);
    }

    @Test
    void closingPublishesTheFileUnderItsFinalName() throws IOException {
        String runId = "run-published";
        try (PublishedOutputStream out = storage.openAnalyticsOutputStream(runId, "population", "lod0", "000", "batch.parquet")) {
            out.write("complete".getBytes(StandardCharsets.UTF_8));
            out.publish();
        }

        List<String> listed = storage.listAnalyticsFiles(runId, "");
        assertEquals(1, listed.size(), "exactly the published file: " + listed);
        assertTrue(listed.get(0).endsWith("batch.parquet"), listed.get(0));

        try (InputStream in = storage.openAnalyticsInputStream(runId, listed.get(0))) {
            assertEquals("complete", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Rewriting a file must replace it, since a redelivered batch writes the same names again.
     */
    @Test
    void writingTheSameFileTwiceReplacesIt() throws IOException {
        String runId = "run-replaced";
        for (String content : List.of("first", "second")) {
            try (PublishedOutputStream out = storage.openAnalyticsOutputStream(runId, "population", "lod0", "000", "batch.parquet")) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.publish();
            }
        }

        List<String> listed = storage.listAnalyticsFiles(runId, "");
        assertEquals(1, listed.size(), "no leftovers: " + listed);
        try (InputStream in = storage.openAnalyticsInputStream(runId, listed.get(0))) {
            assertEquals("second", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
