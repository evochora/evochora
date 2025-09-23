package org.evochora.datapipeline.core;

import org.evochora.datapipeline.api.channels.IOutputChannel;
import org.evochora.datapipeline.api.services.Direction;

/**
 * Concrete implementation of AbstractChannelBinding for output channels.
 * This class wraps an IOutputChannel and provides monitoring capabilities by
 * counting successful write operations.
 * 
 * @param <T> The message type handled by the output channel
 */
public class OutputChannelBinding<T> extends AbstractChannelBinding<T> implements IOutputChannel<T> {
    
    private final IOutputChannel<T> delegate;
    
    /**
     * Creates a new OutputChannelBinding that wraps the specified output channel.
     * 
     * @param serviceName The name of the service this binding belongs to
     * @param portName    The logical name of the port this binding is attached to
     * @param channelName The name of the channel this binding wraps
     * @param delegate The actual output channel being wrapped
     */
    public OutputChannelBinding(String serviceName, String portName, String channelName, IOutputChannel<T> delegate) {
        super(serviceName, portName, channelName, Direction.OUTPUT, delegate);
        this.delegate = delegate;
    }
    
    /**
     * Writes a message to the underlying channel and increments the message count.
     * This method delegates to the wrapped channel and tracks successful writes.
     * 
     * @param message The message to write to the channel
     * @throws InterruptedException if the write operation is interrupted
     */
    @Override
    public void write(T message) throws InterruptedException {
        try {
            delegate.write(message);
            incrementCount(); // Track successful write
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
     * Returns the underlying output channel.
     * This method provides access to the actual channel for operations that need
     * to bypass the monitoring wrapper.
     * 
     * @return The wrapped output channel
     */
    public IOutputChannel<T> getDelegate() {
        return delegate;
    }
}
