package org.evochora.test.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utility class for file operations in tests, designed to be resilient to race conditions.
 */
public final class FileUtils {

    private FileUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * Safely finds all batch files (ending in .pb or .pb.zst) in a directory, ignoring temporary files
     * and handling race conditions where files might be deleted or renamed during the walk.
     *
     * @param storageDir The directory to search.
     * @return A list of paths to the batch files found.
     */
    public static List<Path> findBatchFiles(Path storageDir) {
        if (!Files.exists(storageDir)) {
            return Collections.emptyList();
        }
        try (Stream<Path> paths = Files.walk(storageDir)) {
            return paths
                    .filter(p -> {
                        String fileName = p.getFileName().toString();
                        return fileName.startsWith("batch_") && (fileName.endsWith(".pb") || fileName.endsWith(".pb.zst"));
                    })
                    .filter(Files::isRegularFile)
                    .collect(Collectors.toList());
        } catch (java.io.UncheckedIOException e) {
            if (e.getCause() instanceof java.nio.file.NoSuchFileException) {
                // This is a recoverable race condition. Return an empty list; await() will retry.
                return Collections.emptyList();
            }
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("Failed to find batch files", e);
        }
    }

    /**
     * Safely counts all batch files in a directory using the race-condition-resilient findBatchFiles method.
     *
     * @param storageDir The directory to search.
     * @return The number of batch files found.
     */
    public static int countBatchFiles(Path storageDir) {
        return findBatchFiles(storageDir).size();
    }
}
