package org.evochora.datapipeline.api.services;

import java.util.List;

/**
 * Represents the complete status of a service at a specific point in time.
 * <p>
 * This record provides a snapshot of the service's operational state and the
 * status of all its resource connections.
 *
 * @param state    The current state of the service (e.g., RUNNING, PAUSED).
 * @param bindings A list of {@link ResourceBinding} objects, one for each of the
 *                 service's resource connections.
 */
public record ServiceStatus(
    IService.State state,
    List<ResourceBinding> bindings
) {
}