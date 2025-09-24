package org.evochora.datapipeline.api.resources;

/**
 * Provides context information to a resource during the dependency injection process.
 * <p>
 * This context allows a resource to understand how it is being used by a service,
 * enabling the creation of specialized wrappers or configurations. For example, a
 * resource might behave differently if it's used as an input ("queue-in") versus an
 * output ("storage-readonly").
 *
 * @param serviceName The name of the service requesting the resource.
 * @param portName    The logical port within the service that this resource will be connected to.
 * @param usageType   A string describing the intended use of the resource (e.g., "queue-in").
 */
public record ResourceContext(
    String serviceName,
    String portName,
    String usageType
) {
}