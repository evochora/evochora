package org.evochora.datapipeline.api.services;

/**
 * Represents the lifecycle state of a service.
 */
public enum State {
    /**
     * The service is currently running and processing data.
     */
    RUNNING,

    /**
     * The service is temporarily paused and is not processing data.
     */
    PAUSED,

    /**
     * The service has been stopped and cannot be restarted.
     */
    STOPPED
}
