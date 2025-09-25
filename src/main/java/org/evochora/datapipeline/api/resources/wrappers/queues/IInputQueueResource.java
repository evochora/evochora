package org.evochora.datapipeline.api.resources.wrappers.queues;

import org.evochora.datapipeline.api.resources.IResource;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Interface for queue-based resources that can provide data input capabilities.
 * This interface is designed to be a close analog to {@link java.util.concurrent.BlockingQueue},
 * offering a variety of methods for retrieving elements with different blocking behaviors.
 *
 * @param <T> The type of data this resource can provide.
 */
public interface IInputQueueResource<T> extends IResource {

    /**
     * Retrieves and removes the head of this queue, or returns {@link Optional#empty()} if this queue is empty.
     * This is a non-blocking operation.
     *
     * @return an {@link Optional} containing the head of this queue, or {@link Optional#empty()} if this queue is empty
     */
    Optional<T> poll();

    /**
     * Retrieves and removes the head of this queue, waiting if necessary until an element becomes available.
     * This is a blocking operation.
     *
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     */
    T take() throws InterruptedException;

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified wait time if necessary
     * for an element to become available.
     *
     * @param timeout how long to wait before giving up, in units of {@code unit}
     * @param unit a {@code TimeUnit} determining how to interpret the timeout parameter
     * @return an {@link Optional} containing the head of this queue, or {@link Optional#empty()} if the specified
     *         waiting time elapses before an element is available
     * @throws InterruptedException if interrupted while waiting
     */
    Optional<T> poll(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Removes at most the given number of available elements from this queue and adds them into the given collection.
     * A non-blocking version of {@link #drainTo(Collection, int, long, TimeUnit)}.
     *
     * @param collection the collection to drain elements into
     * @param maxElements the maximum number of elements to drain
     * @return the number of elements transferred
     */
    int drainTo(Collection<? super T> collection, int maxElements);

    /**
     * Drains a batch of elements from the queue into the given collection, waiting up to the
     * specified time if necessary for elements to become available.
     *
     * @param collection the collection to drain elements into
     * @param maxElements the maximum number of elements to drain
     * @param timeout how long to wait before giving up, in units of {@code unit}
     * @param unit a {@code TimeUnit} determining how to interpret the timeout parameter
     * @return the number of elements transferred
     * @throws InterruptedException if interrupted while waiting
     */
    int drainTo(Collection<? super T> collection, int maxElements, long timeout, TimeUnit unit) throws InterruptedException;
}