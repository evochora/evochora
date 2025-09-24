package org.evochora.datapipeline.api.resources;

/**
 * Marker interface for wrapped resources.
 * <p>
 * This interface is used to identify resources that are wrappers around other
 * resources, typically created by {@link IContextualResource#getWrappedResource(ResourceContext)}.
 * Wrapped resources provide specialized functionality based on their usage context.
 */
public interface IWrappedResource extends IResource {
}
