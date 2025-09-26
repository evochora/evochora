package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.services.IService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An abstract base class for all services, providing common lifecycle management,
 * thread handling, and resource access utilities. Subclasses must implement the
 * {@link #run()} method to define their specific logic.
 */
public abstract class AbstractService implements IService {

    protected final Logger log = LoggerFactory.getLogger(this.getClass());
    protected final String serviceName;
    protected final Config options;
    protected final Map<String, List<IResource>> resources;
    private final AtomicReference<State> currentState = new AtomicReference<>(State.STOPPED);
    private final Object pauseLock = new Object();
    private Thread serviceThread;

    /**
     * Constructs an AbstractService with its configuration and resources.
     *
     * @param name      The name of the service instance.
     * @param options   The configuration for this service.
     * @param resources A map of resource ports to lists of resources.
     */
    protected AbstractService(String name, Config options, Map<String, List<IResource>> resources) {
        this.serviceName = name;
        this.options = options;
        this.resources = resources;
    }

    @Override
    public void start() {
        if (currentState.compareAndSet(State.STOPPED, State.RUNNING)) {
            serviceThread = new Thread(this::runService);
            serviceThread.setName(this.getClass().getSimpleName());
            serviceThread.start();
            log.info("{} started", this.getClass().getSimpleName());
        } else {
            log.warn("{} is already running or in an unstartable state: {}. Start command ignored.", this.getClass().getSimpleName(), getCurrentState());
        }
    }

    @Override
    public void stop() {
        State state = getCurrentState();
        if (state == State.RUNNING || state == State.PAUSED) {
            if (currentState.compareAndSet(state, State.STOPPED)) {
                if (state == State.PAUSED) {
                    synchronized (pauseLock) {
                        pauseLock.notifyAll();
                    }
                }
                if (serviceThread != null) {
                    serviceThread.interrupt();
                    try {
                        serviceThread.join(5000); // 5-second timeout
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("{} interrupted while waiting for service thread to stop", this.getClass().getSimpleName());
                    }
                }
                log.info("{} stopped", this.getClass().getSimpleName());
            }
        } else {
            log.warn("{} is not running or paused. Stop command ignored", this.getClass().getSimpleName());
        }
    }

    @Override
    public void pause() {
        if (currentState.compareAndSet(State.RUNNING, State.PAUSED)) {
            log.info("{} paused", this.getClass().getSimpleName());
        } else {
            log.warn("{} is not in a pausable state (e.g. stopped or already paused). Pause command ignored", this.getClass().getSimpleName());
        }
    }

    @Override
    public void resume() {
        if (currentState.compareAndSet(State.PAUSED, State.RUNNING)) {
            log.info("{} resumed", this.getClass().getSimpleName());
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        } else {
            log.warn("{} is not paused. Resume command ignored", this.getClass().getSimpleName());
        }
    }

    /**
     * Stops and then starts the service.
     */
    public void restart() {
        stop();
        try {
            if (serviceThread != null && serviceThread.isAlive()) {
                serviceThread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{} interrupted while waiting for service to stop before restarting", this.getClass().getSimpleName(), e);
            // Restore STOPPED state if interruption occurs
            currentState.set(State.STOPPED);
            return;
        }
        start();
    }

    @Override
    public State getCurrentState() {
        return currentState.get();
    }

    private void runService() {
        try {
            run();
        } catch (InterruptedException e) {
            log.info("Service thread interrupted, shutting down.");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Unhandled exception in service run loop. Service is now in an error state.", e);
            currentState.set(State.ERROR);
        } finally {
            // If the service stopped for any reason other than an error, set state to STOPPED.
            if (getCurrentState() != State.ERROR) {
                currentState.set(State.STOPPED);
            }
            log.info("Service thread for {} has terminated.", this.getClass().getSimpleName());
        }
    }

    /**
     * The main logic of the service, to be implemented by subclasses. This method
     * will be executed in a dedicated thread. It should periodically check for
     * pause and interruption status.
     *
     * @throws InterruptedException if the service thread is interrupted.
     */
    protected abstract void run() throws InterruptedException;

    /**
     * Blocks the current thread if the service is in the {@link State#PAUSED} state.
     * This method is intended to be called within the {@link #run()} loop of a service.
     * When the service is paused, this method will block until the service is resumed or stopped.
     *
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    protected void checkPause() throws InterruptedException {
        synchronized (pauseLock) {
            while (getCurrentState() == State.PAUSED) {
                log.debug("Service is paused, waiting...");
                pauseLock.wait();
                log.debug("Woke up from pause.");
            }
        }
    }

    /**
     * Gets a single required resource for a given port, ensuring it matches the expected type.
     *
     * @param portName     The name of the resource port.
     * @param expectedType The class of the expected resource type.
     * @param <T>          The expected type of the resource.
     * @return The single resource instance.
     * @throws IllegalStateException if the port is not configured, has no resources,
     *                               has more than one resource, or if the resource is of the wrong type.
     */
    protected <T> T getRequiredResource(String portName, Class<T> expectedType) {
        List<IResource> resourceList = resources.get(portName);
        if (resourceList == null) {
            throw new IllegalStateException("Resource port '" + portName + "' is not configured.");
        }
        if (resourceList.isEmpty()) {
            throw new IllegalStateException("Resource port '" + portName + "' has no configured resources, but exactly one is required.");
        }
        if (resourceList.size() > 1) {
            throw new IllegalStateException("Resource port '" + portName + "' has " + resourceList.size() + " resources, but exactly one is required.");
        }
        IResource resource = resourceList.get(0);
        if (!expectedType.isInstance(resource)) {
            throw new IllegalStateException("Resource at port '" + portName + "' is of type " + resource.getClass().getName() + ", but expected type is " + expectedType.getName());
        }
        return expectedType.cast(resource);
    }

    /**
     * Gets a list of all resources for a given port, ensuring they all match the expected type.
     *
     * @param portName     The name of the resource port.
     * @param expectedType The class of the expected resource type.
     * @param <T>          The expected type of the resources.
     * @return A list of resource instances, which may be empty if the port is not configured.
     * @throws IllegalStateException if any resource is not of the expected type.
     */
    protected <T> List<T> getResources(String portName, Class<T> expectedType) {
        List<IResource> resourceList = resources.getOrDefault(portName, java.util.Collections.emptyList());
        return resourceList.stream()
                .map(resource -> {
                    if (!expectedType.isInstance(resource)) {
                        throw new IllegalStateException("Resource at port '" + portName + "' is of type " + resource.getClass().getName() + ", but expected type is " + expectedType.getName());
                    }
                    return expectedType.cast(resource);
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Checks if a resource port has at least one resource configured.
     *
     * @param portName The name of the resource port.
     * @return {@code true} if the port has one or more resources, {@code false} otherwise.
     */
    protected boolean hasResource(String portName) {
        return resources.containsKey(portName) && !resources.get(portName).isEmpty();
    }
}