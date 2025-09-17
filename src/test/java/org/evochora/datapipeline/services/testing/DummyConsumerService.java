package org.evochora.datapipeline.services.testing;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.channels.IInputChannel;
import org.evochora.datapipeline.api.services.State;
import org.evochora.datapipeline.services.BaseService;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A dummy consumer service for testing purposes. It continuously reads messages
 * from its input channel and counts them until it is stopped.
 */
public class DummyConsumerService extends BaseService {

    private final AtomicInteger receivedMessageCount = new AtomicInteger(0);
    private IInputChannel<Integer> inputChannel;

    public DummyConsumerService(Config options) {
        // No options needed for this dummy service
    }

    @Override
    @SuppressWarnings("unchecked")
    public void addInputChannel(String name, IInputChannel<?> channel) {
        this.inputChannel = (IInputChannel<Integer>) channel;
    }

    @Override
    protected void run() {
        try {
            while (currentState.get() == State.RUNNING && !Thread.currentThread().isInterrupted()) {
                inputChannel.read();
                receivedMessageCount.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
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
