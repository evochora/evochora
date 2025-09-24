package org.evochora.datapipeline.api.resources;

import java.util.Optional;

/**
 * Represents a resource that can provide data to a service.
 *
 * @param <T> The type of data this resource provides.
 */
public interface IInputResource<T> extends IResource {
    /**
     * Attempts to receive a single item from the resource.
     *
     * @return An {@link Optional} containing the received item, or an empty Optional if no item is available.
     */
    Optional<T> receive();
}