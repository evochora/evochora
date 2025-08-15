package org.evochora.server.queue;

import org.evochora.server.contracts.IQueueMessage;
import org.evochora.server.setup.Config;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory queue backed by LinkedBlockingQueue with a soft byte-based capacity.
 * The internal queue is bounded by a computed element count derived from {@link Config#getMaxQueueBytes()}.
 */
public final class InMemoryTickQueue implements ITickMessageQueue {

    private final LinkedBlockingQueue<IQueueMessage> delegate;
    private final AtomicLong approximateBytesUsed = new AtomicLong(0);
    private final long maxQueueBytes;

    /**
     * Constructs a queue with capacity inferred from a byte budget.
     * We approximate message sizes conservatively:
     * - ProgramArtifactMessage: ~64 KB
     * - WorldStateMessage: configured as ~1 MB by default
     * This is a heuristic to bound memory; precise sizing is complex without off-heap measurement.
     */
    public InMemoryTickQueue(Config config) {
        this.maxQueueBytes = Math.max(16 * 1024 * 1024L, config.getMaxQueueBytes());
        int elementCapacity = Math.max(64, (int) Math.min(Integer.MAX_VALUE, this.maxQueueBytes / 1_000_000L));
        this.delegate = new LinkedBlockingQueue<>(elementCapacity);
    }

    @Override
    public void put(IQueueMessage message) throws InterruptedException {
        // Backpressure on bytes as best-effort: block while approx bytes >= max
        long estimatedSize = estimateSizeBytes(message);
        while ((approximateBytesUsed.get() + estimatedSize) > maxQueueBytes) {
            TimeUnit.MILLISECONDS.sleep(1);
        }
        delegate.put(message);
        approximateBytesUsed.addAndGet(estimatedSize);
    }

    @Override
    public IQueueMessage take() throws InterruptedException {
        IQueueMessage msg = delegate.take();
        approximateBytesUsed.addAndGet(-estimateSizeBytes(msg));
        return msg;
    }

    @Override
    public int size() {
        return delegate.size();
    }

    private long estimateSizeBytes(IQueueMessage message) {
        // Heuristic: 64 KB for artifacts, 1 MB for world states, 4 KB fallback
        String type = message.getClass().getSimpleName();
        if (type.equals("ProgramArtifactMessage")) return 64 * 1024L;
        if (type.equals("WorldStateMessage")) return 1_000_000L;
        return 4 * 1024L;
    }
}


