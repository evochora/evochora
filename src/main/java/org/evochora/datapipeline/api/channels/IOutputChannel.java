package org.evochora.datapipeline.api.channels;

/**
 * Defines the contract for a component to which messages can be written.
 *
 * @param <T> The type of the message to be written.
 */
public interface IOutputChannel<T> {

    /**
     * Writes a message to the channel, blocking if necessary until space becomes available.
     *
     * @param message The message to write to the channel.
     * @throws InterruptedException if the thread is interrupted while waiting to write the message.
     */
    void write(T message) throws InterruptedException;
}
