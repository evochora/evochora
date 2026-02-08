package org.evochora.datapipeline.resources.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Tests for tick-range filtering and parsing in FileSystemStorageResource.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class FileSystemStorageResourceTickRangeTest {

    @TempDir
    Path tempDir;

    private FileSystemStorageResource storage;

    @BeforeEach
    void setUp() {
        Config config = ConfigFactory.parseMap(Map.of("rootDirectory", tempDir.toAbsolutePath().toString()));
        storage = new FileSystemStorageResource("test-storage", config);
    }

    // ========================================================================
    // parseAnalyticsParquetTickRange
    // ========================================================================

    @Test
    void parseTickRange_validBatchFilename() {
        long[] range = FileSystemStorageResource.parseAnalyticsParquetTickRange(
                "batch_00000000000000000000_00000000000000000099.parquet");
        assertThat(range).isNotNull();
        assertThat(range[0]).isEqualTo(0L);
        assertThat(range[1]).isEqualTo(99L);
    }

    @Test
    void parseTickRange_largeTickValues() {
        long[] range = FileSystemStorageResource.parseAnalyticsParquetTickRange(
                "batch_00000000000001000000_00000000000001000999.parquet");
        assertThat(range).isNotNull();
        assertThat(range[0]).isEqualTo(1_000_000L);
        assertThat(range[1]).isEqualTo(1_000_999L);
    }

    @Test
    void parseTickRange_returnsNullForMetadataJson() {
        assertThat(FileSystemStorageResource.parseAnalyticsParquetTickRange("metadata.json")).isNull();
    }

    @Test
    void parseTickRange_returnsNullForNonBatchParquet() {
        assertThat(FileSystemStorageResource.parseAnalyticsParquetTickRange("merged.parquet")).isNull();
    }

    @Test
    void parseTickRange_returnsNullForProtobufFile() {
        assertThat(FileSystemStorageResource.parseAnalyticsParquetTickRange("batch_000_099.pb")).isNull();
    }

    @Test
    void parseTickRange_returnsNullForMalformedFilename() {
        assertThat(FileSystemStorageResource.parseAnalyticsParquetTickRange("batch_abc_def.parquet")).isNull();
    }

    // ========================================================================
    // listAnalyticsFiles with tick range filter
    // ========================================================================

    @Test
    void listWithTickRange_filtersOverlapping() throws IOException {
        createAnalyticsFiles("run1", "metric/lod0/",
                "batch_00000000000000000000_00000000000000000099.parquet",
                "batch_00000000000000000100_00000000000000000199.parquet",
                "batch_00000000000000000200_00000000000000000299.parquet",
                "metadata.json");

        List<String> result = storage.listAnalyticsFiles("run1", "metric/lod0/", 150L, 250L);

        // batch 0-99: excluded (ends before 150)
        // batch 100-199: included (overlaps: 150-199)
        // batch 200-299: included (overlaps: 200-250)
        // metadata.json: always included
        assertThat(result).hasSize(3);
        assertThat(result).anyMatch(f -> f.contains("batch_00000000000000000100"));
        assertThat(result).anyMatch(f -> f.contains("batch_00000000000000000200"));
        assertThat(result).anyMatch(f -> f.contains("metadata.json"));
        assertThat(result).noneMatch(f -> f.contains("batch_00000000000000000000"));
    }

    @Test
    void listWithTickRange_onlyTickFromSet() throws IOException {
        createAnalyticsFiles("run1", "metric/lod0/",
                "batch_00000000000000000000_00000000000000000099.parquet",
                "batch_00000000000000000100_00000000000000000199.parquet");

        List<String> result = storage.listAnalyticsFiles("run1", "metric/lod0/", 100L, null);

        // batch 0-99: excluded (ends before 100)
        // batch 100-199: included
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).contains("batch_00000000000000000100");
    }

    @Test
    void listWithTickRange_onlyTickToSet() throws IOException {
        createAnalyticsFiles("run1", "metric/lod0/",
                "batch_00000000000000000000_00000000000000000099.parquet",
                "batch_00000000000000000100_00000000000000000199.parquet");

        List<String> result = storage.listAnalyticsFiles("run1", "metric/lod0/", null, 50L);

        // batch 0-99: included (overlaps: 0-50)
        // batch 100-199: excluded (starts after 50)
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).contains("batch_00000000000000000000");
    }

    @Test
    void listWithTickRange_nullBothDelegatesToUnfiltered() throws IOException {
        createAnalyticsFiles("run1", "metric/lod0/",
                "batch_00000000000000000000_00000000000000000099.parquet",
                "batch_00000000000000000100_00000000000000000199.parquet");

        List<String> result = storage.listAnalyticsFiles("run1", "metric/lod0/", null, null);

        assertThat(result).hasSize(2);
    }

    @Test
    void listWithTickRange_nonExistentRunReturnsEmpty() throws IOException {
        List<String> result = storage.listAnalyticsFiles("no-such-run", "metric/lod0/", 0L, 100L);
        assertThat(result).isEmpty();
    }

    // ========================================================================
    // getAnalyticsTickRange
    // ========================================================================

    @Test
    void getTickRange_returnsMinMaxAcrossFiles() throws IOException {
        createAnalyticsFiles("run1", "metric/lod0/",
                "batch_00000000000000000100_00000000000000000199.parquet",
                "batch_00000000000000000500_00000000000000000599.parquet",
                "batch_00000000000000000200_00000000000000000299.parquet");

        long[] range = storage.getAnalyticsTickRange("run1", "metric/lod0/");

        assertThat(range).isNotNull();
        assertThat(range[0]).isEqualTo(100L);
        assertThat(range[1]).isEqualTo(599L);
    }

    @Test
    void getTickRange_ignoresNonBatchFiles() throws IOException {
        createAnalyticsFiles("run1", "metric/lod0/",
                "metadata.json",
                "batch_00000000000000000000_00000000000000000099.parquet");

        long[] range = storage.getAnalyticsTickRange("run1", "metric/lod0/");

        assertThat(range).isNotNull();
        assertThat(range[0]).isEqualTo(0L);
        assertThat(range[1]).isEqualTo(99L);
    }

    @Test
    void getTickRange_returnsNullIfNoBatchFiles() throws IOException {
        createAnalyticsFiles("run1", "metric/lod0/", "metadata.json");

        long[] range = storage.getAnalyticsTickRange("run1", "metric/lod0/");

        assertThat(range).isNull();
    }

    @Test
    void getTickRange_returnsNullForNonExistentRun() throws IOException {
        long[] range = storage.getAnalyticsTickRange("no-such-run", "metric/lod0/");
        assertThat(range).isNull();
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private void createAnalyticsFiles(String runId, String prefix, String... filenames) throws IOException {
        Path analyticsRoot = tempDir.resolve(runId).resolve("analytics");
        for (String filename : filenames) {
            Path filePath = analyticsRoot.resolve(prefix).resolve(filename);
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, "test");
        }
    }
}
