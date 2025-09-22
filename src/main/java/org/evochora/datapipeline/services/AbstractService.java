package org.evochora.datapipeline.services;

import org.evochora.datapipeline.api.channels.IInputChannel;
import org.evochora.datapipeline.api.channels.IOutputChannel;
import org.evochora.datapipeline.api.services.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    
    // Track underlying channel instances by name for dynamic status determination
    private final Map<String, Object> underlyingChannels = new HashMap<>();

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
        if (currentState.get() == State.STOPPED) {
            // Reset error counts for all channel bindings when service starts
            resetChannelBindingErrorCounts();
            
            // Start service in separate thread
            Thread serviceThread = new Thread(() -> {
                // Set state to RUNNING when the service actually starts processing
                currentState.set(State.RUNNING);
                run();
            }, this.getClass().getSimpleName().toLowerCase());
            serviceThread.start();
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
        for (ChannelBindingStatus binding : channelBindings) {
            BindingState dynamicState = determineBindingState(binding);
            dynamicBindings.add(new ChannelBindingStatus(
                binding.channelName(),
                binding.direction(),
                dynamicState,
                binding.messagesPerSecond()
            ));
        }
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
    private BindingState determineBindingState(ChannelBindingStatus binding) {
        // Find the underlying channel from our stored channels
        Object underlyingChannel = findUnderlyingChannel(binding.channelName());
        
        if (underlyingChannel instanceof org.evochora.datapipeline.api.channels.IMonitorableChannel) {
            org.evochora.datapipeline.api.channels.IMonitorableChannel monitorableChannel = 
                (org.evochora.datapipeline.api.channels.IMonitorableChannel) underlyingChannel;
            
            long backlogSize = monitorableChannel.getBacklogSize();
            long capacity = monitorableChannel.getCapacity();
            
            if (binding.direction() == Direction.INPUT) {
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
     * Finds the underlying channel instance for a given channel name.
     * If the stored channel is a ChannelBinding wrapper, extracts the real underlying channel.
     * 
     * @param channelName The name of the channel
     * @return The underlying channel object, or null if not found
     */
    private Object findUnderlyingChannel(String channelName) {
        Object channel = underlyingChannels.get(channelName);
        
        // If it's a ChannelBinding wrapper, extract the real underlying channel
        if (channel instanceof org.evochora.datapipeline.core.InputChannelBinding) {
            return ((org.evochora.datapipeline.core.InputChannelBinding<?>) channel).getDelegate();
        } else if (channel instanceof org.evochora.datapipeline.core.OutputChannelBinding) {
            return ((org.evochora.datapipeline.core.OutputChannelBinding<?>) channel).getDelegate();
        }
        
        return channel;
    }

    /**
     * The main logic of the service, to be implemented by subclasses.
     * This method is called by the {@link #start()} method.
     */
    protected abstract void run();

    /**
     * Adds an input channel to the service. This method is called by the ServiceManager during wiring.
     * The channel parameter is now a ChannelBinding wrapper that provides monitoring capabilities.
     *
     * @param name    The name of the channel.
     * @param channel The input channel binding wrapper.
     */
    public void addInputChannel(String name, IInputChannel<?> channel) {
        // Store the underlying channel instance for dynamic status determination
        underlyingChannels.put(name, channel);
        
        // Create channel binding status for monitoring
        channelBindings.add(new ChannelBindingStatus(
            name,
            Direction.INPUT,
            BindingState.ACTIVE, // Will be updated dynamically in getServiceStatus()
            0.0 // Will be updated by metrics collection
        ));
    }

    /**
     * Adds an output channel to the service. This method is called by the ServiceManager during wiring.
     * The channel parameter is now a ChannelBinding wrapper that provides monitoring capabilities.
     *
     * @param name    The name of the channel.
     * @param channel The output channel binding wrapper.
     */
    public void addOutputChannel(String name, IOutputChannel<?> channel) {
        // Store the underlying channel instance for dynamic status determination
        underlyingChannels.put(name, channel);
        
        // Create channel binding status for monitoring
        channelBindings.add(new ChannelBindingStatus(
            name,
            Direction.OUTPUT,
            BindingState.ACTIVE, // Will be updated dynamically in getServiceStatus()
            0.0 // Will be updated by metrics collection
        ));
    }
}
