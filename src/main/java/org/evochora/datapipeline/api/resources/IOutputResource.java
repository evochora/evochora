package org.evochora.datapipeline.api.resources;

/**
 * Represents a resource that can accept data from a service.
 *
 * @param <T> The type of data this resource accepts.
 */
public interface IOutputResource<T> extends IResource {
    /**
     * Sends a single item to the resource.
     *
     * @param item The item to send.
     * @return true if the item was sent successfully, false otherwise.
     */
    boolean send(T item);
}