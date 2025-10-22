package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.resources.IResource;

/**
 * Combination interface that provides both metadata writing capability and resource management.
 * <p>
 * This interface combines {@link IMetadataWriter} with {@link IResource} and {@link ISchemaAwareDatabase}
 * to provide a complete database capability for wrapper implementations.
 * <p>
 * <strong>Usage:</strong>
 * <ul>
 *   <li>Wrapper classes implement this interface for complete functionality</li>
 *   <li>Direct database implementations use only {@link IMetadataWriter}</li>
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
public interface IResourceSchemaAwareMetadataWriter extends IMetadataWriter, IResource, ISchemaAwareDatabase {
    // Combination interface - inherits all methods from the three base interfaces
    // No additional methods needed
}
