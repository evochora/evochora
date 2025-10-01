package org.evochora.node.processes.http.api.pipeline.dto;

import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.services.ResourceBinding;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A Data Transfer Object representing a resource binding. This is a serializable, immutable
 * representation of the internal {@link ResourceBinding} domain model, suitable for use in API responses.
 *
 * @param portName      The name of the service port the resource is bound to.
 * @param resourceName  The name of the bound resource.
 * @param usageType     The type of usage (e.g., "INPUT", "OUTPUT").
 * @param metrics       Metrics specific to this binding (e.g., messages produced/consumed).
 * @param errors        Errors specific to this binding.
 * @param healthy       Whether this binding is healthy.
 */
public record ResourceBindingDto(
    String portName,
    String resourceName,
    String usageType,
    Map<String, Number> metrics,
    List<String> errors,
    boolean healthy
) {
    /**
     * Factory method to create a DTO from a domain object.
     *
     * @param binding The internal ResourceBinding to convert.
     * @return A new ResourceBindingDto instance.
     */
    public static ResourceBindingDto from(final ResourceBinding binding) {
        final Map<String, Number> metrics;
        final List<String> errorMessages;
        final boolean healthy;

        // The binding's resource is the wrapped resource (e.g., MonitoredQueueProducer)
        // which may have its own metrics specific to this binding
        if (binding.resource() instanceof IMonitorable) {
            final IMonitorable monitorable = (IMonitorable) binding.resource();
            metrics = monitorable.getMetrics();
            errorMessages = monitorable.getErrors().stream()
                    .map(OperationalError::message)
                    .collect(Collectors.toList());
            healthy = monitorable.isHealthy();
        } else {
            metrics = Collections.emptyMap();
            errorMessages = Collections.emptyList();
            healthy = true; // Non-monitorable bindings are assumed healthy
        }

        return new ResourceBindingDto(
            binding.context().portName(),
            binding.context().resourceName(),
            binding.context().usageType(),
            metrics,
            errorMessages,
            healthy
        );
    }
}