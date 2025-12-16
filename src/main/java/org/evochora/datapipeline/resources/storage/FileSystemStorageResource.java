package org.evochora.datapipeline.resources.storage;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.storage.IAnalyticsStorageRead;
import org.evochora.datapipeline.api.resources.storage.IAnalyticsStorageWrite;
import org.evochora.datapipeline.utils.compression.ICompressionCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

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
        this.rootDirectory = new File(rootPath);
        if (!this.rootDirectory.isAbsolute()) {
            throw new IllegalArgumentException("rootDirectory must be an absolute path: " + rootPath);
        }
        
        // Validate parent directory exists (helps detect unmounted filesystems)
        // Skip validation for temp directories (used in tests)
        File parentDir = this.rootDirectory.getParentFile();
        boolean isTempDir = rootPath.startsWith("/tmp/") || rootPath.startsWith(System.getProperty("java.io.tmpdir"));
        if (parentDir != null && !parentDir.exists() && !isTempDir) {
            throw new IllegalArgumentException("Parent directory does not exist: " + parentDir.getAbsolutePath());
        }
        
        if (!this.rootDirectory.exists() && !this.rootDirectory.mkdirs()) {
            throw new IllegalArgumentException("Cannot create rootDirectory: " + rootPath);
        }
        
        if (!this.rootDirectory.canWrite()) {
            throw new IllegalArgumentException("rootDirectory is not writable: " + rootPath);
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
    protected long writeAtomicStreaming(String physicalPath, List<TickData> batch, 
                                         ICompressionCodec codec) throws IOException {
        validateKey(physicalPath);
        File finalFile = new File(rootDirectory, physicalPath);
        
        // Create parent directories if needed
        File parentDir = finalFile.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();
            if (!parentDir.isDirectory()) {
                throw new IOException("Failed to create parent directories for: " + finalFile.getAbsolutePath());
            }
        }
        
        // Generate unique temp file path
        File tempFile = new File(parentDir, finalFile.getName() + "." + UUID.randomUUID() + ".tmp");
        
        try {
            // Stream directly: Protobuf → Compression → BufferedOutputStream → File
            // No intermediate byte[] buffers - eliminates ~2-3 GB peak memory for large batches
            long bytesWritten;
            try (OutputStream fileOut = new BufferedOutputStream(
                     Files.newOutputStream(tempFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE));
                 CountingOutputStream countingOut = new CountingOutputStream(fileOut);
                 OutputStream compressedOut = codec.wrapOutputStream(countingOut)) {
                
                // Stream each tick directly through compression to disk
                for (TickData tick : batch) {
                    tick.writeDelimitedTo(compressedOut);
                }
                
                // Flush compression (required for ZSTD to finalize)
                compressedOut.flush();
                bytesWritten = countingOut.getBytesWritten();
            }
            
            // Atomic rename: temp → final
            Files.move(tempFile.toPath(), finalFile.toPath(), 
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            
            return bytesWritten;
            
        } catch (IOException e) {
            // Clean up temp file on failure
            try {
                if (tempFile.exists()) {
                    Files.delete(tempFile.toPath());
                }
            } catch (IOException cleanupEx) {
                log.warn("Failed to clean up temp file after write failure: {}", tempFile, cleanupEx);
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
            // Use iterator to catch NoSuchFileException during iteration
            java.util.Iterator<Path> iterator = stream.iterator();
            List<Path> validPaths = new ArrayList<>();
            
            while (iterator.hasNext()) {
                try {
                    Path path = iterator.next();
                    // Skip .tmp files and directories
                    String filename = path.getFileName().toString();
                    if (!filename.endsWith(".tmp") && Files.isRegularFile(path)) {
                        validPaths.add(path);
                    }
                } catch (java.io.UncheckedIOException e) {
                    // Unwrap UncheckedIOException to check underlying cause
                    // Files.walk() wraps NoSuchFileException in UncheckedIOException when files are deleted during traversal
                    if (e.getCause() instanceof java.nio.file.NoSuchFileException) {
                        log.debug("Ignoring file deleted during traversal");
                        continue;
                    }
                    throw e;
                }
            }
            
            List<String> allFiles = validPaths.stream()
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
    public OutputStream openAnalyticsOutputStream(String runId, String metricId, String lodLevel, String subPath, String filename) throws IOException {
        File file = getAnalyticsFile(runId, metricId, lodLevel, subPath, filename);
        
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

    @Override
    public List<String> listAnalyticsRunIds() throws IOException {
        if (!rootDirectory.exists() || !rootDirectory.isDirectory()) {
            return Collections.emptyList();
        }
        
        File[] runDirs = rootDirectory.listFiles(File::isDirectory);
        if (runDirs == null) {
            return Collections.emptyList();
        }
        
        // Filter to runs that have an analytics subdirectory with content
        // Sort by name (which includes timestamp) in reverse order (newest first)
        return Arrays.stream(runDirs)
            .filter(dir -> {
                File analyticsDir = new File(dir, "analytics");
                if (!analyticsDir.exists() || !analyticsDir.isDirectory()) {
                    return false;
                }
                // Check if analytics dir has any content
                String[] contents = analyticsDir.list();
                return contents != null && contents.length > 0;
            })
            .map(File::getName)
            .sorted(Comparator.reverseOrder()) // Newest first
            .collect(Collectors.toList());
    }

    // ========================================================================
    // Internal Helpers
    // ========================================================================

    private File getAnalyticsRoot(String runId) {
        validateKey(runId); // Ensure runId is safe (no ..)
        return new File(new File(rootDirectory, runId), "analytics");
    }

    private File getAnalyticsFile(String runId, String metricId, String lodLevel, String subPath, String filename) {
        validateKey(runId);
        validateKey(metricId);
        if (lodLevel != null) validateKey(lodLevel);
        validateKey(filename);
        
        File root = getAnalyticsRoot(runId);
        File metricDir = new File(root, metricId);
        File targetDir = (lodLevel != null) ? new File(metricDir, lodLevel) : metricDir;
        
        // Add hierarchical subPath if provided (e.g., "000/001")
        if (subPath != null && !subPath.isEmpty()) {
            // Validate subPath segments (prevent path traversal)
            for (String segment : subPath.split("/")) {
                validateKey(segment);
            }
            targetDir = new File(targetDir, subPath);
        }
        
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