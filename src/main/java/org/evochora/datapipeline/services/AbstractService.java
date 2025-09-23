package org.evochora.datapipeline.services;

import org.evochora.datapipeline.api.channels.IInputChannel;
import org.evochora.datapipeline.api.channels.IOutputChannel;
import org.evochora.datapipeline.api.services.*;
import org.evochora.datapipeline.core.InputChannelBinding;
import org.evochora.datapipeline.core.OutputChannelBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Map<String, List<InputChannelBinding<?>>> inputBindings = new ConcurrentHashMap<>();
    private final Map<String, List<OutputChannelBinding<?>>> outputBindings = new ConcurrentHashMap<>();

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
        // Create dynamic channel binding statuses
        List<ChannelBindingStatus> dynamicBindings = new ArrayList<>();
        inputBindings.values().stream()
                .flatMap(List::stream)
                .forEach(binding -> {
                    BindingState state = determineBindingState(binding.getUnderlyingChannel(), Direction.INPUT);
                    dynamicBindings.add(new ChannelBindingStatus(
                            binding.getChannelName(),
                            Direction.INPUT,
                            state,
                            0.0 // Note: This value is now determined by the ServiceManager's metrics collector
                    ));
                });
        outputBindings.values().stream()
                .flatMap(List::stream)
                .forEach(binding -> {
                    BindingState state = determineBindingState(binding.getUnderlyingChannel(), Direction.OUTPUT);
                    dynamicBindings.add(new ChannelBindingStatus(
                            binding.getChannelName(),
                            Direction.OUTPUT,
                            state,
                            0.0 // Note: This value is now determined by the ServiceManager's metrics collector
                    ));
                });
        return new ServiceStatus(currentState.get(), dynamicBindings);
    }
    

    @Override
    public String getActivityInfo() {
        // Default implementation - subclasses can override
        return "";
    }
    
    /**
     * Determines the binding state based on real-time channel metrics.
     * 
     * @param binding The channel binding to analyze
     * @return The current binding state (ACTIVE or WAITING)
     */
    private BindingState determineBindingState(Object underlyingChannel, Direction direction) {
        if (underlyingChannel instanceof org.evochora.datapipeline.api.channels.IMonitorableChannel) {
            org.evochora.datapipeline.api.channels.IMonitorableChannel monitorableChannel =
                    (org.evochora.datapipeline.api.channels.IMonitorableChannel) underlyingChannel;
            
            long backlogSize = monitorableChannel.getBacklogSize();
            long capacity = monitorableChannel.getCapacity();
            
            if (direction == Direction.INPUT) {
                // INPUT: WAITING if getBacklogSize() == 0, otherwise ACTIVE
                return backlogSize == 0 ? BindingState.WAITING : BindingState.ACTIVE;
            } else {
                // OUTPUT: WAITING if getCapacity() >= 0 and getBacklogSize() >= getCapacity(), otherwise ACTIVE
                boolean isFull = (capacity >= 0 && backlogSize >= capacity);
                return isFull ? BindingState.WAITING : BindingState.ACTIVE;
            }
        } else {
            // If not monitorable, default to ACTIVE
            return BindingState.ACTIVE;
        }
    }

    /**
     * The main logic of the service, to be implemented by subclasses.
     * This method is called by the {@link #start()} method.
     */
    protected abstract void run();

    @Override
    public void addInputChannel(String portName, InputChannelBinding<?> binding) {
        throw new UnsupportedOperationException(
                String.format("Service '%s' does not support input channels.", this.getClass().getSimpleName()));
    }

    @Override
    public void addOutputChannel(String portName, OutputChannelBinding<?> binding) {
        throw new UnsupportedOperationException(
                String.format("Service '%s' does not support output channels.", this.getClass().getSimpleName()));
    }

    /**
     * Registers an input channel binding with the service. Concrete service implementations
     * that support inputs should override {@link #addInputChannel(String, InputChannelBinding)}
     * and call this method to store the binding.
     *
     * @param portName The logical port name.
     * @param binding The input channel binding.
     */
    protected void registerInputChannel(String portName, InputChannelBinding<?> binding) {
        inputBindings.computeIfAbsent(portName, k -> new ArrayList<>()).add(binding);
    }

    /**
     * Registers an output channel binding with the service. Concrete service implementations
     * that support outputs should override {@link #addOutputChannel(String, OutputChannelBinding)}
     * and call this method to store the binding.
     *
     * @param portName The logical port name.
     * @param binding The output channel binding.
     */
    protected void registerOutputChannel(String portName, OutputChannelBinding<?> binding) {
        outputBindings.computeIfAbsent(portName, k -> new ArrayList<>()).add(binding);
    }

    /**
     * Gets a single, required input channel for a specific port.
     * This is a convenience method for services that expect exactly one channel on a given port.
     *
     * @param portName The logical port name.
     * @param <T> The expected message type of the channel.
     * @return The {@link IInputChannel} instance.
     * @throws IllegalStateException if no channel or more than one channel is configured for the port.
     */
    @SuppressWarnings("unchecked") // Cast is safe due to configuration-time checks and generic constraints.
    protected <T> IInputChannel<T> getRequiredInputChannel(String portName) {
        List<InputChannelBinding<?>> bindings = inputBindings.get(portName);
        if (bindings == null || bindings.isEmpty()) {
            throw new IllegalStateException("Required input port '" + portName + "' has no configured channel.");
        }
        if (bindings.size() > 1) {
            throw new IllegalStateException("Required input port '" + portName + "' expects a single channel, but " + bindings.size() + " were configured.");
        }
        return (IInputChannel<T>) bindings.get(0);
    }

    /**
     * Gets all input channels for a specific port.
     * This method is for services that can handle multiple channels on a single logical port.
     *
     * @param portName The logical port name.
     * @param <T> The expected message type of the channels.
     * @return A stream of {@link IInputChannel} instances. Returns an empty stream if the port is not configured.
     */
    @SuppressWarnings("unchecked") // Cast is safe due to configuration-time checks and generic constraints.
    protected <T> Stream<IInputChannel<T>> getInputChannels(String portName) {
        return inputBindings.getOrDefault(portName, Collections.emptyList())
                .stream()
                .map(binding -> (IInputChannel<T>) binding);
    }

    /**
     * Gets a single, required output channel for a specific port.
     * This is a convenience method for services that expect exactly one channel on a given port.
     *
     * @param portName The logical port name.
     * @param <T> The expected message type of the channel.
     * @return The {@link IOutputChannel} instance.
     * @throws IllegalStateException if no channel or more than one channel is configured for the port.
     */
    @SuppressWarnings("unchecked") // Cast is safe due to configuration-time checks and generic constraints.
    protected <T> IOutputChannel<T> getRequiredOutputChannel(String portName) {
        List<OutputChannelBinding<?>> bindings = outputBindings.get(portName);
        if (bindings == null || bindings.isEmpty()) {
            throw new IllegalStateException("Required output port '" + portName + "' has no configured channel.");
        }
        if (bindings.size() > 1) {
            throw new IllegalStateException("Required output port '" + portName + "' expects a single channel, but " + bindings.size() + " were configured.");
        }
        return (IOutputChannel<T>) bindings.get(0);
    }

    /**
     * Gets all output channels for a specific port.
     * This method is for services that can handle multiple channels on a single logical port.
     *
     * @param portName The logical port name.
     * @param <T> The expected message type of the channels.
     * @return A stream of {@link IOutputChannel} instances. Returns an empty stream if the port is not configured.
     */
    @SuppressWarnings("unchecked") // Cast is safe due to configuration-time checks and generic constraints.
    protected <T> Stream<IOutputChannel<T>> getOutputChannels(String portName) {
        return outputBindings.getOrDefault(portName, Collections.emptyList())
                .stream()
                .map(binding -> (IOutputChannel<T>) binding);
    }
}
