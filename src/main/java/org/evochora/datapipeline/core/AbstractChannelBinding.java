package org.evochora.datapipeline.core;

import org.evochora.datapipeline.api.services.Direction;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for channel binding wrappers that provide monitoring capabilities.
 * This class contains common functionality shared between input and output channel bindings,
 * including metrics collection and metadata storage.
 * 
 * @param <T> The message type handled by the channel
 */
public abstract class AbstractChannelBinding<T> {
    
    protected final String serviceName;
    protected final String portName;
    protected final String channelName;
    protected final Direction direction;
    protected final AtomicLong messageCount;
    protected final AtomicLong errorCount;
    protected final Object underlyingChannel;
    
    /**
     * Creates a new AbstractChannelBinding with the specified parameters.
     * 
     * @param serviceName The name of the a service this binding belongs to
     * @param portName The logical name of the port this binding is attached to
     * @param channelName The name of the channel this binding wraps
     * @param direction The direction of the binding (INPUT or OUTPUT)
     * @param underlyingChannel The actual channel instance being wrapped
     */
    protected AbstractChannelBinding(String serviceName, String portName, String channelName, Direction direction, Object underlyingChannel) {
        this.serviceName = serviceName;
        this.portName = portName;
        this.channelName = channelName;
        this.direction = direction;
        this.messageCount = new AtomicLong(0);
        this.errorCount = new AtomicLong(0);
        this.underlyingChannel = underlyingChannel;
    }
    
    /**
     * Returns the name of the service this binding belongs to.
     * 
     * @return The service name
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * Returns the logical port name this binding is associated with.
     *
     * @return The port name
     */
    public String getPortName() {
        return portName;
    }
    
    /**
     * Returns the name of the channel this binding wraps.
     * 
     * @return The channel name
     */
    public String getChannelName() {
        return channelName;
    }
    
    /**
     * Returns the direction of this binding.
     * 
     * @return The direction (INPUT or OUTPUT)
     */
    public Direction getDirection() {
        return direction;
    }
    
    /**
     * Returns the current message count and atomically resets it to zero.
     * This method is thread-safe and is used by the metrics collection system.
     * 
     * @return The current message count before reset
     */
    public long getAndResetCount() {
        return messageCount.getAndSet(0);
    }
    
    /**
     * Returns the underlying channel instance.
     * This allows access to the actual channel for operations like checking if it's monitorable.
     * 
     * @return The underlying channel object
     */
    public Object getUnderlyingChannel() {
        return underlyingChannel;
    }
    
    /**
     * Atomically increments the message count.
     * This method is called by concrete implementations when a message is successfully processed.
     * 
     * @return The new count after increment
     */
    protected long incrementCount() {
        return messageCount.incrementAndGet();
    }

    /**
     * Atomically increments the error counter for this binding.
     * This method is called when channel communication errors occur.
     */
    protected void incrementErrorCount() {
        errorCount.incrementAndGet();
    }

    /**
     * Public method for testing purposes to increment error count.
     * This method should only be used in tests.
     */
    public void incrementErrorCountForTesting() {
        incrementErrorCount();
    }

    /**
     * Returns the current error count for this binding.
     * This count accumulates since service start and is only reset on service restart.
     *
     * @return The number of errors encountered since service start
     */
    public long getErrorCount() {
        return errorCount.get();
    }

    /**
     * Resets the error counter to zero.
     * This method should only be called when the service starts.
     */
    public void resetErrorCount() {
        errorCount.set(0);
    }
    
    /**
     * Returns a string representation of this binding for debugging purposes.
     * 
     * @return A string describing this binding
     */
    @Override
    public String toString() {
        return String.format("%s[service=%s, port=%s, channel=%s, direction=%s, count=%d]",
            this.getClass().getSimpleName(), serviceName, portName, channelName, direction, messageCount.get());
    }
}
