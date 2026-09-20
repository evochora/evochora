package org.evochora.datapipeline;

import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.services.IService;

import com.typesafe.config.Config;

/**
 * A service that finishes by itself at the moment it is asked to stop: it is found running, and the
 * stop meets a service that has stopped already and rejects it, as a one-shot service does that
 * completes between the two.
 */
public class SelfStoppingTestService implements IService {

    private final String name;
    private volatile State state = State.STOPPED;

    /**
     * Creates the service the way the service manager creates every service.
     *
     * @param name The service's name
     * @param options Its options, unused
     * @param resources Its resources, unused
     */
    public SelfStoppingTestService(String name, Config options, Map<String, List<IResource>> resources) {
        this.name = name;
    }

    @Override
    public void start() {
        state = State.RUNNING;
    }

    @Override
    public void stop() {
        state = State.STOPPED;
        throw new IllegalStateException("Cannot stop service '" + name + "' as it is in state STOPPED");
    }

    @Override
    public void pause() {
        state = State.PAUSED;
    }

    @Override
    public void resume() {
        state = State.RUNNING;
    }

    @Override
    public State getCurrentState() {
        return state;
    }

    @Override
    public ShutdownPhase getShutdownPhase() {
        return ShutdownPhase.WAITING;
    }
}
