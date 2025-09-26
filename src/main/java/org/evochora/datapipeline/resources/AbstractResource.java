package org.evochora.datapipeline.resources;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IResource;
import java.util.Objects;

/**
 * Abstract base class for all IResource implementations, providing common
 * functionality for name and configuration handling.
 */
public abstract class AbstractResource implements IResource {
    protected final String resourceName;
    protected final Config options;

    /**
     * Constructor for AbstractResource.
     *
     * @param name    The unique name of the resource instance from the configuration.
     * @param options The configuration object for this resource instance.
     */
    protected AbstractResource(String name, Config options) {
        this.resourceName = Objects.requireNonNull(name, "Resource name cannot be null");
        this.options = Objects.requireNonNull(options, "Resource options cannot be null");
    }

    @Override
    public String getResourceName() {
        return resourceName;
    }

    /**
     * Returns the configuration object for this resource.
     *
     * @return The configuration object.
     */
    public Config getOptions() {
        return options;
    }
}