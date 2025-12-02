package org.evochora.datapipeline.api.services;

import java.util.List;

import org.evochora.datapipeline.api.resources.OperationalError;

import java.util.Map;

/**
 * Represents the complete status of a service at a specific point in time.
 * <p>
 * This record provides a snapshot of the service's operational state, its metrics,
 * any errors, and the status of all its resource connections.
 *
 * @param state    The current state of the service (e.g., RUNNING, PAUSED).
 * @param healthy  Whether the service reports itself as healthy (from {@link IService#isHealthy()}).
 * @param metrics  A map of metrics for the service.
 * @param errors   A list of operational errors reported by the service.
 * @param resourceBindings A list of {@link ResourceBinding} objects, one for each of the
 *                 service's resource connections.
 */
public record ServiceStatus(
    IService.State state,
    boolean healthy,
    Map<String, Number> metrics,
    List<OperationalError> errors,
    List<ResourceBinding> resourceBindings
) {
}