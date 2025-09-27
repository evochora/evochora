package org.evochora.node.http.api.pipeline.dto;

import org.evochora.datapipeline.api.services.ResourceBinding;

/**
 * A Data Transfer Object representing a resource binding. This is a serializable, immutable
 * representation of the internal {@link ResourceBinding} domain model, suitable for use in API responses.
 *
 * @param portName      The name of the service port the resource is bound to.
 * @param resourceName  The name of the bound resource.
 * @param usageType     The type of usage (e.g., "INPUT", "OUTPUT").
 */
public record ResourceBindingDto(
    String portName,
    String resourceName,
    String usageType
) {
    /**
     * Factory method to create a DTO from a domain object.
     *
     * @param binding The internal ResourceBinding to convert.
     * @return A new ResourceBindingDto instance.
     */
    public static ResourceBindingDto from(final ResourceBinding binding) {
        // Use direct record accessors (e.g., binding.context()) instead of getters.
        return new ResourceBindingDto(
            binding.context().portName(),
            binding.context().resourceName(),
            binding.context().usageType()
        );
    }
}