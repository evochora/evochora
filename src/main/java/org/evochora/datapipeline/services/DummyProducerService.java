package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.channels.IOutputChannel;
import org.evochora.datapipeline.api.services.State;
import org.evochora.datapipeline.core.OutputChannelBinding;

/**
 * A dummy producer service for testing purposes. It writes a configurable number of
 * integer messages to its output channel and then terminates.
 */
public class DummyProducerService extends AbstractService {

    private final int messageCount;
    private int executionDelay = 0;

    public DummyProducerService(Config options) {
        this.messageCount = options.hasPath("messageCount") ? options.getInt("messageCount") : 10;
    }

    public void setExecutionDelay(int millis) {
        this.executionDelay = millis;
    }

    @Override
    public void addOutputChannel(String portName, OutputChannelBinding<?> binding) {
        // This service supports output channels, so we override the default (which throws an exception)
        // and register the channel.
        registerOutputChannel(portName, binding);
    }

    @Override
    protected void run() {
        IOutputChannel<Integer> outputChannel = getRequiredOutputChannel("messages");
        try {
            for (int i = 0; i < messageCount; i++) {
                synchronized (pauseLock) {
                    while (paused) {
                        pauseLock.wait();
                    }
                }
                if (currentState.get() == State.STOPPED) {
                    break;
                }
                outputChannel.write(i);
                if (executionDelay > 0) {
                    Thread.sleep(executionDelay);
                }
            }
            
            // After sending all messages, keep running until explicitly stopped
            // This is the normal behavior for services - they don't stop themselves
            while (currentState.get() != State.STOPPED) {
                synchronized (pauseLock) {
                    while (paused) {
                        pauseLock.wait();
                    }
                }
                // Small sleep to avoid busy waiting
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Don't set state to STOPPED - let AbstractService handle that
    }
}
