package org.evochora.server.queue;

import org.evochora.server.contracts.IQueueMessage;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-memory implementation of the tick message queue.
 * Uses a LinkedBlockingQueue with configurable element capacity.
 */
public final class InMemoryTickQueue implements ITickMessageQueue {

    private final LinkedBlockingQueue<IQueueMessage> delegate;
    private final int maxMessageCount;

    /**
     * Creates a new in-memory tick queue with the specified element capacity.
     *
     * @param maxMessageCount the maximum number of messages the queue can hold
     */
    public InMemoryTickQueue(int maxMessageCount) {
        this.maxMessageCount = maxMessageCount;
        this.delegate = new LinkedBlockingQueue<>(maxMessageCount);
    }


    @Override
    public void put(IQueueMessage message) throws InterruptedException {
        delegate.put(message);
    }

    @Override
    public IQueueMessage take() throws InterruptedException {
        return delegate.take();
    }

    @Override
    public IQueueMessage poll(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.poll(timeout, unit);
    }

    @Override
    public IQueueMessage poll() {
        return delegate.poll();
    }

    @Override
    public int size() {
        return delegate.size();
    }


    /**
     * Checks if the queue can accept another message.
     *
     * @return true if there is space for another message
     */
    public boolean canAcceptMessage() {
        return delegate.remainingCapacity() > 0;
    }

    /**
     * Attempts to add a message to the queue with a short timeout.
     * Used by SimulationEngine to detect if the queue is full without blocking.
     *
     * @param message the message to add
     * @return true if the message was added, false if the queue is full
     */
    public boolean tryPut(IQueueMessage message) {
        try {
            return delegate.offer(message, 1, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Gets the current number of messages in the queue.
     *
     * @return the current message count
     */
    public int getCurrentMessageCount() {
        return delegate.size();
    }

    /**
     * Gets the maximum number of messages the queue can hold.
     *
     * @return the maximum message count
     */
    public int getMaxMessageCount() {
        return maxMessageCount;
    }


}


