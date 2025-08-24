package org.evochora.server;

/**
 * Basic lifecycle controls for long-running services.
 */
public interface IControllable {
    void start();
    void pause();
    void resume();
    void shutdown();
    boolean isRunning();
    boolean isPaused();
}


