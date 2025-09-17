package org.evochora.datapipeline.api.services;

/**
 * Represents the direction of a channel binding relative to a service.
 */
public enum Direction {
    /**
     * The service reads data from the channel.
     */
    INPUT,

    /**
     * The service writes data to the channel.
     */
    OUTPUT
}
