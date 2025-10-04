package org.evochora.datapipeline.resources.storage.wrappers;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.IStorageReadResource;
import org.evochora.datapipeline.api.resources.storage.MessageReader;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.resources.AbstractResource;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.evochora.datapipeline.api.resources.OperationalError;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public class MonitoredStorageReader extends AbstractResource
    implements IStorageReadResource, IWrappedResource, IMonitorable {

    private final IStorageReadResource delegate;
    private final ResourceContext context;

    private final AtomicLong totalMessagesRead = new AtomicLong(0);
    private final AtomicLong totalBytesRead = new AtomicLong(0);
    private final AtomicLong readOperations = new AtomicLong(0);
    private final AtomicLong listOperations = new AtomicLong(0);
    private final AtomicLong readErrors = new AtomicLong(0);

    private final ConcurrentHashMap<Long, AtomicLong> messagesPerSecond = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> bytesPerSecond = new ConcurrentHashMap<>();
    private final int throughputWindowSeconds;

    public MonitoredStorageReader(IStorageReadResource delegate, ResourceContext context) {
    super(String.format("monitored-reader(%s)", delegate.getResourceName()), ConfigFactory.parseMap(context.parameters()));
        this.delegate = delegate;
        this.context = context;
        this.throughputWindowSeconds = getOptions().hasPath("throughputWindowSeconds")
            ? getOptions().getInt("throughputWindowSeconds")
            : 60;
    }

    @Override
    public <T extends MessageLite> T readMessage(String key, Parser<T> parser) throws IOException {
        readOperations.incrementAndGet();
        try {
            T message = delegate.readMessage(key, parser);
            totalMessagesRead.incrementAndGet();
            totalBytesRead.addAndGet(message.getSerializedSize());
            return message;
        } catch (IOException e) {
            readErrors.incrementAndGet();
            throw e;
        }
    }

    @Override
    public <T extends MessageLite> MessageReader<T> openReader(String key, Parser<T> parser) throws IOException {
        readOperations.incrementAndGet();
        try {
            MessageReader<T> delegateReader = delegate.openReader(key, parser);
            return new MonitoredMessageReader<>(delegateReader);
        } catch (IOException e) {
            readErrors.incrementAndGet();
            throw e;
        }
    }

    @Override
    public boolean exists(String key) {
        return delegate.exists(key);
    }

    @Override
    public List<String> listKeys(String prefix) {
        listOperations.incrementAndGet();
        return delegate.listKeys(prefix);
    }

    @Override
    public Map<String, Number> getMetrics() {
        long currentSecond = Instant.now().getEpochSecond();
        long windowStart = currentSecond - throughputWindowSeconds;

        long messagesInWindow = messagesPerSecond.entrySet().stream()
            .filter(e -> e.getKey() >= windowStart)
            .mapToLong(e -> e.getValue().get())
            .sum();

        long bytesInWindow = bytesPerSecond.entrySet().stream()
            .filter(e -> e.getKey() >= windowStart)
            .mapToLong(e -> e.getValue().get())
            .sum();

        return Map.of(
            "messages_read", totalMessagesRead.get(),
            "bytes_read", totalBytesRead.get(),
            "read_operations", readOperations.get(),
            "list_operations", listOperations.get(),
            "throughput_msgs_per_sec", (double) messagesInWindow / throughputWindowSeconds,
            "throughput_bytes_per_sec", (double) bytesInWindow / throughputWindowSeconds,
            "errors", readErrors.get()
        );
    }

    @Override
public IResource.UsageState getUsageState(String usageType) {
    return delegate.getUsageState(usageType);
    }

@Override
public boolean isHealthy() {
    if (delegate instanceof IMonitorable) {
        return ((IMonitorable) delegate).isHealthy();
    }
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

    private class MonitoredMessageReader<T extends MessageLite> implements MessageReader<T> {
        private final MessageReader<T> delegate;

        public MonitoredMessageReader(MessageReader<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public T next() {
            T message = delegate.next();
            int messageSize = message.getSerializedSize();
            totalMessagesRead.incrementAndGet();
            totalBytesRead.addAndGet(messageSize);

            if (ThreadLocalRandom.current().nextInt(10) == 0) {
                recordThroughputSample(messageSize);
            }
            return message;
        }

        private void recordThroughputSample(int messageSize) {
            long currentSecond = Instant.now().getEpochSecond();

            messagesPerSecond.computeIfAbsent(currentSecond, k -> new AtomicLong(0))
                .addAndGet(10);
            bytesPerSecond.computeIfAbsent(currentSecond, k -> new AtomicLong(0))
                .addAndGet((long) messageSize * 10);

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
}