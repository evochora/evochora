package org.evochora.datapipeline.api.resources.database;

/**
 * Exception thrown when attempting to read metadata that doesn't exist yet.
 * <p>
 * This is a checked exception representing a normal condition in parallel mode
 * where indexers start before MetadataIndexer has finished. Callers should poll
 * until metadata becomes available.
 */
public class MetadataNotFoundException extends Exception {
    /**
     * Creates a new MetadataNotFoundException with the given message.
     *
     * @param message description of the metadata that is not available yet.
     */
    public MetadataNotFoundException(String message) {
        super(message);
    }
}

