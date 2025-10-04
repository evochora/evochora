package org.evochora.datapipeline.resources.storage.wrappers;

import com.google.protobuf.MessageLite;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.IStorageWriteResource;
import org.evochora.datapipeline.api.resources.storage.MessageWriter;
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

public class MonitoredStorageWriter extends AbstractResource
    implements IStorageWriteResource, IWrappedResource, IMonitorable {

    private final IStorageWriteResource delegate;
    private final ResourceContext context;

    private final AtomicLong totalMessagesWritten = new AtomicLong(0);
    private final AtomicLong totalBytesWritten = new AtomicLong(0);
    private final AtomicLong writeOperations = new AtomicLong(0);
    private final AtomicLong writeErrors = new AtomicLong(0);

    private final ConcurrentHashMap<Long, AtomicLong> messagesPerSecond = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> bytesPerSecond = new ConcurrentHashMap<>();
    private final int throughputWindowSeconds;

    public MonitoredStorageWriter(IStorageWriteResource delegate, ResourceContext context) {
    super(String.format("monitored-writer(%s)", delegate.getResourceName()), ConfigFactory.parseMap(context.parameters()));
        this.delegate = delegate;
        this.context = context;
        this.throughputWindowSeconds = getOptions().hasPath("throughputWindowSeconds")
            ? getOptions().getInt("throughputWindowSeconds")
            : 60;
    }

    @Override
    public MessageWriter openWriter(String key) throws IOException {
        writeOperations.incrementAndGet();
        try {
            MessageWriter delegateWriter = delegate.openWriter(key);
            return new MonitoredMessageWriter(delegateWriter);
        } catch (IOException e) {
            writeErrors.incrementAndGet();
            throw e;
        }
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
            "messages_written", totalMessagesWritten.get(),
            "bytes_written", totalBytesWritten.get(),
            "write_operations", writeOperations.get(),
            "throughput_msgs_per_sec", (double) messagesInWindow / throughputWindowSeconds,
            "throughput_bytes_per_sec", (double) bytesInWindow / throughputWindowSeconds,
            "errors", writeErrors.get()
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

    private class MonitoredMessageWriter implements MessageWriter {
        private final MessageWriter delegate;

        public MonitoredMessageWriter(MessageWriter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void writeMessage(MessageLite message) throws IOException {
            try {
                delegate.writeMessage(message);

                int messageSize = message.getSerializedSize();
                totalMessagesWritten.incrementAndGet();
                totalBytesWritten.addAndGet(messageSize);

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