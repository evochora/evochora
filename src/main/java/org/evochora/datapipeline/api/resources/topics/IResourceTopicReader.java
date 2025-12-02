package org.evochora.datapipeline.api.resources.topics;

import com.google.protobuf.Message;
import org.evochora.datapipeline.api.resources.IResource;

/**
 * Combination interface that provides both topic reading capability and resource management.
 * <p>
 * This interface combines {@link ITopicReader} with {@link IResource} to provide
 * a complete topic capability for service implementations.
 * <p>
 * <strong>Usage:</strong>
 * <ul>
 *   <li>Service implementations use this interface for complete functionality</li>
 *   <li>Direct topic implementations use only {@link ITopicReader}</li>
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
public interface IResourceTopicReader<T extends Message, K> extends ITopicReader<T, K> {
    // Combination interface - inherits all methods from the two base interfaces
    // No additional methods needed
}
