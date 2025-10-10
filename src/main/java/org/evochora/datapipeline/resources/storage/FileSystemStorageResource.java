package org.evochora.datapipeline.resources.storage;

import com.typesafe.config.Config;
import org.evochora.datapipeline.utils.PathExpansion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileSystemStorageResource extends AbstractBatchStorageResource {

    private static final Logger log = LoggerFactory.getLogger(FileSystemStorageResource.class);
    private final File rootDirectory;

    public FileSystemStorageResource(String name, Config options) {
        super(name, options);
        if (!options.hasPath("rootDirectory")) {
            throw new IllegalArgumentException("rootDirectory is required for FileSystemStorageResource");
        }
        String rootPath = options.getString("rootDirectory");
        String expandedPath = PathExpansion.expandPath(rootPath);
        this.rootDirectory = new File(expandedPath);
        if (!this.rootDirectory.isAbsolute()) {
            throw new IllegalArgumentException("rootDirectory must be an absolute path: " + expandedPath);
        }
        if (!this.rootDirectory.exists() && !this.rootDirectory.mkdirs()) {
            throw new IllegalArgumentException("Failed to create rootDirectory: " + expandedPath);
        }
    }

    @Override
    protected void writeBytes(String path, byte[] data) throws IOException {
        validateKey(path);
        File file = new File(rootDirectory, path);
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs(); // Idempotent - safe to call even if directory exists
            if (!parentDir.isDirectory()) {
                throw new IOException("Failed to create parent directories for: " + file.getAbsolutePath());
            }
        }
        Files.write(file.toPath(), data);
        writeOperations.incrementAndGet();
        bytesWritten.addAndGet(data.length);
    }

    @Override
    protected byte[] readBytes(String path) throws IOException {
        validateKey(path);
        File file = new File(rootDirectory, path);
        if (!file.exists()) {
            throw new IOException("File does not exist: " + path);
        }
        byte[] data = Files.readAllBytes(file.toPath());
        readOperations.incrementAndGet();
        bytesRead.addAndGet(data.length);
        return data;
    }

    @Override
    protected void atomicMove(String src, String dest) throws IOException {
        validateKey(src);
        validateKey(dest);
        File srcFile = new File(rootDirectory, src);
        File destFile = new File(rootDirectory, dest);

        // Ensure parent directory exists
        File parentDir = destFile.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();  // Idempotent - safe to call even if directory exists
            if (!parentDir.isDirectory()) {
                throw new IOException("Failed to create parent directories for: " + destFile.getAbsolutePath());
            }
        }

        try {
            Files.move(srcFile.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Clean up source file on failure (filesystem-specific optimization)
            // Source file cleanup is cheap on filesystem, so we do it here
            try {
                if (srcFile.exists()) {
                    Files.delete(srcFile.toPath());
                }
            } catch (IOException cleanupEx) {
                log.warn("Failed to clean up temp file after move failure: {}", src, cleanupEx);
            }
            throw e;
        }
    }

    @Override
    protected List<String> listFilesWithPrefix(String prefix, String continuationToken, int maxResults) throws IOException {
        final String finalPrefix = (prefix == null) ? "" : prefix;

        // Determine starting path for the walk
        Path startPath = Paths.get(rootDirectory.getAbsolutePath(), finalPrefix);

        // If startPath doesn't exist yet (no files written), return empty list
        if (!Files.exists(startPath)) {
            return Collections.emptyList();
        }

        List<String> results = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(startPath)) {
            List<String> allFiles = stream
                    .filter(Files::isRegularFile)
                    .map(p -> Paths.get(rootDirectory.getAbsolutePath()).relativize(p))
                    .map(Path::toString)
                    .map(s -> s.replace(File.separatorChar, '/'))  // Normalize to forward slashes
                    .filter(path -> path.startsWith(finalPrefix))
                    .filter(path -> !path.contains("/.tmp"))  // Filter out .tmp files
                    .filter(path -> !path.endsWith(".tmp"))
                    .sorted()  // Lexicographic order
                    .toList();

            // Apply continuation token (skip files until we're past the token)
            boolean foundToken = (continuationToken == null);
            for (String file : allFiles) {
                if (!foundToken) {
                    if (file.compareTo(continuationToken) > 0) {
                        foundToken = true;
                    } else {
                        continue;  // Skip files up to and including the token
                    }
                }

                results.add(file);
                if (results.size() >= maxResults) {
                    break;
                }
            }

            return results;
        } catch (IOException e) {
            throw new IOException("Failed to list files with prefix: " + prefix, e);
        }
    }

    @Override
    public List<String> listRunIds(Instant afterTimestamp) {
        if (rootDirectory == null || !rootDirectory.isDirectory()) {
            return Collections.emptyList();
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSS");
        try (Stream<Path> stream = Files.list(rootDirectory.toPath())) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(runId -> {
                        if (runId.length() < 17) return false;  // Updated: 8 + 1 + 8 = 17 chars minimum
                        try {
                            String timestampStr = runId.substring(0, 17);  // Updated: include dash
                            LocalDateTime ldt = LocalDateTime.parse(timestampStr, formatter);
                            // Use system default timezone for conversion (same as SimulationEngine uses)
                            Instant runIdInstant = ldt.atZone(java.time.ZoneId.systemDefault()).toInstant();
                            return runIdInstant.isAfter(afterTimestamp);
                        } catch (DateTimeParseException e) {
                            log.trace("Ignoring non-runId directory: {}", runId);
                            return false;
                        }
                    })
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Could not list directories in storage root for run discovery. This can happen during concurrent test execution and is handled gracefully.", e);
            return Collections.emptyList();
        }
    }

    @Override
    protected void addCustomMetrics(java.util.Map<String, Number> metrics) {
        // Add filesystem-specific capacity metrics
        long totalSpace = rootDirectory.getTotalSpace();
        long usableSpace = rootDirectory.getUsableSpace();
        long usedSpace = totalSpace - usableSpace;

        metrics.put("disk_total_bytes", totalSpace);
        metrics.put("disk_available_bytes", usableSpace);
        metrics.put("disk_used_bytes", usedSpace);

        // Calculate percentage used (avoid division by zero)
        if (totalSpace > 0) {
            double usedPercent = (double) usedSpace / totalSpace * 100.0;
            metrics.put("disk_used_percent", usedPercent);
        } else {
            metrics.put("disk_used_percent", 0.0);
        }
    }

    private void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty");
        }

        // Prevent path traversal attacks
        if (key.contains("..")) {
            throw new IllegalArgumentException("Key cannot contain '..' (path traversal attempt): " + key);
        }

        // Prevent absolute paths
        if (key.startsWith("/") || key.startsWith("\\")) {
            throw new IllegalArgumentException("Key cannot be an absolute path: " + key);
        }

        // Check for Windows drive letter (C:, D:, etc.)
        if (key.length() >= 2 && key.charAt(1) == ':') {
            throw new IllegalArgumentException("Key cannot contain Windows drive letter: " + key);
        }

        // Prevent Windows-invalid characters
        String invalidChars = "<>\"?*|";
        for (char c : invalidChars.toCharArray()) {
            if (key.indexOf(c) >= 0) {
                throw new IllegalArgumentException("Key contains invalid character '" + c + "': " + key);
            }
        }

        // Prevent control characters (0x00-0x1F)
        for (char c : key.toCharArray()) {
            if (c < 0x20) {
                throw new IllegalArgumentException("Key contains control character (0x" +
                        Integer.toHexString(c) + "): " + key);
            }
        }
    }
}