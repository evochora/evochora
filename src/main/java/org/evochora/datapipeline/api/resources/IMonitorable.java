package org.evochora.datapipeline.api.resources;

import java.util.List;
import java.util.Map;

/**
 * An interface for components that can be monitored.
 * <p>
 * This interface provides a standard way to retrieve metrics, errors, and health status
 * from various components in the data pipeline, such as services, resources, or
 * resource bindings.
 */
public interface IMonitorable {

    /**
     * Returns a map of metrics for the component.
     * <p>
     * The keys are metric names (e.g., "messages_processed", "queue_size") and the
     * values are the corresponding numeric values. The format is designed to be
     * compatible with monitoring systems like Prometheus.
     *
     * @return A map of metric names to their current values.
     */
    Map<String, Number> getMetrics();

    /**
     * Returns a list of operational errors that have occurred in the component.
     * <p>
     * This list may be cleared by calling {@link #clearErrors()}.
     *
     * @return A list of {@link OperationalError}s.
     */
    List<OperationalError> getErrors();

    /**
     * Clears the list of operational errors.
     * <p>
     * This is typically an administrative action performed via the CLI after
     * an operator has investigated the errors.
     */
    void clearErrors();

    /**
     * Indicates whether the component is currently operational.
     *
     * @return true if the component is healthy, false if it is in a degraded or failed state.
     */
    boolean isHealthy();
}