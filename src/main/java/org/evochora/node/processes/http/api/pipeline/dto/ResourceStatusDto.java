package org.evochora.node.processes.http.api.pipeline.dto;

import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A Data Transfer Object representing the status of a single resource.
 *
 * @param name    The name of the resource.
 * @param type    The class name of the resource implementation.
 * @param metrics A map of metrics if the resource is monitorable.
 * @param errors  A list of recent error messages.
 * @param healthy Whether the resource is healthy.
 */
public record ResourceStatusDto(
    String name,
    String type,
    Map<String, Number> metrics,
    List<String> errors,
    boolean healthy
) {
    /**
     * Factory method to create a DTO from a resource instance.
     *
     * @param name     The name of the resource.
     * @param resource The resource instance.
     * @return A new ResourceStatusDto instance.
     */
    public static ResourceStatusDto from(final String name, final IResource resource) {
        final String type = resource.getClass().getSimpleName();

        final Map<String, Number> metrics;
        final List<String> errorMessages;
        final boolean healthy;

        if (resource instanceof IMonitorable) {
            final IMonitorable monitorable = (IMonitorable) resource;
            metrics = monitorable.getMetrics();
            errorMessages = monitorable.getErrors().stream()
                    .map(OperationalError::message)
                    .collect(Collectors.toList());
            healthy = monitorable.isHealthy();
        } else {
            metrics = Collections.emptyMap();
            errorMessages = Collections.emptyList();
            healthy = true; // Non-monitorable resources are assumed healthy
        }

        return new ResourceStatusDto(name, type, metrics, errorMessages, healthy);
    }
}