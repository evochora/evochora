package org.evochora.datapipeline.services.testing;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.channels.IInputChannel;
import org.evochora.datapipeline.api.channels.IMonitorableChannel;
import org.evochora.datapipeline.api.services.State;
import org.evochora.datapipeline.services.AbstractService;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A dummy consumer service for testing purposes. It continuously reads messages
 * from its input channel and counts them until it is stopped.
 */
public class DummyConsumerService extends AbstractService {

    private final AtomicInteger receivedMessageCount = new AtomicInteger(0);
    private IInputChannel<Integer> inputChannel;

    public DummyConsumerService(Config options) {
        // No options needed for this dummy service
    }

    @Override
    @SuppressWarnings("unchecked")
    public void addInputChannel(String name, IInputChannel<?> channel) {
        this.inputChannel = (IInputChannel<Integer>) channel;
        // Add channel binding status
        channelBindings.add(new org.evochora.datapipeline.api.services.ChannelBindingStatus(
                name, 
                org.evochora.datapipeline.api.services.Direction.INPUT,
                org.evochora.datapipeline.api.services.BindingState.ACTIVE,
                0.0 // Will be updated with actual throughput
        ));
    }

    @Override
    protected void run() {
        try {
            while (currentState.get() == State.RUNNING && !Thread.currentThread().isInterrupted()) {
                // Reading is blocking but will be interrupted by stopAll()
                Integer message = inputChannel.read();
                receivedMessageCount.incrementAndGet();
            }
        } catch (InterruptedException e) {
            // Interruption is a signal to stop, but we must drain the queue first.
            Thread.currentThread().interrupt();
        } finally {
            // After being stopped or interrupted, drain any remaining messages from the channel.
            // This requires a cast because the interface doesn't support non-blocking reads.
            if (inputChannel instanceof IMonitorableChannel) {
                IMonitorableChannel monitorableChannel = (IMonitorableChannel) inputChannel;
                while (monitorableChannel.getQueueSize() > 0) {
                    try {
                        // We can't use a non-blocking poll from the interface,
                        // but we can read and assume it won't block if size > 0.
                        inputChannel.read();
                        receivedMessageCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        // Should not happen as we are not in a blocking read here, but if it does...
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            currentState.set(State.STOPPED);
        }
    }

    /**
     * @return The number of messages received by this service.
     */
    public int getReceivedMessageCount() {
        return receivedMessageCount.get();
    }
}
