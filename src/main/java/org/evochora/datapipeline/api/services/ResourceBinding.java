package org.evochora.datapipeline.api.services;

import org.evochora.datapipeline.api.resources.IResource;

/**
 * Represents the connection between a service and a resource at a specific port.
 * <p>
 * This record contains only structural information about the binding. Dynamic
 * information like the binding's current state is obtained by calling
 * {@code resource.getState(usageType)}.
 *
 * @param portName   The logical port name within the service (e.g., "tickInput").
 * @param usageType  How the resource is being used (e.g., "queue-in", "storage-readonly").
 * @param service    Reference to the connected service.
 * @param resource   Reference to the connected resource.
 */
public record ResourceBinding(
    String portName,
    String usageType,
    IService service,
    IResource resource
) {
}