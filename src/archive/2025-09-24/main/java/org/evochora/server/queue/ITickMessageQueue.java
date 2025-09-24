package org.evochora.server.queue;

import org.evochora.server.contracts.IQueueMessage;
import java.util.concurrent.TimeUnit;

/**
 * Blocking queue interface for exchanging tick messages between services.
 */
public interface ITickMessageQueue {
    void put(IQueueMessage message) throws InterruptedException;
    IQueueMessage take() throws InterruptedException;
    IQueueMessage poll(long timeout, TimeUnit unit) throws InterruptedException;
    IQueueMessage poll();
    int size();
}


