package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.resources.IResource;

/**
 * Combination interface that provides both organism data writing capability and resource management.
 * <p>
 * This interface combines {@link IOrganismDataWriter} with {@link IResource} and {@link ISchemaAwareDatabase}
 * to provide a complete database capability for wrapper implementations.
 * <p>
 * <strong>Usage:</strong>
 * <ul>
 *   <li>Wrapper classes implement this interface for complete functionality.</li>
 *   <li>Direct database implementations use only {@link IOrganismDataWriter}.</li>
 * </ul>
 */
public interface IResourceSchemaAwareOrganismDataWriter extends IOrganismDataWriter, IResource, ISchemaAwareDatabase {
    // Combination interface - inherits all methods from the three base interfaces
}


