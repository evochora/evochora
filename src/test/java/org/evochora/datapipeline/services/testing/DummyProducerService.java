package org.evochora.datapipeline.services.testing;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.channels.IOutputChannel;
import org.evochora.datapipeline.api.services.State;
import org.evochora.datapipeline.services.BaseService;

/**
 * A dummy producer service for testing purposes. It writes a configurable number of
 * integer messages to its output channel and then terminates.
 */
public class DummyProducerService extends BaseService {

    private final int messageCount;
    private IOutputChannel<Integer> outputChannel;
    private int executionDelay = 0;

    public DummyProducerService(Config options) {
        this.messageCount = options.hasPath("messageCount") ? options.getInt("messageCount") : 10;
    }

    public void setExecutionDelay(int millis) {
        this.executionDelay = millis;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void addOutputChannel(String name, IOutputChannel<?> channel) {
        this.outputChannel = (IOutputChannel<Integer>) channel;
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            currentState.set(State.STOPPED);
        }
    }
}
