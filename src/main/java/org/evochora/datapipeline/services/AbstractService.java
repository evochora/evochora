package org.evochora.datapipeline.services;

import org.evochora.datapipeline.api.channels.IInputChannel;
import org.evochora.datapipeline.api.channels.IOutputChannel;
import org.evochora.datapipeline.api.services.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An abstract base class for services, providing common lifecycle management and channel wiring.
 * This class handles the state transitions (RUNNING, PAUSED, STOPPED) and provides
 * a thread-safe mechanism for pausing and resuming the service's execution.
 */
public abstract class AbstractService implements IService {

    protected final Logger log = LoggerFactory.getLogger(this.getClass());
    protected final AtomicReference<State> currentState = new AtomicReference<>(State.STOPPED);
    protected final Object pauseLock = new Object();
    protected volatile boolean paused = false;

    protected final List<ChannelBindingStatus> channelBindings = new ArrayList<>();

    @Override
    public void start() {
        if (currentState.compareAndSet(State.STOPPED, State.RUNNING)) {
            log.info("Started service: {}", this.getClass().getSimpleName());
            // Start service in separate thread
            Thread serviceThread = new Thread(this::run, this.getClass().getSimpleName().toLowerCase());
            serviceThread.start();
        }
    }

    @Override
    public void stop() {
        currentState.set(State.STOPPED);
        // Wake up any waiting threads
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        log.info("Stopped service: {}", this.getClass().getSimpleName());
    }

    @Override
    public void pause() {
        if (currentState.compareAndSet(State.RUNNING, State.PAUSED)) {
            paused = true;
            log.info("Paused service: {}", this.getClass().getSimpleName());
        }
    }

    @Override
    public void resume() {
        if (currentState.compareAndSet(State.PAUSED, State.RUNNING)) {
            synchronized (pauseLock) {
                paused = false;
                pauseLock.notifyAll();
            }
            log.info("Resumed service: {}", this.getClass().getSimpleName());
        }
    }

    @Override
    public ServiceStatus getServiceStatus() {
        return new ServiceStatus(currentState.get(), new ArrayList<>(channelBindings));
    }

    /**
     * The main logic of the service, to be implemented by subclasses.
     * This method is called by the {@link #start()} method.
     */
    protected abstract void run();

    /**
     * Adds an input channel to the service. This method is called by the ServiceManager during wiring.
     *
     * @param name    The name of the channel.
     * @param channel The input channel instance.
     */
    public void addInputChannel(String name, IInputChannel<?> channel) {
        // To be implemented by subclasses that need input channels
    }

    /**
     * Adds an output channel to the service. This method is called by the ServiceManager during wiring.
     *
     * @param name    The name of the channel.
     * @param channel The output channel instance.
     */
    public void addOutputChannel(String name, IOutputChannel<?> channel) {
        // To be implemented by subclasses that need output channels
    }
}
