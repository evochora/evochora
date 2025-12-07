package org.evochora.datapipeline.api.resources;

import com.typesafe.config.Config;

/**
 * Interface for resource initializers that run before resources are instantiated.
 * <p>
 * Use for system-level configuration that must happen early, such as setting
 * system properties before a driver is loaded (e.g., H2's temp directory must
 * be configured before the H2 driver is loaded).
 * <p>
 * <strong>Lifecycle:</strong>
 * <ol>
 *   <li>ServiceManager collects all {@code init} blocks from resource definitions</li>
 *   <li>Initializers are deduplicated by class name (each runs only once)</li>
 *   <li>All initializers run before any resource class is loaded</li>
 *   <li>Resources are then instantiated normally</li>
 * </ol>
 * <p>
 * <strong>Example configuration:</strong>
 * <pre>{@code
 * pipeline {
 *   resources {
 *     batch-topic {
 *       init {
 *         className = "org.evochora.datapipeline.resources.database.H2Initializer"
 *         options = ${pipeline.database}
 *       }
 *       className = "org.evochora.datapipeline.resources.topics.H2TopicResource"
 *       options = ${pipeline.database} { ... }
 *     }
 *   }
 * }
 * }</pre>
 * <p>
 * <strong>Thread Safety:</strong> Initializers are called sequentially from the main thread
 * during ServiceManager construction. They do not need to be thread-safe.
 *
 * @see org.evochora.datapipeline.resources.database.H2Initializer
 */
public interface IResourceInitializer {

    /**
     * Called once before any resources are instantiated.
     * <p>
     * Use this method for early system-level configuration such as:
     * <ul>
     *   <li>Setting system properties that drivers read at load time</li>
     *   <li>Creating required directories</li>
     *   <li>Registering JDBC drivers</li>
     * </ul>
     * <p>
     * This method is called exactly once per initializer class, even if multiple
     * resources reference the same initializer.
     *
     * @param options Configuration from the {@code init.options} block.
     *                May be empty if no options were specified.
     */
    void initialize(Config options);
}

