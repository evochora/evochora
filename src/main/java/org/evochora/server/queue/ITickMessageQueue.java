package org.evochora.server.queue;

import org.evochora.server.contracts.IQueueMessage;

/**
 * Blocking queue interface for exchanging tick messages between services.
 */
public interface ITickMessageQueue {
    void put(IQueueMessage message) throws InterruptedException;
    IQueueMessage take() throws InterruptedException;
    int size();
}


