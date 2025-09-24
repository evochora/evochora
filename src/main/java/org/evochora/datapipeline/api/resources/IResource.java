package org.evochora.datapipeline.api.resources;

/**
 * Base interface for all resources in the data pipeline.
 * <p>
 * Resources are components that provide access to external systems, such as
 * message queues, databases, or file storage. This interface serves as a common
 * type for all resource implementations, allowing them to be managed by the
 * ServiceManager.
 */
public interface IResource {

    /**
     * The operational state of a resource for a specific usage context.
     */
    enum ResourceState {
        /**
         * The resource is functioning normally for this usage type.
         */
        ACTIVE,
        /**
         * The resource is temporarily busy or blocked (e.g., queue full/empty).
         */
        WAITING,
        /**
         * The resource has an error for this usage type.
         */
        FAILED
    }

    /**
     * Returns the current state of the resource for a specific usage context.
     * <p>
     * Different usage types may have different states. For example, a queue might
     * be WAITING for input (empty) but ACTIVE for output (has space).
     *
     * @param usageType The usage type (e.g., "queue-in", "storage-readonly")
     * @return The current ResourceState for this usage context
     */
    ResourceState getState(String usageType);
}