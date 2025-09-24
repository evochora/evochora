package org.evochora.datapipeline.api.services;

import org.evochora.datapipeline.api.resources.IMonitorable;

/**
 * Represents the connection between a service and a resource at a specific port.
 * <p>
 * This record provides status and monitoring information for a single
 * service-to-resource link. It can optionally implement {@link IMonitorable}
 * to provide detailed, per-binding metrics.
 *
 * @param portName      The logical port name within the service (e.g., "tickInput").
 * @param resourceType  The type or category of the connected resource (e.g., "InMemoryQueue").
 * @param state         The current state of the binding.
 * @param throughput    An optional metric for data transfer rate (e.g., messages/sec).
 */
public record ResourceBinding(
    String portName,
    String resourceType,
    State state,
    double throughput
) implements IMonitorable {

    /**
     * Represents the operational state of a resource binding.
     */
    public enum State {
        /**
         * The resource is connected and functioning normally.
         */
        ACTIVE,
        /**
         * The service is waiting for the resource to become available (e.g., queue is full/empty).
         */
        WAITING,
        /**
         * The connection to the resource has failed.
         */
        FAILED
    }

    // Default implementation for IMonitorable for simplicity.
    // A concrete implementation could provide more detailed metrics.
    @Override
    public java.util.Map<String, Number> getMetrics() {
        return java.util.Map.of("throughput", throughput);
    }

    @Override
    public java.util.List<org.evochora.datapipeline.api.resources.OperationalError> getErrors() {
        return java.util.Collections.emptyList();
    }

    @Override
    public void clearErrors() {
        // No-op
    }

    @Override
    public boolean isHealthy() {
        return state != State.FAILED;
    }
}