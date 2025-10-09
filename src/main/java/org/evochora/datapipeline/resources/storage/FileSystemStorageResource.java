package org.evochora.datapipeline.resources.storage;

import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public class FileSystemStorageResource extends AbstractBatchStorageResource {

    private static final Logger log = LoggerFactory.getLogger(FileSystemStorageResource.class);
    private final File rootDirectory;

    // All metrics tracking inherited from AbstractBatchStorageResource

    public FileSystemStorageResource(String name, Config options) {
        super(name, options);
        if (!options.hasPath("rootDirectory")) {
            throw new IllegalArgumentException("rootDirectory is required for FileSystemStorageResource");
        }
        String rootPath = options.getString("rootDirectory");

        // Expand environment variables and system properties
        String expandedPath = expandPath(rootPath);
        if (!rootPath.equals(expandedPath)) {
            log.debug("Expanded rootDirectory: '{}' -> '{}'", rootPath, expandedPath);
        }

        this.rootDirectory = new File(expandedPath);
        if (!this.rootDirectory.isAbsolute()) {
            throw new IllegalArgumentException("rootDirectory must be an absolute path (after variable expansion): " + expandedPath);
        }
        if (!this.rootDirectory.exists()) {
            if (!this.rootDirectory.mkdirs()) {
                throw new IllegalArgumentException("Failed to create rootDirectory: " + expandedPath);
            }
        }
        if (!this.rootDirectory.isDirectory()) {
            throw new IllegalArgumentException("rootDirectory is not a directory: " + expandedPath);
        }
    }

    /**
     * Expands environment variables and Java system properties in a path string.
     * Supports syntax: ${VAR} for both environment variables and system properties.
     * System properties are checked first, then environment variables.
     *
     * @param path the path potentially containing variables like ${HOME} or ${user.home}
     * @return the path with all variables expanded
     * @throws IllegalArgumentException if a variable is referenced but not defined
     */
    private static String expandPath(String path) {
        if (path == null || !path.contains("${")) {
            return path;
        }

        StringBuilder result = new StringBuilder();
        int pos = 0;

        while (pos < path.length()) {
            int startVar = path.indexOf("${", pos);
            if (startVar == -1) {
                // No more variables, append rest of string
                result.append(path.substring(pos));
                break;
            }

            // Append text before variable
            result.append(path.substring(pos, startVar));

            int endVar = path.indexOf("}", startVar + 2);
            if (endVar == -1) {
                throw new IllegalArgumentException("Unclosed variable in path: " + path);
            }

            String varName = path.substring(startVar + 2, endVar);
            String value = resolveVariable(varName);

            if (value == null) {
                throw new IllegalArgumentException(
                    "Undefined variable '${" + varName + "}' in path: " + path +
                    ". Check that environment variable or system property exists."
                );
            }

            result.append(value);
            pos = endVar + 1;
        }

        return result.toString();
    }

    /**
     * Resolves a variable name to its value, checking system properties first, then environment variables.
     *
     * @param varName the variable name (without ${} delimiters)
     * @return the resolved value, or null if not found
     */
    private static String resolveVariable(String varName) {
        // Check system properties first (e.g., user.home, java.io.tmpdir)
        String value = System.getProperty(varName);
        if (value != null) {
            return value;
        }

        // Check environment variables (e.g., HOME, USERPROFILE)
        return System.getenv(varName);
    }

    // ===== Abstract method implementations for AbstractBatchStorageResource =====

    @Override
    protected void writeBytes(String path, byte[] data) throws IOException {
        validateKey(path);
        File file = new File(rootDirectory, path);
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();  // Idempotent - safe to call even if directory exists
            if (!parentDir.isDirectory()) {
                throw new IOException("Failed to create parent directories for: " + file.getAbsolutePath());
            }
        }
        Files.write(file.toPath(), data);

        // Track metrics
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

        // Track metrics
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

    // All monitoring methods (getUsageState, getMetrics, getErrors, clearErrors, isHealthy)
    // and getWrappedResource inherited from AbstractBatchStorageResource

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