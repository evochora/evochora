package org.evochora.datapipeline.api.resources.database;

/**
 * Thrown when no indexed organism state exists for a given tick and organism id.
 */
public class OrganismNotFoundException extends Exception {

    /**
     * Creates a new OrganismNotFoundException with the given message.
     *
     * @param message description of the missing organism state.
     */
    public OrganismNotFoundException(String message) {
        super(message);
    }
}


