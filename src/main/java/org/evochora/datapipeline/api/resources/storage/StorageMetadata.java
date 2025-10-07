package org.evochora.datapipeline.api.resources.storage;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents storage metadata stored in .storage-metadata.json file.
 * <p>
 * This metadata is written once at simulation start and describes the folder organization,
 * compression settings, and batch constraints. Indexers read this file to discover how
 * the storage is organized without hardcoding configuration.
 * <p>
 * Example .storage-metadata.json:
 * <pre>
 * {
 *   "version": "1.0",
 *   "folderStructure": {
 *     "levels": [100000000, 100000]
 *   },
 *   "maxBatchSize": 10000,
 *   "compression": {
 *     "codec": "zstd",
 *     "level": 3
 *   }
 * }
 * </pre>
 */
public class StorageMetadata {

    /**
     * Format version for backward compatibility.
     * Current version: "1.0"
     */
    public final String version;

    /**
     * Folder structure configuration.
     */
    public final FolderStructure folderStructure;

    /**
     * Maximum batch size used by persistence services.
     * Indexers use this to calculate lookback distance when querying.
     */
    public final int maxBatchSize;

    /**
     * Compression configuration.
     */
    public final CompressionConfig compression;

    public StorageMetadata(String version, FolderStructure folderStructure,
                           int maxBatchSize, CompressionConfig compression) {
        if (version == null || version.isEmpty()) {
            throw new IllegalArgumentException("version cannot be null or empty");
        }
        if (folderStructure == null) {
            throw new IllegalArgumentException("folderStructure cannot be null");
        }
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }
        if (compression == null) {
            throw new IllegalArgumentException("compression cannot be null");
        }

        this.version = version;
        this.folderStructure = folderStructure;
        this.maxBatchSize = maxBatchSize;
        this.compression = compression;
    }

    /**
     * Describes hierarchical folder organization based on tick ranges.
     */
    public static class FolderStructure {
        /**
         * Tick range divisors for each folder level (outermost to innermost).
         * <p>
         * Example: [100000000, 100000] means:
         * <ul>
         *   <li>Level 0: tick / 100,000,000 (hundred-millions, 000-999)</li>
         *   <li>Level 1: (tick % 100,000,000) / 100,000 (hundred-thousands, 000-999)</li>
         * </ul>
         * <p>
         * Tick 123,456,789 → folder "001/234/"
         */
        public final List<Long> levels;

        public FolderStructure(List<Long> levels) {
            if (levels == null || levels.isEmpty()) {
                throw new IllegalArgumentException("levels cannot be null or empty");
            }
            for (Long level : levels) {
                if (level == null || level <= 0) {
                    throw new IllegalArgumentException("All levels must be positive");
                }
            }
            this.levels = Collections.unmodifiableList(levels);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FolderStructure that = (FolderStructure) o;
            return levels.equals(that.levels);
        }

        @Override
        public int hashCode() {
            return Objects.hash(levels);
        }

        @Override
        public String toString() {
            return "FolderStructure{levels=" + levels + "}";
        }
    }

    /**
     * Describes compression configuration.
     */
    public static class CompressionConfig {
        /**
         * Compression codec: "zstd", "gzip", or "none"
         */
        public final String codec;

        /**
         * Compression level.
         * For zstd: 1-22 (3 is default, balanced)
         * For gzip: 1-9
         * For none: ignored
         */
        public final int level;

        public CompressionConfig(String codec, int level) {
            if (codec == null || codec.isEmpty()) {
                throw new IllegalArgumentException("codec cannot be null or empty");
            }
            this.codec = codec;
            this.level = level;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CompressionConfig that = (CompressionConfig) o;
            return level == that.level && codec.equals(that.codec);
        }

        @Override
        public int hashCode() {
            return Objects.hash(codec, level);
        }

        @Override
        public String toString() {
            return String.format("CompressionConfig{codec='%s', level=%d}", codec, level);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StorageMetadata that = (StorageMetadata) o;
        return maxBatchSize == that.maxBatchSize &&
               version.equals(that.version) &&
               folderStructure.equals(that.folderStructure) &&
               compression.equals(that.compression);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, folderStructure, maxBatchSize, compression);
    }

    @Override
    public String toString() {
        return String.format(
            "StorageMetadata{version='%s', folderStructure=%s, maxBatchSize=%d, compression=%s}",
            version, folderStructure, maxBatchSize, compression
        );
    }
}
