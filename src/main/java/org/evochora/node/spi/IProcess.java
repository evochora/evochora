package org.evochora.node.spi;

/**
 * Defines the contract for a long-running, manageable background process within the Node.
 * Each process must be self-contained and expose methods to control its lifecycle.
 */
public interface IProcess {

    /**
     * Starts the process. This method should be non-blocking if the process runs continuously.
     * For example, it might start a new thread or submit a task to an executor service.
     */
    void start();

    /**
     * Stops the process gracefully. This method should ensure that all resources are released
     * and the process terminates cleanly.
     */
    void stop();
}