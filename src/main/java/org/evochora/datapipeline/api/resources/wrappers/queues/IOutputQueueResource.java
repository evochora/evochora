package org.evochora.datapipeline.api.resources.wrappers.queues;

import org.evochora.datapipeline.api.resources.IResource;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * Interface for queue-based resources that can accept data output.
 * This interface is designed to be a close analog to {@link java.util.concurrent.BlockingQueue},
 * offering a variety of methods for adding elements with different blocking behaviors.
 *
 * @param <T> The type of data this resource can accept.
 */
public interface IOutputQueueResource<T> extends IResource {

    /**
     * Inserts the specified element into this queue if it is possible to do so immediately without violating
     * capacity restrictions, returning {@code true} upon success and {@code false} if no space is currently
     * available. This is a non-blocking operation.
     *
     * @param element the element to add
     * @return {@code true} if the element was added to this queue, else {@code false}
     */
    boolean offer(T element);

    /**
     * Inserts the specified element into this queue, waiting if necessary for space to become available.
     * This is a blocking operation.
     *
     * @param element the element to add
     * @throws InterruptedException if interrupted while waiting
     */
    void put(T element) throws InterruptedException;

    /**
     * Inserts the specified element into this queue, waiting up to the specified wait time if necessary
     * for space to become available.
     *
     * @param element the element to add
     * @param timeout how long to wait before giving up, in units of {@code unit}
     * @param unit a {@code TimeUnit} determining how to interpret the timeout parameter
     * @return {@code true} if successful, or {@code false} if the specified waiting time elapses before space is available
     * @throws InterruptedException if interrupted while waiting
     */
    boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Inserts all elements from the specified collection into this queue, waiting if necessary for space to become available.
     * This is a blocking operation.
     *
     * @param elements the collection of elements to add
     * @throws InterruptedException if interrupted while waiting
     */
    void putAll(Collection<T> elements) throws InterruptedException;

    /**
     * Inserts the specified elements into this queue if it is possible to do so
     * immediately without violating capacity restrictions, returning the number
     * of elements successfully added. When using a capacity-restricted queue,
     * this method is generally preferable to {@link #putAll(Collection)},
     * which may block for an non-blocking time if the queue is full.
     *
     * @param elements the collection of elements to add
     * @return the number of elements added to the queue
     * @throws NullPointerException if the specified collection or any of its elements are null
     */
    int offerAll(Collection<T> elements);
}