package org.evochora.cli;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IResource;

import java.lang.reflect.Constructor;

/**
 * A factory for creating data pipeline resources from configuration within the CLI.
 * This allows CLI commands to use the same resources as the main data pipeline
 * without being hardcoded to specific implementations like FileSystemStorageResource.
 */
public class CliResourceFactory {

    /**
     * Creates a resource instance from its configuration.
     *
     * @param name               A logical name for the resource instance (for logging).
     * @param resourceInterface The interface the resource is expected to implement.
     * @param config             The configuration block for the resource, containing 'className' and 'options'.
     * @param <T>                The type of the resource interface.
     * @return An instantiated and configured resource.
     * @throws RuntimeException if the resource cannot be created.
     */
    @SuppressWarnings("unchecked")
    public static <T extends IResource> T create(String name, Class<T> resourceInterface, Config config) {
        if (!config.hasPath("className")) {
            throw new IllegalArgumentException("Resource configuration is missing 'className' property.");
        }
        String className = config.getString("className");
        Config options = config.hasPath("options") ? config.getConfig("options") : com.typesafe.config.ConfigFactory.empty();

        try {
            Class<?> resourceClass = Class.forName(className);
            if (!resourceInterface.isAssignableFrom(resourceClass)) {
                throw new ClassCastException(String.format("Class %s does not implement the required interface %s", className, resourceInterface.getName()));
            }

            Constructor<?> constructor = resourceClass.getConstructor(String.class, Config.class);
            return (T) constructor.newInstance(name, options);

        } catch (Exception e) {
            throw new RuntimeException("Failed to create resource '" + name + "' with class " + className, e);
        }
    }
}
