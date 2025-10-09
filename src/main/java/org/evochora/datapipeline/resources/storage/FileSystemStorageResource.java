package org.evochora.datapipeline.resources.storage;

import com.typesafe.config.Config;
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
        String expandedPath = expandPath(rootPath);
        this.rootDirectory = new File(expandedPath);
        if (!this.rootDirectory.isAbsolute()) {
            throw new IllegalArgumentException("rootDirectory must be an absolute path: " + expandedPath);
        }
        if (!this.rootDirectory.exists() && !this.rootDirectory.mkdirs()) {
            throw new IllegalArgumentException("Failed to create rootDirectory: " + expandedPath);
        }
    }

    private static String expandPath(String path) {
        if (path == null || !path.contains("${")) return path;
        StringBuilder result = new StringBuilder();
        int pos = 0;
        while (pos < path.length()) {
            int startVar = path.indexOf("${", pos);
            if (startVar == -1) {
                result.append(path.substring(pos));
                break;
            }
            result.append(path.substring(pos, startVar));
            int endVar = path.indexOf("}", startVar + 2);
            if (endVar == -1) throw new IllegalArgumentException("Unclosed variable in path: " + path);
            String varName = path.substring(startVar + 2, endVar);
            String value = System.getProperty(varName, System.getenv(varName));
            if (value == null) throw new IllegalArgumentException("Undefined variable '${" + varName + "}' in path: " + path);
            result.append(value);
            pos = endVar + 1;
        }
        return result.toString();
    }

    @Override
    protected void writeBytes(String path, byte[] data) throws IOException {
        validateKey(path);
        File file = new File(rootDirectory, path);
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();
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
        if (destFile.getParentFile() != null) {
            destFile.getParentFile().mkdirs();
        }
        Files.move(srcFile.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    protected List<String> listFilesWithPrefix(String prefix, String continuationToken, int maxResults) throws IOException {
        final String finalPrefix = (prefix == null) ? "" : prefix;
        Path startPath = Paths.get(rootDirectory.getAbsolutePath(), finalPrefix);
        if (!Files.exists(startPath)) {
            return Collections.emptyList();
        }
        List<String> results = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(startPath)) {
            List<String> allFiles = stream.filter(Files::isRegularFile)
                    .map(p -> Paths.get(rootDirectory.getAbsolutePath()).relativize(p))
                    .map(Path::toString).map(s -> s.replace(File.separatorChar, '/'))
                    .filter(path -> path.startsWith(finalPrefix) && !path.contains("/.tmp") && !path.endsWith(".tmp"))
                    .sorted().collect(Collectors.toList());
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
        }
    }

    @Override
    public List<String> listRunIds(Instant afterTimestamp) {
        if (rootDirectory == null || !rootDirectory.isDirectory()) {
            return Collections.emptyList();
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSS");
        try (Stream<Path> stream = Files.list(rootDirectory.toPath())) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(runId -> {
                        if (runId.length() < 16) return false;
                        try {
                            String timestampStr = runId.substring(0, 16);
                            LocalDateTime ldt = LocalDateTime.parse(timestampStr, formatter);
                            return ldt.toInstant(ZoneOffset.UTC).isAfter(afterTimestamp);
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
        long totalSpace = rootDirectory.getTotalSpace();
        long usableSpace = rootDirectory.getUsableSpace();
        metrics.put("disk_total_bytes", totalSpace);
        metrics.put("disk_available_bytes", usableSpace);
        metrics.put("disk_used_bytes", totalSpace - usableSpace);
    }

    private void validateKey(String key) {
        if (key == null || key.isEmpty() || key.contains("..") || key.startsWith("/") || key.startsWith("\\")) {
            throw new IllegalArgumentException("Invalid key: " + key);
        }
    }
}