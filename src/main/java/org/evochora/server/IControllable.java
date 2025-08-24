package org.evochora.server;

public interface IControllable {
    void pause();
    void resume();
    void exit();
    boolean isPaused();
    boolean isRunning();
}
