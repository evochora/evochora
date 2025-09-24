package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.channels.IInputChannel;
import org.evochora.datapipeline.api.channels.IMonitorableChannel;
import org.evochora.datapipeline.api.channels.IOutputChannel;
import org.evochora.datapipeline.api.services.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

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
    protected Thread serviceThread;
    protected final Config options;
    protected final Map<String, List<Object>> resources;

    /**
     * Constructs an AbstractService with its dependencies.
     *
     * @param options   The service-specific configuration.
     * @param resources A map of all injected resource objects, keyed by port name.
     */
    public AbstractService(Config options, Map<String, List<Object>> resources) {
        this.options = options;
        this.resources = resources;
    }

    /**
     * Resets error counts for all channel bindings when the service starts.
     * This ensures error counts are reset only on service restart, not on every metrics collection.
     * Note: This method needs to be overridden by services that have access to the actual channel bindings.
     */
    protected void resetChannelBindingErrorCounts() {
        // Default implementation does nothing - services with channel bindings should override this
        // The ServiceManager will handle resetting error counts for channel bindings it manages
    }

    @Override
    public void start() {
        if (currentState.compareAndSet(State.STOPPED, State.RUNNING)) {
            // Reset error counts for all channel bindings when service starts
            resetChannelBindingErrorCounts();
            
            // Start service in separate thread
            this.serviceThread = new Thread(this::run, this.getClass().getSimpleName().toLowerCase());
            this.serviceThread.start();
        }
    }

    @Override
    public void stop() {
        if (currentState.compareAndSet(State.RUNNING, State.STOPPED) || 
            currentState.compareAndSet(State.PAUSED, State.STOPPED)) {
            // Wake up any waiting threads
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
            if (serviceThread != null && serviceThread.isAlive()) {
                serviceThread.interrupt();
            }
            log.info("Stopped service: {}", this.getClass().getSimpleName());
        }
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
        List<org.evochora.datapipeline.api.services.ChannelBindingStatus> channelBindingStatuses = new java.util.ArrayList<>();
        resources.forEach((portName, resourceList) -> {
            resourceList.forEach(resource -> {
                if (resource instanceof org.evochora.datapipeline.api.channels.IInputChannel) {
                    channelBindingStatuses.add(new org.evochora.datapipeline.api.services.ChannelBindingStatus(
                        portName,
                        org.evochora.datapipeline.api.services.Direction.INPUT,
                        determineBindingState(resource, org.evochora.datapipeline.api.services.Direction.INPUT),
                        0.0
                    ));
                } else if (resource instanceof org.evochora.datapipeline.api.channels.IOutputChannel) {
                    channelBindingStatuses.add(new org.evochora.datapipeline.api.services.ChannelBindingStatus(
                        portName,
                        org.evochora.datapipeline.api.services.Direction.OUTPUT,
                        determineBindingState(resource, org.evochora.datapipeline.api.services.Direction.OUTPUT),
                        0.0
                    ));
                }
            });
        });
        return new ServiceStatus(currentState.get(), channelBindingStatuses);
    }

    private org.evochora.datapipeline.api.services.BindingState determineBindingState(Object underlyingChannel, org.evochora.datapipeline.api.services.Direction direction) {
        if (underlyingChannel instanceof org.evochora.datapipeline.api.channels.IMonitorableChannel) {
            org.evochora.datapipeline.api.channels.IMonitorableChannel monitorableChannel =
                    (org.evochora.datapipeline.api.channels.IMonitorableChannel) underlyingChannel;
            
            long backlogSize = monitorableChannel.getBacklogSize();
            long capacity = monitorableChannel.getCapacity();
            
            if (direction == org.evochora.datapipeline.api.services.Direction.INPUT) {
                return backlogSize == 0 ? org.evochora.datapipeline.api.services.BindingState.WAITING : org.evochora.datapipeline.api.services.BindingState.ACTIVE;
            } else {
                boolean isFull = (capacity >= 0 && backlogSize >= capacity);
                return isFull ? org.evochora.datapipeline.api.services.BindingState.WAITING : org.evochora.datapipeline.api.services.BindingState.ACTIVE;
            }
        } else {
            return org.evochora.datapipeline.api.services.BindingState.ACTIVE;
        }
    }

    @Override
    public String getActivityInfo() {
        // Default implementation - subclasses can override
        return "";
    }

    /**
     * The main logic of the service, to be implemented by subclasses.
     * This method is called by the {@link #start()} method.
     */
    protected abstract void run();

    /**
     * Gets a single, required resource for a specific port.
     * This is a convenience method for services that expect exactly one resource on a given port.
     *
     * @param portName The logical port name.
     * @param <T>      The expected type of the resource.
     * @return The resource instance.
     * @throws IllegalStateException if no resource or more than one resource is configured for the port.
     */
    @SuppressWarnings("unchecked")
    protected <T> T getRequiredResource(String portName) {
        List<Object> resolved = resources.get(portName);
        if (resolved == null || resolved.isEmpty()) {
            throw new IllegalStateException("Required resource port '" + portName + "' has no configured resource.");
        }
        if (resolved.size() > 1) {
            throw new IllegalStateException("Required resource port '" + portName + "' expects a single resource, but " + resolved.size() + " were configured.");
        }
        return (T) resolved.get(0);
    }

    /**
     * Gets all resources for a specific port.
     * This is a convenience method for services that can handle multiple resources on a single logical port.
     *
     * @param portName The logical port name.
     * @param <T>      The expected type of the resources.
     * @return A stream of resource instances. Returns an empty stream if the port is not configured.
     */
    @SuppressWarnings("unchecked")
    protected <T> Stream<T> getResources(String portName) {
        return resources.getOrDefault(portName, Collections.emptyList())
                .stream()
                .map(resource -> (T) resource);
    }
}
