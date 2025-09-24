package org.evochora.datapipeline.api.resources;

/**
 * Marker interface for all resources in the data pipeline.
 * <p>
 * Resources are components that provide access to external systems, such as
 * message queues, databases, or file storage. This interface serves as a common
 * type for all resource implementations, allowing them to be managed by the
 * ServiceManager.
 */
public interface IResource {
}