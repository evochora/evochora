package org.evochora.cli.cleanup;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * Cleans up storage directories for simulation runs.
 * <p>
 * Storage structure: {dataBaseDir}/storage/{runId}/
 */
public class StorageCleaner {

    private static final Logger log = LoggerFactory.getLogger(StorageCleaner.class);

    private final Path storageDir;

    /**
     * Creates a new StorageCleaner.
     *
     * @param config application configuration
     */
    public StorageCleaner(Config config) {
        String dataBaseDir = config.getString("pipeline.dataBaseDir");
        this.storageDir = Path.of(dataBaseDir, "storage");
    }

    /**
     * Lists all run IDs in storage.
     *
     * @return list of run IDs
     */
    public List<String> listRunIds() {
        List<String> runIds = new ArrayList<>();

        if (!Files.exists(storageDir)) {
            log.warn("Storage directory does not exist: {}", storageDir);
            return runIds;
        }

        try (Stream<Path> dirs = Files.list(storageDir)) {
            dirs.filter(Files::isDirectory)
                .map(p -> p.getFileName().toString())
                .forEach(runIds::add);
        } catch (IOException e) {
            log.error("Failed to list storage directories: {}", e.getMessage());
        }

        return runIds;
    }

    /**
     * Performs cleanup on storage directories.
     *
     * @param toKeep run IDs to keep
     * @param toDelete run IDs to delete
     * @param force if true, actually delete; if false, dry-run only
     * @param out output writer for progress
     * @return statistics with kept and deleted counts
     */
    public CleanupService.AreaStats cleanup(List<String> toKeep, List<String> toDelete, boolean force, PrintWriter out) {
        out.printf("Storage (%s):%n", storageDir);

        int keepCount = 0;
        int deleteCount = 0;

        // Show what will be kept
        for (String runId : toKeep) {
            Path dir = storageDir.resolve(runId);
            if (Files.exists(dir)) {
                out.printf("  %s KEEP   %s%n", "\u2713", runId);
                keepCount++;
            }
        }

        // Show/delete what will be removed
        for (String runId : toDelete) {
            Path dir = storageDir.resolve(runId);
            if (Files.exists(dir)) {
                if (force) {
                    try {
                        deleteDirectory(dir);
                        out.printf("  %s DELETE %s (deleted)%n", "\u2717", runId);
                        deleteCount++;
                    } catch (IOException e) {
                        out.printf("  %s DELETE %s (FAILED: %s)%n", "\u2717", runId, e.getMessage());
                        log.error("Failed to delete storage directory {}: {}", dir, e.getMessage());
                    }
                } else {
                    out.printf("  %s DELETE %s%n", "\u2717", runId);
                    deleteCount++;
                }
            }
        }

        out.println();
        return new CleanupService.AreaStats(keepCount, deleteCount);
    }

    /**
     * Recursively deletes a directory and all its contents.
     *
     * @param dir directory to delete
     * @throws IOException if deletion fails
     */
    private void deleteDirectory(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }
}
