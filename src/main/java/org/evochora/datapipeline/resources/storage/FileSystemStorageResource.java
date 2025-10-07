package org.evochora.datapipeline.resources.storage;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IContextualResource;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.MessageReader;
import org.evochora.datapipeline.api.resources.storage.MessageWriter;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.utils.compression.CompressionCodecFactory;
import org.evochora.datapipeline.utils.compression.CompressionException;
import org.evochora.datapipeline.utils.compression.ICompressionCodec;
import org.evochora.datapipeline.resources.storage.wrappers.MonitoredBatchStorageReader;
import org.evochora.datapipeline.resources.storage.wrappers.MonitoredBatchStorageWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileSystemStorageResource extends AbstractBatchStorageResource
    implements IContextualResource, IMonitorable {

    private static final Logger log = LoggerFactory.getLogger(FileSystemStorageResource.class);
    private final File rootDirectory;
    private final ICompressionCodec codec;

    // Metrics tracking
    private final AtomicLong writeOperations = new AtomicLong(0);
    private final AtomicLong readOperations = new AtomicLong(0);
    private final AtomicLong bytesWritten = new AtomicLong(0);
    private final AtomicLong bytesRead = new AtomicLong(0);

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

        // Initialize compression codec (fail-fast if environment validation fails)
        try {
            this.codec = CompressionCodecFactory.createAndValidate(options);
            if (!"none".equals(codec.getName())) {
                log.info("FileSystemStorage '{}' using compression: codec={}, level={}",
                    name, codec.getName(), codec.getLevel());
            }
        } catch (CompressionException e) {
            throw new IllegalStateException("Failed to initialize compression codec for storage '" + name + "'", e);
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

    /**
     * Returns a contextual wrapper for this storage resource based on usage type.
     * Supports usage types: storage-write, storage-read.
     */
    @Override
    public IWrappedResource getWrappedResource(ResourceContext context) {
        if (context.usageType() == null) {
            throw new IllegalArgumentException(String.format(
                "Storage resource '%s' requires a usageType in the binding URI. " +
                "Expected format: 'usageType:%s' where usageType is one of: " +
                "storage-write, storage-read",
                getResourceName(), getResourceName()
            ));
        }

        return switch (context.usageType()) {
            case "storage-write" -> new MonitoredBatchStorageWriter(this, context);
            case "storage-read" -> new MonitoredBatchStorageReader(this, context);
            default -> throw new IllegalArgumentException(String.format(
                "Unsupported usage type '%s' for storage resource '%s'. " +
                "Supported types: storage-write, storage-read",
                context.usageType(), getResourceName()
            ));
        };
    }

    /**
     * Determines whether a file should be decompressed based on its extension.
     * Files with .zst extension are decompressed, others are read as-is (backward compatibility).
     */
    private boolean shouldDecompress(String key) {
        return key.endsWith(codec.getFileExtension()) && !codec.getFileExtension().isEmpty();
    }

    // ===== IBatchStorageWrite/IBatchStorageRead interface implementations (single-message operations) =====

    /**
     * Writes a single protobuf message to storage at the specified key.
     * Implements {@link org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite#writeMessage}.
     */
    @Override
    public <T extends MessageLite> void writeMessage(String key, T message) throws IOException {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }

        try (MessageWriter writer = openWriter(key)) {
            writer.writeMessage(message);
        }
    }

    /**
     * Reads a single protobuf message from storage at the specified key.
     * Implements {@link org.evochora.datapipeline.api.resources.storage.IBatchStorageRead#readMessage}.
     */
    @Override
    public <T extends MessageLite> T readMessage(String key, Parser<T> parser) throws IOException {
        try (MessageReader<T> reader = openReader(key, parser)) {
            if (!reader.hasNext()) {
                throw new IOException("File is empty: " + key);
            }
            T message = reader.next();
            if (reader.hasNext()) {
                throw new IOException("File contains multiple messages: " + key);
            }
            return message;
        }
    }

    // ===== Protected helper methods for internal use =====

    /**
     * Opens a reader for streaming message reads.
     * Protected to prevent direct access - use readMessage() or readBatch() instead.
     * Used internally by readMessage() and test utilities.
     */
    protected <T extends MessageLite> MessageReader<T> openReader(String key, Parser<T> parser) throws IOException {
        validateKey(key);

        // Try with compression extension first, fallback to uncompressed
        String storageKey = key + codec.getFileExtension();
        File file = new File(rootDirectory, storageKey);

        if (!file.exists()) {
            // Try without extension (backward compatibility)
            storageKey = key;
            file = new File(rootDirectory, storageKey);
        }

        if (!file.exists()) {
            throw new IOException("File does not exist: " + key);
        }

        return new MessageReaderImpl<>(file, parser, storageKey, codec);
    }

    /**
     * Opens a writer for streaming message writes.
     * Protected to prevent direct access - use writeMessage() or writeBatch() instead.
     * Used internally by writeMessage() and test utilities.
     */
    protected MessageWriter openWriter(String key) throws IOException {
        validateKey(key);

        // Add compression file extension if compression is enabled
        String storageKey = key + codec.getFileExtension();

        File finalFile = new File(rootDirectory, storageKey);
        File tempFile = new File(rootDirectory, storageKey + ".tmp");

        File parentDir = finalFile.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();  // Idempotent - safe to call even if directory exists
            if (!parentDir.isDirectory()) {
                throw new IOException("Failed to create parent directories for: " + finalFile.getAbsolutePath());
            }
        }

        return new MessageWriterImpl(tempFile, finalFile, codec);
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
    }

    @Override
    protected byte[] readBytes(String path) throws IOException {
        validateKey(path);
        File file = new File(rootDirectory, path);
        if (!file.exists()) {
            throw new IOException("File does not exist: " + path);
        }
        return Files.readAllBytes(file.toPath());
    }

    @Override
    protected void appendLine(String path, String line) throws IOException {
        validateKey(path);
        File file = new File(rootDirectory, path);
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();  // Idempotent - safe to call even if directory exists
            if (!parentDir.isDirectory()) {
                throw new IOException("Failed to create parent directories for: " + file.getAbsolutePath());
            }
        }

        String lineWithNewline = line + "\n";
        Files.write(file.toPath(), lineWithNewline.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    @Override
    protected List<String> listKeys(String prefix) throws IOException {
        if (prefix == null) {
            throw new IllegalArgumentException("Prefix cannot be null");
        }
        Path startPath = Paths.get(rootDirectory.getAbsolutePath());
        try (Stream<Path> stream = Files.walk(startPath)) {
            return stream
                .filter(Files::isRegularFile)
                .map(startPath::relativize)
                .map(Path::toString)
                .map(s -> s.replace(File.separatorChar, '/'))
                .filter(path -> !path.endsWith(".tmp"))
                .filter(path -> path.startsWith(prefix))
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new IOException("Failed to list keys with prefix: " + prefix, e);
        }
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

        Files.move(srcFile.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    protected boolean exists(String path) throws IOException {
        validateKey(path);
        return new File(rootDirectory, path).exists();
    }

    @Override
    protected void deleteIfExists(String path) throws IOException {
        validateKey(path);
        File file = new File(rootDirectory, path);
        if (file.exists()) {
            Files.delete(file.toPath());
        }
    }

    @Override
    protected byte[] compressBatch(byte[] data) throws IOException {
        if ("none".equals(compression.codec)) {
            return data;
        }

        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (OutputStream compressedStream = codec.wrapOutputStream(bos)) {
                compressedStream.write(data);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IOException("Failed to compress batch", e);
        }
    }

    @Override
    protected byte[] decompressBatch(byte[] compressedData, String filename) throws IOException {
        // Check if file should be decompressed based on extension
        if (!shouldDecompress(filename)) {
            return compressedData;
        }

        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(compressedData);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream decompressedStream = codec.wrapInputStream(bis)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = decompressedStream.read(buffer)) != -1) {
                    bos.write(buffer, 0, bytesRead);
                }
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IOException("Failed to decompress batch: " + filename, e);
        }
    }

    @Override
    public IResource.UsageState getUsageState(String usageType) {
        if (usageType == null) {
            throw new IllegalArgumentException("Storage requires non-null usageType");
        }

        return switch (usageType) {
            case "storage-read", "storage-write" -> IResource.UsageState.ACTIVE;
            default -> throw new IllegalArgumentException("Unknown usageType: " + usageType);
        };
    }

    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new HashMap<>();
        metrics.put("write_operations", writeOperations.get());
        metrics.put("read_operations", readOperations.get());
        metrics.put("bytes_written", bytesWritten.get());
        metrics.put("bytes_read", bytesRead.get());
        return metrics;
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public List<OperationalError> getErrors() {
        return Collections.emptyList();
    }

    @Override
    public void clearErrors() {
        // No-op
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

    private class MessageWriterImpl implements MessageWriter {
        private final File tempFile;
        private final File finalFile;
        private final FileOutputStream rawOutputStream;
        private final OutputStream outputStream;
        private boolean closed = false;

        MessageWriterImpl(File tempFile, File finalFile, ICompressionCodec codec) throws IOException {
            this.tempFile = tempFile;
            this.finalFile = finalFile;
            this.rawOutputStream = new FileOutputStream(tempFile);
            this.outputStream = codec.wrapOutputStream(rawOutputStream);
        }

        @Override
        public void writeMessage(MessageLite message) throws IOException {
            if (closed) {
                throw new IllegalStateException("Writer already closed");
            }
            if (message == null) {
                throw new IllegalArgumentException("Message cannot be null");
            }
            message.writeDelimitedTo(outputStream);
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;

            try {
                // Flush compressed stream to write all buffered data
                outputStream.flush();

                // Sync to disk BEFORE closing (compression stream close will close raw stream)
                rawOutputStream.getFD().sync();

                // Close compressed stream (this also closes rawOutputStream)
                outputStream.close();
            } catch (IOException e) {
                // Ensure raw stream is closed even if compression close fails
                try {
                    rawOutputStream.close();
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }

            if (!tempFile.renameTo(finalFile)) {
                throw new IOException("Failed to commit file: " + finalFile);
            }

            // Track metrics after successful write
            writeOperations.incrementAndGet();
            long fileSize = finalFile.length();
            bytesWritten.addAndGet(fileSize);
        }
    }

    private class MessageReaderImpl<T extends MessageLite> implements MessageReader<T> {
        private final InputStream rawInputStream;
        private final InputStream inputStream;
        private final Parser<T> parser;
        private T nextMessage = null;
        private boolean closed = false;
        private boolean eof = false;
        private final long fileSize;

        MessageReaderImpl(File file, Parser<T> parser, String key, ICompressionCodec codec) throws IOException {
            this.rawInputStream = new FileInputStream(file);
            // Only decompress if file has compression extension
            this.inputStream = shouldDecompress(key) ? codec.wrapInputStream(rawInputStream) : rawInputStream;
            this.parser = parser;
            this.fileSize = file.length();

            // Track metrics when reader is opened
            readOperations.incrementAndGet();
            bytesRead.addAndGet(fileSize);
        }

        @Override
        public boolean hasNext() {
            if (closed) {
                throw new IllegalStateException("Reader already closed");
            }
            if (nextMessage != null) {
                return true;
            }
            if (eof) {
                return false;
            }

            try {
                nextMessage = parser.parseDelimitedFrom(inputStream);
                if (nextMessage == null) {
                    eof = true;
                    return false;
                }
                return true;
            } catch (IOException e) {
                throw new RuntimeException("Failed to read next message", e);
            }
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            T result = nextMessage;
            nextMessage = null;
            return result;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;

            // Close compressed stream first (if different from raw), then raw stream
            try {
                if (inputStream != rawInputStream) {
                    inputStream.close();
                }
            } finally {
                rawInputStream.close();
            }
        }
    }
}