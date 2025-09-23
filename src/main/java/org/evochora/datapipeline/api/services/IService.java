package org.evochora.datapipeline.api.services;

import org.evochora.datapipeline.core.InputChannelBinding;
import org.evochora.datapipeline.core.OutputChannelBinding;

/**
 * Defines the lifecycle and control interface for all services in the pipeline.
 */
public interface IService {

    /**
     * Starts the service. This method should be non-blocking and return immediately.
     * The service will begin its processing in a separate thread.
     */
    void start();

    /**
     * Stops the service permanently. The service cannot be restarted after being stopped.
     * This method should signal the service to clean up its resources and terminate.
     */
    void stop();

    /**
     * Pauses the service temporarily. The service should stop processing new data
     * but maintain its internal state.
     */
    void pause();

    /**
     * Resumes a paused service. The service will continue processing from where it left off.
     */
    void resume();

    /**
     * Gets the current status of the service.
     *
     * @return A {@link ServiceStatus} object containing detailed information about the
     *         service's state and its channel bindings.
     */
    ServiceStatus getServiceStatus();

    /**
     * Adds an input channel binding to a specific logical port of the service.
     * @param portName The logical name of the input port (e.g., "tickData").
     * @param binding The fully constructed, type-safe input channel binding.
     */
    void addInputChannel(String portName, InputChannelBinding<?> binding);

    /**
     * Adds an output channel binding to a specific logical port of the service.
     * @param portName The logical name of the output port.
     * @param binding The fully constructed, type-safe output channel binding.
     */
    void addOutputChannel(String portName, OutputChannelBinding<?> binding);
    
    /**
     * Gets activity information to display in the CLI status output.
     * Each service can decide what information is most relevant to show.
     * 
     * @return A string describing the current activity of the service
     */
    String getActivityInfo();
}
