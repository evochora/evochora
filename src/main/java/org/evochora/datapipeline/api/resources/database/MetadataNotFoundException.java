package org.evochora.datapipeline.api.resources.database;

/**
 * Exception thrown when attempting to read metadata that doesn't exist yet.
 * <p>
 * This is a checked exception representing a normal condition in parallel mode
 * where indexers start before MetadataIndexer has finished. Callers should poll
 * until metadata becomes available.
 */
public class MetadataNotFoundException extends Exception {
    public MetadataNotFoundException(String message) {
        super(message);
    }
}

