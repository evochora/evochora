package org.evochora.datapipeline.api.resources.database;

/**
 * Thrown when no indexed tick exists for a given tick number.
 */
public class TickNotFoundException extends Exception {

    /**
     * Creates a new TickNotFoundException with the given message.
     *
     * @param message description of the missing tick.
     */
    public TickNotFoundException(String message) {
        super(message);
    }
}
