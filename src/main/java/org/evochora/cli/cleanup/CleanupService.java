package org.evochora.cli.cleanup;

import java.io.PrintWriter;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.typesafe.config.Config;

/**
 * Orchestrates cleanup of simulation runs across storage, database, and topics.
 * <p>
 * Uses glob patterns to determine which runs to keep or delete.
 */
public class CleanupService {

    private final StorageCleaner storageCleaner;
    private final DatabaseCleaner databaseCleaner;
    private final TopicCleaner topicCleaner;

    /**
     * Statistics for a single cleanup area.
     *
     * @param kept number of items kept
     * @param deleted number of items deleted/to delete
     */
    public record AreaStats(int kept, int deleted) {}

    /**
     * Result of a cleanup operation with per-area statistics.
     *
     * @param storage storage directory statistics (null if not processed)
     * @param database database schema statistics (null if not processed)
     * @param topics topic queue statistics (null if not processed)
     */
    public record CleanupResult(
        AreaStats storage,
        AreaStats database,
        AreaStats topics
    ) {}

    /**
     * Creates a new CleanupService with the given configuration.
     *
     * @param config the application configuration
     */
    public CleanupService(Config config) {
        this.storageCleaner = new StorageCleaner(config);
        this.databaseCleaner = new DatabaseCleaner(config);
        this.topicCleaner = new TopicCleaner(config);
    }

    /**
     * Performs the cleanup operation.
     *
     * @param pattern glob pattern for matching run IDs
     * @param keepMode if true, keep matching runs; if false, delete matching runs
     * @param doStorage whether to clean storage
     * @param doDatabase whether to clean database
     * @param doTopics whether to clean topics
     * @param force if true, execute deletion; if false, dry-run only
     * @param out output writer for progress information
     * @return cleanup result with statistics
     * @throws Exception if cleanup fails
     */
    public CleanupResult cleanup(
            String pattern,
            boolean keepMode,
            boolean doStorage,
            boolean doDatabase,
            boolean doTopics,
            boolean force,
            PrintWriter out) throws Exception {

        // Create glob matcher
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        // Collect all known run IDs from all sources
        Set<String> allRunIds = new HashSet<>();

        if (doStorage) {
            allRunIds.addAll(storageCleaner.listRunIds());
        }
        if (doDatabase) {
            allRunIds.addAll(databaseCleaner.listRunIds());
        }
        if (doTopics) {
            allRunIds.addAll(topicCleaner.listRunIds());
        }

        // Partition runs into keep/delete based on pattern and mode
        List<String> toKeep = new ArrayList<>();
        List<String> toDelete = new ArrayList<>();

        for (String runId : allRunIds) {
            boolean matches = matcher.matches(java.nio.file.Path.of(runId));

            if (keepMode) {
                // Keep mode: matching runs are kept, non-matching are deleted
                if (matches) {
                    toKeep.add(runId);
                } else {
                    toDelete.add(runId);
                }
            } else {
                // Delete mode: matching runs are deleted, non-matching are kept
                if (matches) {
                    toDelete.add(runId);
                } else {
                    toKeep.add(runId);
                }
            }
        }

        // Sort for consistent output
        toKeep.sort(String::compareTo);
        toDelete.sort(String::compareTo);

        AreaStats storageStats = null;
        AreaStats databaseStats = null;
        AreaStats topicsStats = null;

        // Process storage
        if (doStorage) {
            storageStats = storageCleaner.cleanup(toKeep, toDelete, force, out);
        }

        // Process database
        if (doDatabase) {
            databaseStats = databaseCleaner.cleanup(toKeep, toDelete, force, out);
        }

        // Process topics
        if (doTopics) {
            topicsStats = topicCleaner.cleanup(toKeep, toDelete, force, out);
        }

        return new CleanupResult(storageStats, databaseStats, topicsStats);
    }
}
