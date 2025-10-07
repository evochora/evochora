package org.evochora.node.processes;

import com.typesafe.config.Config;
import org.evochora.node.spi.IProcess;

import java.util.Collections;
import java.util.Map;

/**
 * An abstract base class for {@link IProcess} implementations. It provides a consistent
 * constructor for dependency injection, ensuring that every process receives its
 * dependencies and specific configuration block.
 *
 * <p>Dependencies are explicitly declared in the configuration and injected via the constructor.
 * Subclasses can use {@link #getDependency(String, Class)} for type-safe access to required
 * dependencies, or {@link #getOptionalDependency(String, Class)} for optional ones.</p>
 */
public abstract class AbstractProcess implements IProcess {

    protected final String processName;
    protected final Map<String, Object> dependencies;
    protected final Config options;

    /**
     * Constructs a new AbstractProcess.
     *
     * @param processName The name of this process instance from the configuration.
     * @param dependencies A map of dependency names to their instances, as declared in the configuration.
     * @param options  The HOCON configuration specific to this process instance.
     */
    public AbstractProcess(final String processName, final Map<String, Object> dependencies, final Config options) {
        this.processName = processName;
        this.dependencies = dependencies != null ? dependencies : Collections.emptyMap();
        this.options = options;
    }

    /**
     * Gets the name of this process instance.
     *
     * @return The process name.
     */
    public String getProcessName() {
        return processName;
    }

    /**
     * Retrieves a required dependency with type safety.
     *
     * @param name The dependency name as declared in the configuration
     * @param expectedType The expected type of the dependency
     * @param <T> The type parameter
     * @return The dependency instance, cast to the expected type
     * @throws IllegalArgumentException if the dependency is not found or has the wrong type
     */
    protected <T> T getDependency(final String name, final Class<T> expectedType) {
        final Object dep = dependencies.get(name);

        if (dep == null) {
            throw new IllegalArgumentException(
                "Required dependency '" + name + "' not found for process '" + processName + "'"
            );
        }

        if (!expectedType.isAssignableFrom(dep.getClass())) {
            throw new IllegalArgumentException(
                "Dependency '" + name + "' for process '" + processName + "' is " +
                dep.getClass().getName() + " but expected " + expectedType.getName()
            );
        }

        return expectedType.cast(dep);
    }

    /**
     * Retrieves an optional dependency with type safety.
     *
     * @param name The dependency name as declared in the configuration
     * @param expectedType The expected type of the dependency
     * @param <T> The type parameter
     * @return The dependency instance, or null if not present
     * @throws IllegalArgumentException if the dependency has the wrong type
     */
    protected <T> T getOptionalDependency(final String name, final Class<T> expectedType) {
        final Object dep = dependencies.get(name);

        if (dep == null) {
            return null;
        }

        if (!expectedType.isAssignableFrom(dep.getClass())) {
            throw new IllegalArgumentException(
                "Dependency '" + name + "' for process '" + processName + "' is " +
                dep.getClass().getName() + " but expected " + expectedType.getName()
            );
        }

        return expectedType.cast(dep);
    }
}