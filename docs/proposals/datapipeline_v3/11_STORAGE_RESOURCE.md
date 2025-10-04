# Data Pipeline V3 - Storage Resource (Phase 2.2)

## Goal

Implement the storage resource abstraction with FileSystemStorage as the first concrete implementation. This resource provides persistent storage with Protobuf serialization, following the same architectural patterns as queue resources with contextual wrappers, per-service metrics, and transparent serialization handling.

## Scope

**This phase implements ONLY:**
1. Storage capability interfaces (IStorageReadResource, IStorageWriteResource)
2. Streaming interfaces (MessageWriter, MessageReader)
3. FileSystemStorageResource implementation
4. Monitored wrappers (MonitoredStorageWriter, MonitoredStorageReader)
5. DummyWriterService and DummyReaderService for testing

**This phase does NOT implement:**
- PersistenceService (future phase - writes TickData batches from SimulationEngine to storage)
- Indexer services (future phase - reads batches from storage for analysis)
- These are shown in "Future Usage Examples" section for context only

## Success Criteria

Upon completion:
1. Storage capability interfaces compile (IStorageReadResource, IStorageWriteResource)
2. Streaming interfaces compile (MessageWriter, MessageReader<T>)
3. FileSystemStorageResource implements all required interfaces and IContextualResource
4. Atomic writes work correctly (temp file + fsync + rename pattern)
5. Streaming write and read work with length-prefixed Protobuf format
6. Monitored wrappers track metrics with negligible overhead (hybrid buckets + sampling)
7. Hierarchical key structure works (e.g., "sim_123/batch_0_999.pb")
8. listKeys() returns all files matching prefix
9. Multiple concurrent readers work without coordination
10. DummyWriterService writes test data to storage
11. DummyReaderService reads and validates test data from storage
12. Unit and integration tests verify all functionality

## Prerequisites

- Phase 0: API Foundation (completed) - IResource, IMonitorable interfaces
- Phase 1.2: Core Resource Implementation (completed) - Queue pattern, wrappers, metrics
- Phase 2.0: Protobuf Setup (completed) - MessageLite, Parser, delimited format

## Architectural Context

### Design Consistency with Queues

Storage resources follow the same architectural patterns as queue resources:

**Queue Pattern (Reference):**
- Services work with Java objects (DummyMessage, TickData)
- InMemoryBlockingQueue stores objects directly (zero serialization)
- Future SQSQueue serializes to bytes for network
- Services are unaware of serialization (transparent)

**Storage Pattern (This Implementation):**
- Services work with Protobuf objects (MessageLite)
- FileSystemStorage serializes to bytes for disk
- Future S3Storage serializes to bytes for network
- Services are unaware of serialization (transparent)

### Why Protobuf-Aware Storage?

**Performance:** Avoids intermediate buffering - Protobuf messages stream directly to disk
**Consistency:** Same abstraction level as queues (services work with objects, not bytes)
**Type Safety:** Can't accidentally write wrong format

### Use Cases

**Persistence Service (Writer):**
- Drains batches from queue: `List<TickData> batch = queue.drainTo(1000)`
- Opens writer: `MessageWriter writer = storage.openWriter("sim_123/batch_0_999.pb")`
- Streams to disk: `for (tick : batch) writer.writeMessage(tick);`
- Atomic commit: `writer.close();` (temp file renamed to final)

**Indexer Service (Reader):**
- Lists batches: `List<String> files = storage.listKeys("sim_123/batch_")`
- Checks database: "Which batches have I not processed?"
- Opens reader: `MessageReader<TickData> reader = storage.openReader(batchFile, TickData.parser())`
- Streams from disk: `while (reader.hasNext()) processTickForIndex(reader.next());`

**Multiple Indexers (Competing Consumers):**
- All indexers list same files
- Database coordination: Each indexer checks "Is this batch already indexed by my type?"
- No storage-layer coordination needed (zero contention)

## Implementation Requirements

### Package Structure

```
api/resources/storage/
├── IStorageReadResource.java     (capability interface)
├── IStorageWriteResource.java    (capability interface)
├── MessageWriter.java            (streaming write interface)
└── MessageReader.java            (streaming read interface)

resources/storage/
├── FileSystemStorageResource.java  (main implementation)
└── wrappers/
    ├── MonitoredStorageWriter.java
    └── MonitoredStorageReader.java

services/
├── DummyWriterService.java       (test service - writes dummy data)
└── DummyReaderService.java       (test service - reads and validates data)
```

### Capability Interfaces

#### IStorageWriteResource

**File:** `src/main/java/org/evochora/datapipeline/api/resources/storage/IStorageWriteResource.java`

```java
package org.evochora.datapipeline.api.resources.storage;

import com.google.protobuf.MessageLite;
import org.evochora.datapipeline.api.resources.IResource;
import java.io.IOException;

/**
 * Interface for storage resources that support write operations.
 * <p>
 * This interface provides Protobuf-aware storage operations where services work with
 * message objects and the storage layer handles serialization transparently. This maintains
 * architectural consistency with queue resources where serialization is also hidden.
 * <p>
 * Write operations are atomic: files appear only after successful completion. Crashes
 * during writes leave temporary files (.tmp suffix) but never corrupt final data files.
 * <p>
 * Keys use hierarchical structure with '/' separators (e.g., "sim_123/batch_0_999.pb").
 */
public interface IStorageWriteResource extends IResource {

    /**
     * Opens a writer for streaming multiple Protobuf messages to storage.
     * <p>
     * This method is designed for batch writes where multiple messages are written
     * sequentially to the same file. Messages are written in length-prefixed format
     * using Protobuf's writeDelimitedTo() for efficient streaming reads.
     * <p>
     * <strong>Atomicity:</strong> The file appears in storage only when close() succeeds.
     * During writing, data is accumulated in a temporary file. If the writer is not
     * closed properly (e.g., JVM crash), the temporary file remains but the target
     * key does not appear in storage.
     * <p>
     * <strong>Thread Safety:</strong> MessageWriter instances are NOT thread-safe.
     * Each writer must be used by a single thread.
     * <p>
     * <strong>Example usage:</strong>
     * <pre>
     * try (MessageWriter writer = storage.openWriter("sim_123/batch_0_999.pb")) {
     *     for (TickData tick : batchData) {
     *         writer.writeMessage(tick);
     *     }
     * }  // Atomic commit on close
     * </pre>
     *
     * @param key The storage key (hierarchical path with '/' separators)
     * @return A MessageWriter for streaming messages to storage
     * @throws IOException If opening the writer fails (e.g., permission denied)
     * @throws IllegalArgumentException If key is null, empty, or contains invalid characters
     */
    MessageWriter openWriter(String key) throws IOException;
}
```

#### IStorageReadResource

**File:** `src/main/java/org/evochora/datapipeline/api/resources/storage/IStorageReadResource.java`

```java
package org.evochora.datapipeline.api.resources.storage;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import org.evochora.datapipeline.api.resources.IResource;
import java.io.IOException;
import java.util.List;

/**
 * Interface for storage resources that support read and list operations.
 * <p>
 * This interface provides both single-message reads (for metadata) and streaming
 * multi-message reads (for batches). Read operations work with Protobuf message
 * objects, with serialization handled transparently by the storage layer.
 * <p>
 * All read operations support concurrent access - multiple readers can read the
 * same key simultaneously without coordination.
 * <p>
 * Keys use hierarchical structure with '/' separators (e.g., "sim_123/metadata.pb").
 */
public interface IStorageReadResource extends IResource {

    /**
     * Reads a single Protobuf message from storage.
     * <p>
     * This method is designed for reading metadata files or other single-message
     * content. For files containing multiple length-prefixed messages, use
     * {@link #openReader(String, Parser)} instead.
     * <p>
     * <strong>Thread Safety:</strong> Multiple threads can read the same key concurrently.
     * <p>
     * <strong>Example usage:</strong>
     * <pre>
     * SimulationMetadata meta = storage.readMessage(
     *     "sim_123/metadata.pb",
     *     SimulationMetadata.parser()
     * );
     * </pre>
     *
     * @param <T> The Protobuf message type
     * @param key The storage key to read
     * @param parser The Protobuf parser for deserializing (e.g., TickData.parser())
     * @return The deserialized message
     * @throws IOException If the key does not exist or reading fails
     * @throws IllegalArgumentException If key or parser is null
     */
    <T extends MessageLite> T readMessage(String key, Parser<T> parser) throws IOException;

    /**
     * Opens a reader for streaming multiple Protobuf messages from storage.
     * <p>
     * This method is designed for reading batch files containing multiple messages
     * in length-prefixed format (written with writeDelimitedTo()). Messages are
     * parsed lazily on-demand, providing O(1) memory usage regardless of file size.
     * <p>
     * <strong>Thread Safety:</strong> MessageReader instances are NOT thread-safe.
     * Each reader must be used by a single thread. However, multiple readers can
     * read the same key concurrently from different threads.
     * <p>
     * <strong>Example usage:</strong>
     * <pre>
     * try (MessageReader&lt;TickData&gt; reader = storage.openReader(
     *         "sim_123/batch_0_999.pb",
     *         TickData.parser())) {
     *     while (reader.hasNext()) {
     *         TickData tick = reader.next();
     *         processTickForIndex(tick);
     *     }
     * }
     * </pre>
     *
     * @param <T> The Protobuf message type
     * @param key The storage key to read
     * @param parser The Protobuf parser for deserializing
     * @return A MessageReader for streaming messages from storage
     * @throws IOException If the key does not exist or opening fails
     * @throws IllegalArgumentException If key or parser is null
     */
    <T extends MessageLite> MessageReader<T> openReader(String key, Parser<T> parser) throws IOException;

    /**
     * Checks if a key exists in storage.
     * <p>
     * This is a lightweight operation that does not read the file contents.
     *
     * @param key The storage key to check
     * @return true if the key exists, false otherwise
     * @throws IllegalArgumentException If key is null
     */
    boolean exists(String key);

    /**
     * Lists all keys in storage that start with the given prefix.
     * <p>
     * The prefix is treated as a string prefix, not a directory boundary. For example:
     * <ul>
     *   <li>Prefix "sim_123/" matches "sim_123/metadata.pb", "sim_123/batch_0_999.pb"</li>
     *   <li>Prefix "sim_1" matches "sim_1/...", "sim_10/...", "sim_123/..." (all starting with "sim_1")</li>
     * </ul>
     * <p>
     * The returned list is in <strong>unspecified order</strong>. Callers requiring
     * sorted order must sort the result themselves (e.g., Collections.sort()).
     * <p>
     * For batch files with zero-padded tick numbers (e.g., "batch_0000000000000000000_..."),
     * lexicographic sort produces chronological order.
     * <p>
     * <strong>Example usage:</strong>
     * <pre>
     * List&lt;String&gt; batches = storage.listKeys("sim_123/batch_");
     * Collections.sort(batches);  // Chronological order due to zero-padding
     * for (String batchFile : batches) {
     *     if (!isAlreadyIndexed(batchFile)) {
     *         processBatch(batchFile);
     *     }
     * }
     * </pre>
     *
     * @param prefix The prefix to match (empty string matches all keys)
     * @return List of keys starting with prefix (may be empty, never null)
     * @throws IllegalArgumentException If prefix is null
     */
    List<String> listKeys(String prefix);
}
```

#### MessageWriter

**File:** `src/main/java/org/evochora/datapipeline/api/resources/storage/MessageWriter.java`

```java
package org.evochora.datapipeline.api.resources.storage;

import com.google.protobuf.MessageLite;
import java.io.IOException;

/**
 * Interface for streaming multiple Protobuf messages to storage.
 * <p>
 * MessageWriter instances are obtained from {@link IStorageWriteResource#openWriter(String)}
 * and must be closed after use (use try-with-resources). Messages are written in
 * length-prefixed format (Protobuf's writeDelimitedTo) for efficient streaming reads.
 * <p>
 * <strong>Atomicity:</strong> Files appear in storage only when close() succeeds.
 * If close() is not called (e.g., exception during writing, JVM crash), the target
 * key does not appear in storage (only temporary file remains).
 * <p>
 * <strong>Thread Safety:</strong> NOT thread-safe. Each writer must be used by a single thread.
 * <p>
 * <strong>Usage Pattern:</strong>
 * <pre>
 * try (MessageWriter writer = storage.openWriter("key")) {
 *     for (MessageLite msg : batch) {
 *         writer.writeMessage(msg);
 *     }
 * }  // close() called automatically - atomic commit
 * </pre>
 */
public interface MessageWriter extends AutoCloseable {

    /**
     * Writes a Protobuf message to storage in length-prefixed format.
     * <p>
     * This method streams the message directly to storage without intermediate
     * buffering. Messages are written sequentially and can be read back in the
     * same order using MessageReader.
     * <p>
     * <strong>Performance:</strong> This operation has minimal overhead (~40ns for
     * metrics tracking + actual I/O time). The message is serialized directly to
     * the output stream.
     *
     * @param message The Protobuf message to write (must not be null)
     * @throws IOException If writing fails
     * @throws IllegalArgumentException If message is null
     */
    void writeMessage(MessageLite message) throws IOException;

    /**
     * Closes the writer and atomically commits the file to storage.
     * <p>
     * This method performs the following operations:
     * <ol>
     *   <li>Flushes all buffered data to disk</li>
     *   <li>Performs fsync to ensure durability</li>
     *   <li>Atomically renames temporary file to final key (POSIX rename)</li>
     * </ol>
     * <p>
     * After successful close(), the file is visible to all readers. If close()
     * throws an exception, the file does NOT appear in storage.
     * <p>
     * <strong>Idempotent:</strong> Calling close() multiple times is safe (subsequent
     * calls are no-ops).
     *
     * @throws IOException If commit fails (file does not appear in storage)
     */
    @Override
    void close() throws IOException;
}
```

#### MessageReader

**File:** `src/main/java/org/evochora/datapipeline/api/resources/storage/MessageReader.java`

```java
package org.evochora.datapipeline.api.resources.storage;

import com.google.protobuf.MessageLite;
import java.io.IOException;
import java.util.Iterator;

/**
 * Interface for streaming multiple Protobuf messages from storage.
 * <p>
 * MessageReader instances are obtained from {@link IStorageReadResource#openReader(String, Parser)}
 * and must be closed after use (use try-with-resources). Messages are read lazily from
 * length-prefixed format, providing O(1) memory usage regardless of file size.
 * <p>
 * <strong>Thread Safety:</strong> NOT thread-safe. Each reader must be used by a single thread.
 * However, multiple readers can read the same key concurrently from different threads.
 * <p>
 * <strong>Usage Pattern:</strong>
 * <pre>
 * try (MessageReader&lt;TickData&gt; reader = storage.openReader("key", TickData.parser())) {
 *     while (reader.hasNext()) {
 *         TickData tick = reader.next();
 *         processTickForIndex(tick);
 *     }
 * }
 * </pre>
 *
 * @param <T> The Protobuf message type being read
 */
public interface MessageReader<T extends MessageLite> extends AutoCloseable, Iterator<T> {

    /**
     * Checks if more messages are available to read.
     * <p>
     * This method does not parse the next message - it only checks if the end
     * of file has been reached. Parsing happens lazily in next().
     *
     * @return true if more messages available, false at end of file
     */
    @Override
    boolean hasNext();

    /**
     * Reads and parses the next Protobuf message from storage.
     * <p>
     * Messages are parsed lazily on-demand, providing O(1) memory usage. This
     * method reads the length prefix, then parses exactly that many bytes into
     * the message object.
     * <p>
     * <strong>Performance:</strong> Parsing time depends on message size, typically
     * 100-1000 nanoseconds for TickData messages.
     *
     * @return The next deserialized message
     * @throws java.util.NoSuchElementException If no more messages available (check hasNext() first)
     * @throws RuntimeException wrapping IOException If parsing fails (corrupt data)
     */
    @Override
    T next();

    /**
     * Closes the reader and releases file handle.
     * <p>
     * After close(), hasNext() and next() throw IllegalStateException.
     * <p>
     * <strong>Idempotent:</strong> Calling close() multiple times is safe (subsequent
     * calls are no-ops).
     *
     * @throws IOException If closing the underlying stream fails
     */
    @Override
    void close() throws IOException;
}
```

### FileSystemStorageResource Implementation

**File:** `src/main/java/org/evochora/datapipeline/resources/storage/FileSystemStorageResource.java`

**Class Declaration:**
```java
public class FileSystemStorageResource extends AbstractResource
    implements IContextualResource, IStorageWriteResource, IStorageReadResource, IMonitorable
```

**Required Constructor:**
```java
public FileSystemStorageResource(String name, Config options) {
    super(name, options);
    // Configuration loading with validation
}
```

**Configuration Options:**
- **rootDirectory** (String, REQUIRED): Base directory for all storage files
  - Must be absolute path
  - Parent directory must exist
  - Directory will be created if it doesn't exist
  - Example: "/data/evochora/storage" or "/tmp/test-storage"

**Configuration Example:**
```hocon
storage-main {
  className = "org.evochora.datapipeline.resources.storage.FileSystemStorageResource"
  options {
    rootDirectory = "/data/evochora/storage"
  }
}
```

**Key to Filesystem Path Mapping:**
- Key "sim_123/metadata.pb" → File "{rootDirectory}/sim_123/metadata.pb"
- Key "sim_123/batch_0_999.pb" → File "{rootDirectory}/sim_123/batch_0_999.pb"
- Directories are created automatically as needed

**IContextualResource Implementation:**
```java
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
```

**Atomic Write Implementation Pattern:**
```java
@Override
public MessageWriter openWriter(String key) throws IOException {
    validateKey(key);
    File finalFile = new File(rootDirectory, key);
    File tempFile = new File(rootDirectory, key + ".tmp");

    // Create parent directories if needed
    finalFile.getParentFile().mkdirs();

    return new MessageWriterImpl(tempFile, finalFile);
}

private class MessageWriterImpl implements MessageWriter {
    private final File tempFile;
    private final File finalFile;
    private final OutputStream outputStream;
    private boolean closed = false;

    @Override
    public void writeMessage(MessageLite message) throws IOException {
        if (closed) throw new IllegalStateException("Writer already closed");
        message.writeDelimitedTo(outputStream);  // Length-prefixed format
    }

    @Override
    public void close() throws IOException {
        if (closed) return;  // Idempotent

        outputStream.flush();
        ((FileOutputStream) outputStream).getFD().sync();  // fsync
        outputStream.close();

        // Atomic rename (POSIX guarantees atomicity)
        if (!tempFile.renameTo(finalFile)) {
            throw new IOException("Failed to commit file: " + finalFile);
        }

        closed = true;
    }
}
```

**Streaming Read Implementation Pattern:**
```java
@Override
public <T extends MessageLite> MessageReader<T> openReader(String key, Parser<T> parser) throws IOException {
    validateKey(key);
    File file = new File(rootDirectory, key);

    if (!file.exists()) {
        throw new IOException("Key does not exist: " + key);
    }

    return new MessageReaderImpl<>(file, parser);
}

private class MessageReaderImpl<T extends MessageLite> implements MessageReader<T> {
    private final InputStream inputStream;
    private final Parser<T> parser;
    private T nextMessage = null;
    private boolean closed = false;

    @Override
    public boolean hasNext() {
        if (closed) throw new IllegalStateException("Reader already closed");
        if (nextMessage != null) return true;

        try {
            nextMessage = parser.parseDelimitedFrom(inputStream);
            return nextMessage != null;
        } catch (IOException e) {
            return false;  // End of stream
        }
    }

    @Override
    public T next() {
        if (!hasNext()) throw new NoSuchElementException();
        T result = nextMessage;
        nextMessage = null;
        return result;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;  // Idempotent
        inputStream.close();
        closed = true;
    }
}
```

**UsageState Implementation:**
```java
@Override
public UsageState getUsageState(String usageType) {
    if (usageType == null) {
        throw new IllegalArgumentException("Storage requires non-null usageType");
    }

    return switch (usageType) {
        case "storage-read", "storage-write" -> UsageState.ACTIVE;
        default -> throw new IllegalArgumentException("Unknown usageType: " + usageType);
    };
}
```

### Monitored Wrappers

#### MonitoredStorageWriter

**File:** `src/main/java/org/evochora/datapipeline/resources/storage/wrappers/MonitoredStorageWriter.java`

**Purpose:** Wraps IStorageWriteResource to track per-service write metrics

**Interfaces:** `IStorageWriteResource`, `IWrappedResource`, `IMonitorable`

**Metrics Implementation (Hybrid Buckets + Sampling):**
```java
public class MonitoredStorageWriter extends AbstractResource
    implements IStorageWriteResource, IWrappedResource, IMonitorable {

    private final IStorageWriteResource delegate;
    private final ResourceContext context;

    // Always-tracked totals (100% accurate)
    private final AtomicLong totalMessagesWritten = new AtomicLong(0);
    private final AtomicLong totalBytesWritten = new AtomicLong(0);
    private final AtomicLong writeOperations = new AtomicLong(0);
    private final AtomicLong writeErrors = new AtomicLong(0);

    // Sampled throughput tracking (10% sampling for performance)
    private final ConcurrentHashMap<Long, AtomicLong> messagesPerSecond = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> bytesPerSecond = new ConcurrentHashMap<>();
    private final int throughputWindowSeconds;

    @Override
    public MessageWriter openWriter(String key) throws IOException {
        writeOperations.incrementAndGet();
        MessageWriter delegateWriter = delegate.openWriter(key);
        return new MonitoredMessageWriter(delegateWriter);
    }

    private class MonitoredMessageWriter implements MessageWriter {
        private final MessageWriter delegate;

        @Override
        public void writeMessage(MessageLite message) throws IOException {
            try {
                delegate.writeMessage(message);

                // Always track totals (super fast: ~10ns)
                int messageSize = message.getSerializedSize();
                totalMessagesWritten.incrementAndGet();
                totalBytesWritten.addAndGet(messageSize);

                // Sample for throughput (10% of writes = 1.2% overhead)
                if (ThreadLocalRandom.current().nextInt(10) == 0) {
                    recordThroughputSample(messageSize);
                }

            } catch (IOException e) {
                writeErrors.incrementAndGet();
                throw e;
            }
        }

        private void recordThroughputSample(int messageSize) {
            long currentSecond = Instant.now().getEpochSecond();

            // Multiply by 10 to compensate for 10% sampling rate
            messagesPerSecond.computeIfAbsent(currentSecond, k -> new AtomicLong(0))
                .addAndGet(10);
            bytesPerSecond.computeIfAbsent(currentSecond, k -> new AtomicLong(0))
                .addAndGet(messageSize * 10);

            // Cleanup old buckets (keep window + 5 buffer)
            if (messagesPerSecond.size() > throughputWindowSeconds + 5) {
                long cutoff = currentSecond - throughputWindowSeconds - 5;
                messagesPerSecond.entrySet().removeIf(e -> e.getKey() < cutoff);
                bytesPerSecond.entrySet().removeIf(e -> e.getKey() < cutoff);
            }
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    @Override
    public Map<String, Number> getMetrics() {
        long currentSecond = Instant.now().getEpochSecond();
        long windowStart = currentSecond - throughputWindowSeconds;

        // Sum buckets within window
        long messagesInWindow = messagesPerSecond.entrySet().stream()
            .filter(e -> e.getKey() >= windowStart)
            .mapToLong(e -> e.getValue().get())
            .sum();

        long bytesInWindow = bytesPerSecond.entrySet().stream()
            .filter(e -> e.getKey() >= windowStart)
            .mapToLong(e -> e.getValue().get())
            .sum();

        return Map.of(
            "messages_written", totalMessagesWritten.get(),
            "bytes_written", totalBytesWritten.get(),
            "write_operations", writeOperations.get(),
            "throughput_msgs_per_sec", (double) messagesInWindow / throughputWindowSeconds,
            "throughput_bytes_per_sec", (double) bytesInWindow / throughputWindowSeconds,
            "errors", writeErrors.get()
        );
    }
}
```

#### MonitoredStorageReader

**File:** `src/main/java/org/evochora/datapipeline/resources/storage/wrappers/MonitoredStorageReader.java`

**Purpose:** Wraps IStorageReadResource to track per-service read metrics

**Interfaces:** `IStorageReadResource`, `IWrappedResource`, `IMonitorable`

**Implementation:** Same hybrid buckets + sampling pattern as writer, tracking:
- `messages_read` (total count, 100% accurate)
- `bytes_read` (total bytes, 100% accurate)
- `read_operations` (number of openReader calls)
- `throughput_msgs_per_sec` (calculated from sampled buckets, ~10% accurate)
- `throughput_bytes_per_sec` (calculated from sampled buckets, ~10% accurate)
- `errors` (count of failed operations)

### Batch Filename Convention

**Format:** `batch_[START_TICK]_[END_TICK].pb`

**Padding:** Both tick numbers padded to 19 digits (full Java long range)

**Examples:**
- Ticks 0-999: `"batch_0000000000000000000_0000000000000000999.pb"`
- Ticks 1000-1999: `"batch_0000000000000001000_0000000000000001999.pb"`
- Ticks 1000000000-1000000999: `"batch_0000000001000000000_0000000001000000999.pb"`

**Format String:**
```java
String filename = String.format("batch_%019d_%019d.pb", startTick, endTick);
```

**Why 19 digits?**
- Java long range: -9,223,372,036,854,775,808 to 9,223,372,036,854,775,807
- Maximum: 9,223,372,036,854,775,807 (19 digits)
- Zero-padding ensures lexicographic sort = chronological sort
- No artificial tick limit

**Complete Key Example:**
```
"sim_20250104_123456/batch_0000000000000000000_0000000000000000999.pb"
```

### Coding Standards

#### Documentation Requirements
- **All public classes and interfaces**: Comprehensive Javadoc with usage examples
- **All public methods**: Javadoc with @param, @return, @throws documentation
- **Thread safety**: Explicitly document in Javadoc
- **Performance characteristics**: Document O(1) vs O(n) operations
- **Atomicity guarantees**: Document when operations are atomic

#### Naming Conventions
- **Classes**: PascalCase (FileSystemStorageResource, MonitoredStorageWriter)
- **Interfaces**: IPascalCase (IStorageReadResource, IStorageWriteResource)
- **Methods**: camelCase (openWriter, readMessage, listKeys)
- **Constants**: UPPER_SNAKE_CASE
- **Generics**: Single letter T for message types

#### Error Handling
- **IOException**: Used for all I/O failures (file not found, read errors, write errors)
- **IllegalArgumentException**: Used for invalid parameters (null keys, null messages, invalid paths)
- **IllegalStateException**: Used for invalid state (writer already closed, reader already closed)
- **Error messages**: Must include context (key name, resource name, operation)
- **Error recording**: Failed operations increment error counter in metrics

#### Configuration Handling
- **TypeSafe Config**: Use Config.getString(), Config.hasPath() for all config access
- **Required validation**: Throw IllegalArgumentException with clear message if required options missing
- **Path validation**: Validate rootDirectory is absolute and parent exists

#### Performance Requirements
- **Metrics overhead**: Must be ≤ 2% of total operation time
- **Write operations**: Use buffered I/O (BufferedOutputStream with 64KB buffer)
- **Atomic commits**: Use fsync only on close(), not per message
- **Memory usage**: Streaming operations must be O(1) memory regardless of file size
- **No background threads**: All metrics calculated on-demand

### Test Services (This Phase)

#### DummyWriterService

**File:** `src/main/java/org/evochora/datapipeline/services/DummyWriterService.java`

**Purpose:** Test service that generates dummy Protobuf messages and writes them to storage for verification.

**Extends:** `AbstractService`

**Implements:** `IMonitorable`

**Required Resources:**
- `storage` (IStorageWriteResource) - Storage resource to write to

**Configuration Options:**
- `intervalMs` (int, default: 1000) - Milliseconds between write operations
- `messagesPerWrite` (int, default: 10) - Messages to write in each batch
- `maxWrites` (int, default: -1) - Maximum writes to perform (-1 for unlimited)
- `keyPrefix` (String, default: "test") - Prefix for storage keys

**Behavior:**
1. Generates dummy TickData messages with incrementing tick numbers
2. Writes batches to storage using zero-padded filenames
3. Tracks metrics: messages written, bytes written, write operations
4. Supports pause/resume
5. Stops after maxWrites (if configured)

**Implementation:**
```java
public class DummyWriterService extends AbstractService implements IMonitorable {
    private final IStorageWriteResource storage;
    private final int intervalMs;
    private final int messagesPerWrite;
    private final int maxWrites;
    private final String keyPrefix;

    private final AtomicLong totalMessagesWritten = new AtomicLong(0);
    private final AtomicLong totalBytesWritten = new AtomicLong(0);
    private final AtomicLong writeOperations = new AtomicLong(0);
    private long currentTick = 0;

    public DummyWriterService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.storage = getRequiredResource("storage", IStorageWriteResource.class);
        this.intervalMs = options.hasPath("intervalMs") ? options.getInt("intervalMs") : 1000;
        this.messagesPerWrite = options.hasPath("messagesPerWrite") ? options.getInt("messagesPerWrite") : 10;
        this.maxWrites = options.hasPath("maxWrites") ? options.getInt("maxWrites") : -1;
        this.keyPrefix = options.hasPath("keyPrefix") ? options.getString("keyPrefix") : "test";
    }

    @Override
    protected void run() throws InterruptedException {
        int writeCount = 0;

        while (!Thread.currentThread().isInterrupted()) {
            checkPause();

            // Stop if reached max writes
            if (maxWrites > 0 && writeCount >= maxWrites) {
                log.info("Reached maxWrites ({}), stopping", maxWrites);
                break;
            }

            // Generate batch filename
            long startTick = currentTick;
            long endTick = currentTick + messagesPerWrite - 1;
            String filename = String.format("batch_%019d_%019d.pb", startTick, endTick);
            String key = keyPrefix + "/" + filename;

            try {
                // Write batch
                try (MessageWriter writer = storage.openWriter(key)) {
                    for (int i = 0; i < messagesPerWrite; i++) {
                        TickData tick = generateDummyTickData(currentTick++);
                        writer.writeMessage(tick);

                        totalMessagesWritten.incrementAndGet();
                        totalBytesWritten.addAndGet(tick.getSerializedSize());
                    }
                }
                writeOperations.incrementAndGet();
                log.debug("Wrote batch {} with {} messages", key, messagesPerWrite);

            } catch (IOException e) {
                log.error("Failed to write batch {}", key, e);
            }

            writeCount++;
            Thread.sleep(intervalMs);
        }
    }

    private TickData generateDummyTickData(long tickNumber) {
        return TickData.newBuilder()
            .setSimulationRunId("dummy_run")
            .setTickNumber(tickNumber)
            .setCaptureTimeMs(System.currentTimeMillis())
            .build();
    }

    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
            "messages_written", totalMessagesWritten.get(),
            "bytes_written", totalBytesWritten.get(),
            "write_operations", writeOperations.get()
        );
    }
}
```

**Configuration Example:**
```hocon
dummy-writer {
  className = "org.evochora.datapipeline.services.DummyWriterService"
  resources {
    storage = "storage-write:storage-main"
  }
  options {
    intervalMs = 100        # Write every 100ms
    messagesPerWrite = 50   # 50 messages per batch
    maxWrites = 20          # Stop after 20 batches
    keyPrefix = "test_run"  # Files: test_run/batch_...
  }
}
```

#### DummyReaderService

**File:** `src/main/java/org/evochora/datapipeline/services/DummyReaderService.java`

**Purpose:** Test service that reads and validates dummy messages written by DummyWriterService.

**Extends:** `AbstractService`

**Implements:** `IMonitorable`

**Required Resources:**
- `storage` (IStorageReadResource) - Storage resource to read from

**Configuration Options:**
- `keyPrefix` (String, default: "test") - Prefix for storage keys to read
- `intervalMs` (int, default: 1000) - Milliseconds between scans
- `validateData` (boolean, default: true) - Validate tick sequence

**Behavior:**
1. Lists all batch files with given prefix
2. Reads and validates each batch (checks tick sequence)
3. Tracks metrics: messages read, bytes read, validation errors
4. Supports pause/resume
5. Continuously scans for new files

**Implementation:**
```java
public class DummyReaderService extends AbstractService implements IMonitorable {
    private final IStorageReadResource storage;
    private final String keyPrefix;
    private final int intervalMs;
    private final boolean validateData;

    private final AtomicLong totalMessagesRead = new AtomicLong(0);
    private final AtomicLong totalBytesRead = new AtomicLong(0);
    private final AtomicLong readOperations = new AtomicLong(0);
    private final AtomicLong validationErrors = new AtomicLong(0);
    private final Set<String> processedFiles = ConcurrentHashMap.newKeySet();

    public DummyReaderService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.storage = getRequiredResource("storage", IStorageReadResource.class);
        this.keyPrefix = options.hasPath("keyPrefix") ? options.getString("keyPrefix") : "test";
        this.intervalMs = options.hasPath("intervalMs") ? options.getInt("intervalMs") : 1000;
        this.validateData = options.hasPath("validateData") ? options.getBoolean("validateData") : true;
    }

    @Override
    protected void run() throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            checkPause();

            // List batch files
            List<String> batchFiles = storage.listKeys(keyPrefix + "/batch_");
            Collections.sort(batchFiles);  // Chronological order

            for (String batchFile : batchFiles) {
                if (processedFiles.contains(batchFile)) {
                    continue;  // Already processed
                }

                try {
                    // Read and validate batch
                    long expectedTick = -1;
                    try (MessageReader<TickData> reader = storage.openReader(batchFile, TickData.parser())) {
                        while (reader.hasNext()) {
                            TickData tick = reader.next();

                            totalMessagesRead.incrementAndGet();
                            totalBytesRead.addAndGet(tick.getSerializedSize());

                            // Validate tick sequence
                            if (validateData && expectedTick >= 0) {
                                if (tick.getTickNumber() != expectedTick) {
                                    log.warn("Tick sequence error in {}: expected {}, got {}",
                                        batchFile, expectedTick, tick.getTickNumber());
                                    validationErrors.incrementAndGet();
                                }
                            }
                            expectedTick = tick.getTickNumber() + 1;
                        }
                    }
                    readOperations.incrementAndGet();
                    processedFiles.add(batchFile);
                    log.debug("Read and validated batch {}", batchFile);

                } catch (IOException e) {
                    log.error("Failed to read batch {}", batchFile, e);
                }
            }

            Thread.sleep(intervalMs);
        }
    }

    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
            "messages_read", totalMessagesRead.get(),
            "bytes_read", totalBytesRead.get(),
            "read_operations", readOperations.get(),
            "validation_errors", validationErrors.get(),
            "files_processed", processedFiles.size()
        );
    }
}
```

**Configuration Example:**
```hocon
dummy-reader {
  className = "org.evochora.datapipeline.services.DummyReaderService"
  resources {
    storage = "storage-read:storage-main"
  }
  options {
    keyPrefix = "test_run"   # Read test_run/batch_... files
    intervalMs = 500         # Scan every 500ms
    validateData = true      # Validate tick sequence
  }
}
```

**Integration Test Configuration:**
```hocon
pipeline {
  resources {
    storage-main {
      className = "org.evochora.datapipeline.resources.storage.FileSystemStorageResource"
      options {
        rootDirectory = "/tmp/test-storage"
      }
    }
  }

  services {
    dummy-writer {
      className = "org.evochora.datapipeline.services.DummyWriterService"
      resources {
        storage = "storage-write:storage-main"
      }
      options {
        intervalMs = 100
        messagesPerWrite = 100
        maxWrites = 10
        keyPrefix = "test_run"
      }
    }

    dummy-reader {
      className = "org.evochora.datapipeline.services.DummyReaderService"
      resources {
        storage = "storage-read:storage-main"
      }
      options {
        keyPrefix = "test_run"
        intervalMs = 200
        validateData = true
      }
    }
  }

  startupSequence = ["dummy-writer", "dummy-reader"]
}
```

### Future Usage Examples (Not Implemented in This Phase)

**Note:** The following examples show how storage will be used by future services. These services are NOT part of this phase and should NOT be implemented.

#### Persistence Service (Writing Batches)

```java
public class PersistenceService extends AbstractService {
    private final IInputQueueResource<TickData> inputQueue;
    private final IStorageWriteResource storage;
    private final int batchSize;

    public PersistenceService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.inputQueue = getRequiredResource("input", IInputQueueResource.class);
        this.storage = getRequiredResource("storage", IStorageWriteResource.class);
        this.batchSize = options.getInt("batchSize");  // e.g., 1000
    }

    @Override
    protected void run() throws InterruptedException {
        String simulationRunId = "sim_20250104_123456";
        long batchStartTick = 0;

        while (!Thread.currentThread().isInterrupted()) {
            // Drain batch from queue
            List<TickData> batch = new ArrayList<>();
            int count = inputQueue.drainTo(batch, batchSize);

            if (count == 0) {
                Thread.sleep(100);  // Wait for data
                continue;
            }

            // Determine tick range
            long startTick = batch.get(0).getTickNumber();
            long endTick = batch.get(count - 1).getTickNumber();

            // Generate filename with zero-padded tick numbers
            String filename = String.format("batch_%019d_%019d.pb", startTick, endTick);
            String key = simulationRunId + "/" + filename;

            // Stream batch to storage (atomic write)
            try (MessageWriter writer = storage.openWriter(key)) {
                for (TickData tick : batch) {
                    writer.writeMessage(tick);
                }
            }  // Atomic commit on close

            log.info("Persisted batch {} with {} ticks", key, count);
        }
    }
}
```

**Configuration:**
```hocon
persistence-service {
  className = "org.evochora.datapipeline.services.PersistenceService"
  resources {
    input = "queue-in:raw-tick-data"
    storage = "storage-write:storage-main"
  }
  options {
    batchSize = 1000
  }
}
```

#### Indexer Service (Reading Batches)

```java
public class EnvironmentIndexerService extends AbstractService {
    private final IStorageReadResource storage;
    private final DatabaseConnection database;

    public EnvironmentIndexerService(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.storage = getRequiredResource("storage", IStorageReadResource.class);
        this.database = new DatabaseConnection(options);
    }

    @Override
    protected void run() throws InterruptedException {
        String simulationRunId = "sim_20250104_123456";

        // List all batch files
        List<String> batchFiles = storage.listKeys(simulationRunId + "/batch_");
        Collections.sort(batchFiles);  // Chronological order (zero-padded)

        for (String batchFile : batchFiles) {
            checkPause();  // Support pause/resume

            // Check database: already indexed?
            if (database.isBatchIndexed("environment", batchFile)) {
                continue;
            }

            // Stream and index batch
            try (MessageReader<TickData> reader = storage.openReader(batchFile, TickData.parser())) {
                database.beginTransaction();

                while (reader.hasNext()) {
                    TickData tick = reader.next();

                    // Extract environment data for indexing
                    for (CellState cell : tick.getCellsList()) {
                        database.insertEnvironmentData(
                            tick.getTickNumber(),
                            cell.getPosition(),
                            cell.getEnergy()
                        );
                    }
                }

                database.markBatchIndexed("environment", batchFile);
                database.commitTransaction();
                log.info("Indexed batch {}", batchFile);

            } catch (IOException e) {
                database.rollbackTransaction();
                log.error("Failed to index batch {}", batchFile, e);
            }
        }
    }
}
```

**Configuration:**
```hocon
environment-indexer {
  className = "org.evochora.datapipeline.services.EnvironmentIndexerService"
  resources {
    storage = "storage-read:storage-main"
  }
  options {
    databaseUrl = "jdbc:postgresql://localhost/evochora"
  }
}
```

#### Multiple Competing Indexers

```hocon
# Scale environment indexing with 3 instances
environment-indexer-1 {
  className = "org.evochora.datapipeline.services.EnvironmentIndexerService"
  resources {
    storage = "storage-read:storage-main"
  }
}

environment-indexer-2 {
  className = "org.evochora.datapipeline.services.EnvironmentIndexerService"
  resources {
    storage = "storage-read:storage-main"
  }
}

environment-indexer-3 {
  className = "org.evochora.datapipeline.services.EnvironmentIndexerService"
  resources {
    storage = "storage-read:storage-main"
  }
}
```

All three indexers list the same files. Each checks the shared database before processing:
- Indexer-1 processes batch_0_999, marks it indexed
- Indexer-2 sees batch_0_999 already indexed, processes batch_1000_1999
- Indexer-3 processes batch_2000_2999
- Zero storage-layer coordination needed

### Test Requirements

#### Unit Tests

**FileSystemStorageResourceTest.java:**
1. Test atomic writes: Verify temp file → rename pattern
2. Test crash during write: Verify final file does not appear
3. Test concurrent reads: Multiple threads reading same key
4. Test hierarchical keys: Verify directory creation
5. Test listKeys with various prefixes
6. Test metrics: Verify counters and throughput calculation
7. Test error handling: IOException for missing files

**MonitoredStorageWriterTest.java:**
1. Test metrics tracking: Verify all counters increment
2. Test throughput sampling: Verify ~10% accuracy
3. Test error counting: Verify errors increment on IOException

**MonitoredStorageReaderTest.java:**
1. Test metrics tracking: Verify all counters increment
2. Test streaming: Verify lazy parsing (O(1) memory)

#### Integration Tests

**DummyWriterReaderIntegrationTest.java:**
1. Start DummyWriterService, write test batches
2. Start DummyReaderService, verify it reads all batches
3. Verify data integrity (all ticks readable, correct sequence)
4. Test concurrent writer and reader (reader doesn't see partial files)
5. Verify metrics: messages written == messages read

## Future Extensions (Deferred)

These features are explicitly deferred to maintain YAGNI principle:

1. **Direct wrappers** (`storage-read-direct`, `storage-write-direct`)
2. **InMemoryStorage** (for testing/short simulations)
3. **S3Storage** (cloud deployment)
4. **Metadata in listKeys()** (file sizes, timestamps)
5. **Conditional writes** (writeIfNotExists for locking)
6. **Compression** (gzip batch files)
7. **Checksums** (validate data integrity)
8. **Batch deletion** (cleanup old simulation runs)

These can be added later without breaking the current API.
