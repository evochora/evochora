package org.evochora.datapipeline.api.channels;

/**
 * Defines the contract for a component from which messages can be read.
 *
 * @param <T> The type of the message to be read.
 */
public interface IInputChannel<T> {

    /**
     * Reads a message from the channel, blocking until a message is available.
     *
     * @return The message read from the channel.
     * @throws InterruptedException if the thread is interrupted while waiting for a message.
     */
    T read() throws InterruptedException;
}
