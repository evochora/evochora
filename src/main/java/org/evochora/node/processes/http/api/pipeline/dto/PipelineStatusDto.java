package org.evochora.node.processes.http.api.pipeline.dto;

import java.util.List;

/**
 * A Data Transfer Object representing the overall status of the data pipeline node.
 * This is the top-level object returned by the main status endpoint.
 *
 * @param nodeId      A unique identifier for the node, typically the hostname.
 * @param status      The overall status of the node (e.g., "RUNNING", "STOPPED", "DEGRADED").
 * @param activeRunId The run ID of the currently active simulation, or {@code null} if no simulation is running.
 * @param services    A list containing the status of each individual service.
 * @param resources   A list containing the status of each resource.
 */
public record PipelineStatusDto(
    String nodeId,
    String status,
    String activeRunId,
    List<ServiceStatusDto> services,
    List<ResourceStatusDto> resources
) {
}