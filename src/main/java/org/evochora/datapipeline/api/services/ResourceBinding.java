package org.evochora.datapipeline.api.services;

import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.ResourceContext;

/**
 * Represents the connection between a service and a resource at a specific port.
 * <p>
 * This record contains structural information about the binding, including the
 * complete context that was used during dependency injection. Dynamic information
 * like the binding's current state is obtained by calling
 * {@code resource.getState(context.usageType())}.
 *
 * @param context  The complete context used during dependency injection.
 * @param service  Reference to the connected service.
 * @param resource Reference to the connected resource.
 */
public record ResourceBinding(
    ResourceContext context,
    IService service,
    IResource resource
) {
}