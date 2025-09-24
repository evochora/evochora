package org.evochora.datapipeline.api.services;

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
     * Gets activity information to display in the CLI status output.
     * Each service can decide what information is most relevant to show.
     * 
     * @return A string describing the current activity of the service
     */
    String getActivityInfo();
}
