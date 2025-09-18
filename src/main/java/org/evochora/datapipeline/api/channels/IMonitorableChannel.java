package org.evochora.datapipeline.api.channels;

/**
 * An optional interface that can be implemented by channels to provide global metrics.
 */
public interface IMonitorableChannel {

    /**
     * Gets the current number of messages in the channel's queue.
     *
     * @return The number of messages currently buffered.
     */
    long getBacklogSize();

    /**
     * Gets the total capacity of the channel's queue.
     *
     * @return The maximum number of messages the channel can buffer, returns -1 if the capacity is unlimited.
     */
    long getCapacity();
}
