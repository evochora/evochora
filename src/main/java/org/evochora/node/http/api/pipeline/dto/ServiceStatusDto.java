package org.evochora.node.http.api.pipeline.dto;

import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.services.ServiceStatus;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A Data Transfer Object representing the status of a single service. This is a serializable,
 * immutable representation of the internal {@link ServiceStatus} domain model.
 *
 * @param name             The name of the service.
 * @param state            The current state of the service (e.g., "RUNNING", "STOPPED").
 * @param metrics          A map of metrics associated with the service.
 * @param errors           A list of recent error messages.
 * @param resourceBindings A list of resource bindings for the service.
 */
public record ServiceStatusDto(
    String name,
    String state,
    Map<String, Number> metrics,
    List<String> errors,
    List<ResourceBindingDto> resourceBindings
) {
    /**
     * Factory method to create a DTO from a domain object.
     *
     * @param name   The name of the service.
     * @param status The internal ServiceStatus to convert.
     * @return A new ServiceStatusDto instance.
     */
    public static ServiceStatusDto from(final String name, final ServiceStatus status) {
        // Use direct record accessors (e.g., status.resourceBindings()) and map errors to strings.
        final List<ResourceBindingDto> bindings = status.resourceBindings().stream()
            .map(ResourceBindingDto::from)
            .collect(Collectors.toList());

        final List<String> errorMessages = status.errors().stream()
            .map(OperationalError::message)
            .collect(Collectors.toList());

        return new ServiceStatusDto(
            name,
            status.state().name(),
            status.metrics(),
            errorMessages,
            bindings
        );
    }
}