package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.resources.IResource;

/**
 * Combination interface that provides both metadata reading capability and resource management.
 * <p>
 * This interface combines {@link IMetadataReader} with {@link IResource} and {@link ISchemaAwareDatabase}
 * to provide a complete database capability for wrapper implementations.
 * <p>
 * <strong>Usage:</strong>
 * <ul>
 *   <li>Wrapper classes implement this interface for complete functionality</li>
 *   <li>Direct database implementations use only {@link IMetadataReader}</li>
 *   <li>This eliminates the need for casting and mock extensions in tests</li>
 * </ul>
 * <p>
 * <strong>Benefits:</strong>
 * <ul>
 *   <li>Type safety: No casting required</li>
 *   <li>Clean tests: No mock extensions needed</li>
 *   <li>Explicit contracts: Clear what capabilities are available</li>
 * </ul>
 */
public interface IResourceSchemaAwareMetadataReader extends IMetadataReader, IResource, ISchemaAwareDatabase, IConnectionManageable {
    
    /**
     * Gets the simulation run ID from the current database schema.
     * <p>
     * This method reads the run-id from the metadata table in the currently
     * set schema. It is used by wrappers and indexers that need to discover
     * the run-id after schema switching.
     * <p>
     * <strong>Note:</strong> This method is NOT needed by {@link IDatabaseReader}
     * implementations because they already know their runId from construction.
     * 
     * @return Simulation run ID from current schema
     * @throws MetadataNotFoundException if metadata doesn't exist in current schema
     */
    String getRunIdInCurrentSchema() throws MetadataNotFoundException;
}
