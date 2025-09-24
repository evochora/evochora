package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.channels.IOutputChannel;
import org.evochora.datapipeline.api.services.State;

import java.util.List;
import java.util.Map;

/**
 * A dummy producer service for testing purposes. It writes a configurable number of
 * integer messages to its output channel and then terminates.
 */
public class DummyProducerService extends AbstractService {

    private final int messageCount;
    private final int executionDelay;
    private final IOutputChannel<Integer> outputChannel;

    public DummyProducerService(Config options, Map<String, List<Object>> resources) {
        super(options, resources);
        this.messageCount = options.hasPath("messageCount") ? options.getInt("messageCount") : 10;
        this.executionDelay = options.hasPath("executionDelay") ? options.getInt("executionDelay") : 0;
        this.outputChannel = getRequiredResource("messages");
    }

    @Override
    protected void run() {
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
