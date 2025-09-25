package org.evochora.datapipeline.api.resources.wrappers.queues;

import org.evochora.datapipeline.api.resources.IResource;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Interface for queue-based resources that can provide data input capabilities.
 * <p>
 * This interface is specifically designed for message queue resources and provides
 * both non-blocking and timeout-based receive operations.
 *
 * @param <T> The type of data this resource can provide.
 */
public interface IInputQueueResource<T> extends IResource {
    
    /**
     * Attempts to receive a single item from the queue without blocking.
     *
     * @return An {@link Optional} containing the received item, or an empty Optional if no item is available.
     */
    Optional<T> receive();
    
    /**
     * Attempts to receive a single item from the queue, waiting up to the specified timeout.
     *
     * @param timeout The maximum time to wait
     * @param unit    The time unit of the timeout argument
     * @return An {@link Optional} containing the received item, or an empty Optional if no item is available within the timeout
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    Optional<T> receive(long timeout, TimeUnit unit) throws InterruptedException;
}
