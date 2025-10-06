package org.evochora.datapipeline.resources.storage;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IContextualResource;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.IStorageReadResource;
import org.evochora.datapipeline.api.resources.storage.IStorageWriteResource;
import org.evochora.datapipeline.api.resources.storage.MessageReader;
import org.evochora.datapipeline.api.resources.storage.MessageWriter;
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.resources.storage.wrappers.MonitoredStorageReader;
import org.evochora.datapipeline.resources.storage.wrappers.MonitoredStorageWriter;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileSystemStorageResource extends AbstractResource
    implements IContextualResource, IStorageWriteResource, IStorageReadResource, IMonitorable {

    private static final Logger log = LoggerFactory.getLogger(FileSystemStorageResource.class);
    private final File rootDirectory;

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

    @Override
    public IWrappedResource getWrappedResource(ResourceContext context) {
        if (context.usageType() == null) {
            throw new IllegalArgumentException(String.format(
                "Storage resource '%s' requires a usageType. " +
                "Use 'storage-read' or 'storage-write'.",
                getResourceName()
            ));
        }

        return switch (context.usageType()) {
            case "storage-read" -> new MonitoredStorageReader(this, context);
            case "storage-write" -> new MonitoredStorageWriter(this, context);
            default -> throw new IllegalArgumentException(String.format(
                "Unsupported usage type '%s' for storage resource '%s'. " +
                "Supported: storage-read, storage-write",
                context.usageType(), getResourceName()
            ));
        };
    }

    @Override
    public <T extends MessageLite> T readMessage(String key, Parser<T> parser) throws IOException {
        validateKey(key);
        if (parser == null) {
            throw new IllegalArgumentException("Parser cannot be null");
        }
        File file = new File(rootDirectory, key);
        if (!file.exists()) {
            throw new IOException("Key does not exist: " + key);
        }
        try (InputStream input = new FileInputStream(file)) {
            return parser.parseFrom(input);
        }
    }

    @Override
    public <T extends MessageLite> MessageReader<T> openReader(String key, Parser<T> parser) throws IOException {
        validateKey(key);
        if (parser == null) {
            throw new IllegalArgumentException("Parser cannot be null");
        }
        File file = new File(rootDirectory, key);

        if (!file.exists()) {
            throw new IOException("Key does not exist: " + key);
        }

        return new MessageReaderImpl<>(file, parser);
    }

    @Override
    public boolean exists(String key) {
        validateKey(key);
        return new File(rootDirectory, key).exists();
    }

    @Override
    public List<String> listKeys(String prefix) {
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
            // Fail fast instead of returning empty list (indistinguishable from "no files")
            // This prevents indexers from thinking they're done when disk is actually failing
            throw new RuntimeException("Failed to list keys with prefix: " + prefix, e);
        }
    }

    @Override
    public MessageWriter openWriter(String key) throws IOException {
        validateKey(key);
        File finalFile = new File(rootDirectory, key);
        File tempFile = new File(rootDirectory, key + ".tmp");

        File parentDir = finalFile.getParentFile();
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("Failed to create parent directories for: " + finalFile.getAbsolutePath());
        }

        return new MessageWriterImpl(tempFile, finalFile);
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
        return Collections.emptyMap();
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
        private final FileOutputStream outputStream;
        private boolean closed = false;

        MessageWriterImpl(File tempFile, File finalFile) throws IOException {
            this.tempFile = tempFile;
            this.finalFile = finalFile;
            this.outputStream = new FileOutputStream(tempFile);
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
                outputStream.flush();
                outputStream.getFD().sync();
            } finally {
                outputStream.close();
            }

            if (!tempFile.renameTo(finalFile)) {
                throw new IOException("Failed to commit file: " + finalFile);
            }
        }
    }

    private class MessageReaderImpl<T extends MessageLite> implements MessageReader<T> {
        private final InputStream inputStream;
        private final Parser<T> parser;
        private T nextMessage = null;
        private boolean closed = false;
        private boolean eof = false;

        MessageReaderImpl(File file, Parser<T> parser) throws IOException {
            this.inputStream = new FileInputStream(file);
            this.parser = parser;
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
            inputStream.close();
        }
    }
}