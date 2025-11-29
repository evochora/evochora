package org.evochora.datapipeline.resources.storage;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.storage.IAnalyticsStorageRead;
import org.evochora.datapipeline.api.resources.storage.IAnalyticsStorageWrite;
import org.evochora.datapipeline.utils.PathExpansion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
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

public class FileSystemStorageResource extends AbstractBatchStorageResource
        implements IAnalyticsStorageWrite, IAnalyticsStorageRead {

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
    protected void putRaw(String physicalPath, byte[] data) throws IOException {
        validateKey(physicalPath);
        File file = new File(rootDirectory, physicalPath);
        
        // Atomic write: temp file → atomic move
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();
            if (!parentDir.isDirectory()) {
                throw new IOException("Failed to create parent directories for: " + file.getAbsolutePath());
            }
        }
        
        // Use suffix .UUID.tmp instead of prefix to ensure temp files are filtered correctly
        File tempFile = new File(parentDir, file.getName() + "." + java.util.UUID.randomUUID() + ".tmp");
        Files.write(tempFile.toPath(), data);
        
        try {
            Files.move(tempFile.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Clean up temp file on failure
            try {
                if (tempFile.exists()) {
                    Files.delete(tempFile.toPath());
                }
            } catch (IOException cleanupEx) {
                log.warn("Failed to clean up temp file after move failure: {}", tempFile, cleanupEx);
            }
            throw e;
        }
    }

    @Override
    protected byte[] getRaw(String physicalPath) throws IOException {
        validateKey(physicalPath);
        File file = new File(rootDirectory, physicalPath);
        if (!file.exists()) {
            throw new IOException("File does not exist: " + physicalPath);
        }
        return Files.readAllBytes(file.toPath());
    }

    @Override
    protected List<String> listRaw(String prefix, boolean listDirectories, String continuationToken, int maxResults,
                                    Long startTick, Long endTick) throws IOException {
        final String finalPrefix = (prefix == null) ? "" : prefix;
        Path rootPath = rootDirectory.toPath();

        if (listDirectories) {
            // List immediate subdirectories only (non-recursive)
            // startTick/endTick are ignored for directory listings
            File searchDir = new File(rootDirectory, finalPrefix);
            if (!searchDir.exists() || !searchDir.isDirectory()) {
                return Collections.emptyList();
            }
            
            File[] dirs = searchDir.listFiles(File::isDirectory);
            if (dirs == null) {
                return Collections.emptyList();
            }
            
            return java.util.Arrays.stream(dirs)
                .map(d -> rootPath.relativize(d.toPath()).toString())
                .map(s -> s.replace(File.separatorChar, '/'))
                .map(s -> s.endsWith("/") ? s : s + "/")  // Ensure trailing slash
                .sorted()
                .limit(maxResults)
                .collect(java.util.stream.Collectors.toList());
        }

        // List files recursively
        File prefixFile = new File(rootDirectory, finalPrefix);
        File searchDir;
        String filePattern;
        
        if (finalPrefix.isEmpty() || finalPrefix.endsWith("/")) {
            searchDir = prefixFile;
            filePattern = null;
        } else {
            searchDir = prefixFile.getParentFile();
            filePattern = prefixFile.getName();
        }
        
        if (searchDir == null || !searchDir.exists()) {
            return Collections.emptyList();
        }

        List<String> results = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(searchDir.toPath())) {
            List<String> allFiles = stream
                    // Filter .tmp files BEFORE checking isRegularFile to avoid race conditions
                    .filter(p -> {
                        String filename = p.getFileName().toString();
                        // Filter out temporary files (.UUID.tmp suffix)
                        return !filename.endsWith(".tmp");
                    })
                    .filter(Files::isRegularFile)
                    .map(p -> rootPath.relativize(p))
                    .map(Path::toString)
                    .map(s -> s.replace(File.separatorChar, '/'))
                    .filter(path -> path.startsWith(finalPrefix))
                    .filter(path -> {
                        if (filePattern != null) {
                            String filename = path.substring(path.lastIndexOf('/') + 1);
                            return filename.equals(filePattern) || filename.startsWith(filePattern + ".");
                        }
                        return true;
                    })
                    .filter(path -> {
                        // Apply tick filtering if specified
                        if (startTick == null && endTick == null) {
                            return true;  // No filtering
                        }
                        
                        // Check if this is a batch file
                        String filename = path.substring(path.lastIndexOf('/') + 1);
                        if (!filename.startsWith("batch_")) {
                            return true;  // Not a batch file, include it
                        }
                        
                        // Parse the start tick from the batch filename
                        long batchStartTick = parseBatchStartTick(filename);
                        if (batchStartTick < 0) {
                            return true;  // Failed to parse, include it anyway
                        }
                        
                        // Apply tick filters
                        if (startTick != null && batchStartTick < startTick) {
                            return false;  // Below start tick threshold
                        }
                        if (endTick != null && batchStartTick > endTick) {
                            return false;  // Above end tick threshold
                        }
                        
                        return true;
                    })
                    .sorted()
                    .toList();

            // Apply continuation token
            boolean foundToken = (continuationToken == null);
            for (String file : allFiles) {
                if (!foundToken) {
                    if (file.compareTo(continuationToken) > 0) {
                        foundToken = true;
                    } else {
                        continue;
                    }
                }

                results.add(file);
                if (results.size() >= maxResults) {
                    break;
                }
            }

            return results;
        } catch (IOException e) {
            throw new IOException("Failed to list with prefix: " + prefix, e);
        }
    }


    @Override
    protected void addCustomMetrics(java.util.Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Include parent metrics from AbstractBatchStorageResource
        
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

    // ========================================================================
    // IAnalyticsStorageWrite Implementation
    // ========================================================================

    @Override
    public OutputStream openAnalyticsOutputStream(String runId, String metricId, String lodLevel, String filename) throws IOException {
        File file = getAnalyticsFile(runId, metricId, lodLevel, filename);
        
        // Ensure parent directories exist
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs() && !parentDir.isDirectory()) {
                throw new IOException("Failed to create directories for: " + file.getAbsolutePath());
            }
        }
        
        return Files.newOutputStream(file.toPath());
    }

    // ========================================================================
    // IAnalyticsStorageRead Implementation
    // ========================================================================

    @Override
    public InputStream openAnalyticsInputStream(String runId, String path) throws IOException {
        // Path is relative to analytics root (e.g. "population/raw/batch_001.parquet")
        File file = new File(getAnalyticsRoot(runId), path);
        validatePath(file, runId); // Security check
        
        if (!file.exists()) {
            throw new IOException("Analytics file not found: " + file.getAbsolutePath());
        }
        return Files.newInputStream(file.toPath());
    }

    @Override
    public List<String> listAnalyticsFiles(String runId, String prefix) throws IOException {
        // Analytics root for this run
        File analyticsRoot = getAnalyticsRoot(runId);
        if (!analyticsRoot.exists() || !analyticsRoot.isDirectory()) {
            return Collections.emptyList();
        }

        // Prefix is relative to analytics/{runId}/
        String searchPrefix = (prefix == null) ? "" : prefix;
        
        // Simple recursive walk, filtering by prefix
        Path rootPath = analyticsRoot.toPath();
        try (Stream<Path> stream = Files.walk(rootPath)) {
            return stream
                .filter(Files::isRegularFile)
                .map(p -> rootPath.relativize(p))
                .map(Path::toString)
                .map(s -> s.replace(File.separatorChar, '/'))
                .filter(path -> path.startsWith(searchPrefix))
                .filter(path -> !path.endsWith(".tmp")) // Exclude temp files
                .sorted()
                .collect(Collectors.toList());
        }
    }

    // ========================================================================
    // Internal Helpers
    // ========================================================================

    private File getAnalyticsRoot(String runId) {
        validateKey(runId); // Ensure runId is safe (no ..)
        return new File(new File(rootDirectory, runId), "analytics");
    }

    private File getAnalyticsFile(String runId, String metricId, String lodLevel, String filename) {
        validateKey(runId);
        validateKey(metricId);
        if (lodLevel != null) validateKey(lodLevel);
        validateKey(filename);
        
        File root = getAnalyticsRoot(runId);
        File metricDir = new File(root, metricId);
        File targetDir = (lodLevel != null) ? new File(metricDir, lodLevel) : metricDir;
        
        return new File(targetDir, filename);
    }
    
    private void validatePath(File file, String runId) throws IOException {
        File root = getAnalyticsRoot(runId).getCanonicalFile();
        File target = file.getCanonicalFile();
        if (!target.toPath().startsWith(root.toPath())) {
            throw new IOException("Path traversal attempt: " + file.getPath());
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