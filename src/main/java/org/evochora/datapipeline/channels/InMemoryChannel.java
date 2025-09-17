package org.evochora.datapipeline.channels;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.channels.IInputChannel;
import org.evochora.datapipeline.api.channels.IMonitorableChannel;
import org.evochora.datapipeline.api.channels.IOutputChannel;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * A thread-safe, in-memory message channel with a fixed capacity.
 * <p>
 * This channel is implemented using a {@link java.util.concurrent.ArrayBlockingQueue}
 * and is designed for high-performance, in-process communication between services.
 * Its capacity is configurable via a {@link com.typesafe.config.Config} object.
 *
 * @param <T> The type of message this channel will hold.
 */
public class InMemoryChannel<T> implements IInputChannel<T>, IOutputChannel<T>, IMonitorableChannel {

    private final BlockingQueue<T> queue;
    private final int capacity;

    /**
     * Constructs an InMemoryChannel based on the provided configuration.
     * <p>
     * The configuration is expected to contain a "capacity" key. If the key is not present,
     * a default capacity of 1000 will be used.
     *
     * @param options A Config object containing the channel's settings, e.g., 'capacity'.
     */
    public InMemoryChannel(Config options) {
        this.capacity = options.hasPath("capacity") ? options.getInt("capacity") : 1000;
        this.queue = new ArrayBlockingQueue<>(this.capacity);
    }

    @Override
    public T read() throws InterruptedException {
        return queue.take();
    }

    @Override
    public void write(T message) throws InterruptedException {
        queue.put(message);
    }

    @Override
    public long getQueueSize() {
        return queue.size();
    }

    @Override
    public long getCapacity() {
        return this.capacity;
    }
}
