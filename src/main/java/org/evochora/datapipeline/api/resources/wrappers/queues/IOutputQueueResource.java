package org.evochora.datapipeline.api.resources.wrappers.queues;

import org.evochora.datapipeline.api.resources.IResource;

import java.util.concurrent.TimeUnit;

/**
 * Interface for queue-based resources that can accept data output.
 * <p>
 * This interface is specifically designed for message queue resources and provides
 * both non-blocking and timeout-based send operations.
 *
 * @param <T> The type of data this resource can accept.
 */
public interface IOutputQueueResource<T> extends IResource {
    
    /**
     * Attempts to send a single item to the queue without blocking.
     *
     * @param item The item to send
     * @return true if the item was sent successfully, false if the queue is full
     */
    boolean send(T item);
    
    /**
     * Attempts to send a single item to the queue, waiting up to the specified timeout.
     *
     * @param item    The item to send
     * @param timeout The maximum time to wait
     * @param unit    The time unit of the timeout argument
     * @return true if the item was sent successfully, false if the queue is still full after the timeout
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    boolean send(T item, long timeout, TimeUnit unit) throws InterruptedException;
}
