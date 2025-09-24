package org.evochora.datapipeline.api.resources;

/**
 * An interface for resources that can provide specialized implementations based on their usage context.
 * <p>
 * When a resource implements this interface, the ServiceManager will call
 * {@link #getInjectedObject(ResourceContext)} to get the object that should be
 * injected into the service, rather than injecting the resource itself. This allows
 * a single resource definition to provide different functionalities based on which
 * service and port it is connected to.
 */
public interface IContextualResource extends IResource {

    /**
     * Returns the wrapped resource object that should be injected into a service.
     *
     * @param context The context describing how the resource is being used.
     * @return The {@link IWrappedResource} object to be injected.
     */
    IWrappedResource getWrappedResource(ResourceContext context);
}