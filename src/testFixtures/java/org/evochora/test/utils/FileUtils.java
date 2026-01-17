package org.evochora.test.utils;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /**
     * Safely reads all TickData messages from all batch files in a directory.
     * This method reads TickDataChunk messages and extracts the snapshot from each.
     * For tests that need full decompression, use {@link #readAllChunksFromBatches}.
     * <p>
     * This method is resilient to race conditions by retrying the read operation
     * if files disappear during processing.
     *
     * @param storageDir The directory to search for batch files.
     * @return A list of all TickData snapshots found (one per chunk).
     */
    public static List<TickData> readAllTicksFromBatches(Path storageDir) {
        List<TickDataChunk> chunks = readAllChunksFromBatches(storageDir);
        List<TickData> allTicks = new ArrayList<>();
        for (TickDataChunk chunk : chunks) {
            allTicks.add(chunk.getSnapshot());
        }
        return allTicks;
    }

    /**
     * Safely reads all TickDataChunk messages from all batch files in a directory.
     * This method is resilient to race conditions by retrying the read operation
     * if files disappear during processing.
     *
     * @param storageDir The directory to search for batch files.
     * @return A list of all TickDataChunk messages found.
     */
    public static List<TickDataChunk> readAllChunksFromBatches(Path storageDir) {
        int maxRetries = 5;
        long delay = 100; // ms

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                List<TickDataChunk> allChunks = new ArrayList<>();
                List<Path> batchFiles = findBatchFiles(storageDir);

                for (Path batchFile : batchFiles) {
                    try (java.io.InputStream is = new java.io.BufferedInputStream(Files.newInputStream(batchFile))) {
                        while (is.available() > 0) {
                            TickDataChunk chunk = TickDataChunk.parseDelimitedFrom(is);
                            if (chunk == null) break;
                            allChunks.add(chunk);
                        }
                    }
                }
                return allChunks;

            } catch (java.nio.file.NoSuchFileException e) {
                if (attempt < maxRetries - 1) {
                    try {
                        Thread.sleep(delay * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while retrying file read", ie);
                    }
                } else {
                    throw new RuntimeException("Failed to read persisted chunks consistently after " + maxRetries + " attempts", e);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to read persisted chunks", e);
            }
        }
        return Collections.emptyList(); // Should be unreachable
    }
}
