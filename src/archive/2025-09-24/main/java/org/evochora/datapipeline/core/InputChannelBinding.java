package org.evochora.datapipeline.core;

import org.evochora.datapipeline.api.channels.IInputChannel;
import org.evochora.datapipeline.api.services.Direction;

/**
 * Concrete implementation of AbstractChannelBinding for input channels.
 * This class wraps an IInputChannel and provides monitoring capabilities by
 * counting successful read operations.
 * 
 * @param <T> The message type handled by the input channel
 */
public class InputChannelBinding<T> extends AbstractChannelBinding<T> implements IInputChannel<T> {
    
    private final IInputChannel<T> delegate;
    
    /**
     * Creates a new InputChannelBinding that wraps the specified input channel.
     * 
     * @param serviceName The name of the service this binding belongs to
     * @param portName    The logical name of the port this binding is attached to
     * @param channelName The name of the channel this binding wraps
     * @param delegate The actual input channel being wrapped
     */
    public InputChannelBinding(String serviceName, String portName, String channelName, IInputChannel<T> delegate) {
        super(serviceName, portName, channelName, Direction.INPUT, delegate);
        this.delegate = delegate;
    }
    
    /**
     * Reads a message from the underlying channel and increments the message count.
     * This method delegates to the wrapped channel and tracks successful reads.
     * 
     * @return The message read from the channel
     * @throws InterruptedException if the read operation is interrupted
     */
    @Override
    public T read() throws InterruptedException {
        try {
            T message = delegate.read();
            incrementCount(); // Track successful read
            return message;
        } catch (InterruptedException e) {
            // Don't count InterruptedException - it's shutdown-related
            throw e;
        } catch (Exception e) {
            // Count all other exceptions as channel communication errors
            incrementErrorCount();
            throw e;
        }
    }
    
    /**
     * Returns the underlying input channel.
     * This method provides access to the actual channel for operations that need
     * to bypass the monitoring wrapper.
     * 
     * @return The wrapped input channel
     */
    public IInputChannel<T> getDelegate() {
        return delegate;
    }
}
